package dev.expository.transfer.sftp

import dev.expository.transfer.planner.DirectoryCreatePlan
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferRoute
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.security.PublicKey

/**
 * Exact SHA-256 SSH public-key fingerprint pinning. Never accept an unknown
 * host key by default or silently trust a changed key after reconnect.
 */
class SftpHostKeyPin(val fingerprint: String) {
    init {
        require(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/=]{20,128}"))) {
            "EXPECTED_EXPLICIT_SHA256_FINGERPRINT"
        }
    }
    fun verifies(presentedKey: PublicKey): Boolean =
        KeyUtils.checkFingerPrint(fingerprint, BuiltinDigests.sha256, presentedKey)?.key == true

    companion object {
        fun observed(key: PublicKey): String =
            KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)
    }
}

data class SftpTarget(val host: String, val port: Int = 22, val trustedHostKey: SftpHostKeyPin) {
    init {
        require(host.isNotBlank() && host.length <= 253 &&
            host.none { it.isWhitespace() || it == '/' || it == '\\' ||
                it == '@' || it == ':' || it.code < 32 || it.code == 127 }) { "INVALID_HOST" }
        require(port in 1..65535) { "INVALID_PORT" }
    }
}

object SftpPath {
    fun requireAbsolute(path: String): String {
        require(path.startsWith("/") && path.toByteArray(StandardCharsets.UTF_8).size <= 4096)
        require(path.none { it == '\\' || it.code < 32 || it.code == 127 })
        if (path != "/") require(path.split('/').drop(1).all {
            it.isNotEmpty() && it != "." && it != ".."
        })
        return path
    }
    fun validateChild(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "INVALID_SFTP_CHILD" }
        return name
    }

    /** Canonical single-child path, including the server root without // or .. */
    fun childPath(absoluteParent: String, childName: String): String {
        val parent = requireAbsolute(absoluteParent)
        val child = validateChild(childName)
        return requireAbsolute(if (parent == "/") "/$child" else "$parent/$child")
    }
}

object SecureSftp {
    fun configuredClient(pin: SftpHostKeyPin): SshClient =
        SshClient.setUpDefaultClient().apply {
            // MINA defaults to an accept-all verifier. Override BEFORE start.
            serverKeyVerifier = ServerKeyVerifier { _, _, presented ->
                pin.verifies(presented)
            }
        }

    fun connect(target: SftpTarget, username: String, password: CharArray): SftpSession {
        require(username.isNotBlank() && username.none { it == '\n' || it == '\r' || it == '\u0000' })
        require(password.isNotEmpty() && password.none { it == '\n' || it == '\r' || it == '\u0000' })
        val client = configuredClient(target.trustedHostKey)
        var session: ClientSession? = null
        try {
            client.start()
            session = client.connect(username, target.host, target.port).verify(12_000L).session
            session.addPasswordIdentity(String(password))
            session.auth().verify(12_000L)
            val sftp = SftpClientFactory.instance().createSftpClient(session)
            return SftpSession(client, session, sftp)
        } catch (_: Exception) {
            runCatching { session?.close() }
            runCatching { client.stop() }
            throw IOException("SFTP_CONNECTION_OR_HOST_KEY_FAILED")
        }
    }
}

data class SftpRemoteEntry(val name: String, val directory: Boolean, val size: Long, val modifiedMillis: Long?) {
    val revision: String get() = listOf(size, modifiedMillis ?: -1L, directory).joinToString(":")
}
data class SftpBrowsePage(val entries: List<SftpRemoteEntry>, val truncated: Boolean)

/**
 * Mutation authorization requires the ENTIRE bounded destination namespace,
 * never the first screenful. A truncated listing is informational UI only.
 * This remains conservative for unknown servers with >2,000 children.
 */
object SftpMutationListing {
    fun requireComplete(page: SftpBrowsePage): SftpBrowsePage {
        require(!page.truncated && page.entries.size <= SftpBrowseBounds.MAX) {
            "INCOMPLETE_SFTP_MUTATION_LISTING"
        }
        // A case-sensitive server may expose two distinct paths that a target
        // provider treats as the same name. Browsing can show both, but write
        // preflights may never treat this namespace as collision-free.
        val normalizedNames = HashSet<String>()
        require(page.entries.all { entry ->
            runCatching { SftpPath.validateChild(entry.name) }.isSuccess &&
                normalizedNames.add(Normalizer.normalize(
                    entry.name, Normalizer.Form.NFC).lowercase(Locale.ROOT))
        }) { "AMBIGUOUS_SFTP_MUTATION_NAMES" }
        return page
    }
}

/** Explicit, bounded rescans. No server paging cursor or complete-write proof is inferred. */
object SftpBrowseBounds {
    const val INITIAL = 200
    const val STEP = 200
    const val MAX = 2000

    fun requireValid(limit: Int): Int =
        limit.also {
            require(it in 1..MAX) { "INVALID_SFTP_BROWSE_WINDOW" }
        }

    fun next(limit: Int): Int {
        requireValid(limit)
        require(limit >= INITIAL && limit % STEP == 0) {
            "INVALID_PROGRESSIVE_BROWSE_STEP"
        }
        return (limit + STEP).coerceAtMost(MAX)
    }
}

class SftpSession internal constructor(
    private val ssh: SshClient,
    private val session: ClientSession,
    private val sftp: SftpClient
) : AutoCloseable {
    /**
     * Legacy list convenience is bounded as strictly as a fresh mutation
     * witness. Never consume a potentially unbounded remote Iterable into
     * memory or pretend that the first 2,000 results were the whole folder.
     * Call browse() for progressive, explicitly incomplete UI previews.
     */
    fun list(absoluteFolder: String): List<String> =
        completeMutationListing(absoluteFolder).entries.map { it.name }

    fun openRead(absoluteFile: String): InputStream =
        sftp.read(SftpPath.requireAbsolute(absoluteFile))

    /**
     * Bounded UI view. Server-side listings may be huge; never materialize
     * the entire directory in Compose. Full pagination is a later G4 gate.
     */
    fun browse(absoluteFolder: String, maxEntries: Int = 200): SftpBrowsePage {
        SftpBrowseBounds.requireValid(maxEntries)
        val entries = sftp.readDir(SftpPath.requireAbsolute(absoluteFolder))
            .asSequence().filter { it.filename != "." && it.filename != ".." }
            .take(maxEntries + 1).map { item ->
                val a = item.attributes
                SftpRemoteEntry(SftpPath.validateChild(item.filename),
                    a.isDirectory, a.size, a.modifyTime?.toMillis())
            }.toList()
        // Duplicate names are not stable UI identities. Treat an ambiguous
        // server listing as an error rather than rendering duplicate item keys.
        require(entries.map { it.name }.distinct().size == entries.size) {
            "AMBIGUOUS_SFTP_DIRECTORY"
        }
        return SftpBrowsePage(entries.take(maxEntries), entries.size > maxEntries)
    }

    /**
     * Authoritative bounded sibling witness for upload, Move and New Folder.
     * The existing preview API stays progressive and can show partial pages,
     * but a mutation must fail CLOSED until the full <=2,000-child parent is
     * observed. No caching or cursor reuse between preflight rescans.
     */
    fun completeMutationListing(absoluteFolder: String): SftpBrowsePage =
        SftpMutationListing.requireComplete(
            browse(absoluteFolder, SftpBrowseBounds.MAX)
        )

    /**
     * Exclusive create, never truncate, append or replace an existing file.
     * Caller must have a fresh, immutable COPY plan before this mutation.
     * SFTP status on close is only a write ACK; independent readback decides
     * whether source and destination contents are equal.
     */
    fun openExclusiveWrite(absoluteFile: String): OutputStream {
        val path = SftpPath.requireAbsolute(absoluteFile)
        require(path != "/") { "NOT_A_FILE" }
        return sftp.write(path, SftpClient.OpenMode.Write,
            SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)
    }

    /**
     * Direct server-side MKDIR. The caller must revalidate the exact immutable
     * directory-create plan and commit its attempt journal BEFORE this call.
     * Server acknowledgement alone is not proof that a directory was created.
     */
    fun createPlannedDirectory(plan: DirectoryCreatePlan) {
        require(plan.executable) { "BLOCKED_DIRECTORY_PLAN" }
        val path = SftpPath.requireAbsolute(plan.absoluteTarget)
        require(path != "/")
        sftp.mkdir(path)
    }

    /**
     * Same authenticated server-only native Move, no file bytes downloaded.
     * NEVER request Overwrite/Atomic extension flags; caller must revalidate a
     * collision-free immutable Move plan, journal the attempt, and reconcile.
     * Server behavior beyond controlled SFTP fixtures is not yet accepted.
     */
    fun movePlannedFile(plan: TransferPlan, fromPath: String, intoPath: String) {
        require(plan.executable && plan.operation == Operation.MOVE &&
            plan.route == TransferRoute.VERIFIED_REMOTE_RENAME) {
            "NOT_VERIFIED_NATIVE_MOVE_PLAN"
        }
        val from = SftpPath.requireAbsolute(fromPath)
        val to = SftpPath.requireAbsolute(intoPath)
        require(from != "/" && to != "/" && from != to) { "INVALID_MOVE_PATH" }
        sftp.rename(from, to) // absent Overwrite flag: fail when destination exists
    }

    fun observeDirectory(absolutePath: String): Boolean =
        sftp.stat(SftpPath.requireAbsolute(absolutePath)).isDirectory

    fun statFile(absoluteFile: String): SftpRemoteEntry {
        val path = SftpPath.requireAbsolute(absoluteFile)
        require(path != "/") { "NOT_A_FILE" }
        val a = sftp.stat(path)
        require(!a.isDirectory) { "NOT_A_FILE" }
        return SftpRemoteEntry(SftpPath.validateChild(path.substringAfterLast('/')),
            false, a.size, a.modifyTime?.toMillis())
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { session.close() }
        runCatching { ssh.stop() }
    }
}
