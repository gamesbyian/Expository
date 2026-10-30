package dev.expository.transfer.ftps

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Both modes negotiate TLS; plaintext FTP is never used as a fallback. */
enum class FtpsMode { EXPLICIT_TLS, IMPLICIT_TLS }

/** Compatibility opt-in for a server that requires TLS 1.2 data-session reuse.
 * A secure TLS handshake, certificate chain, hostname and PROT P remain mandatory.
 * No SSLv3, TLSv1.0/1.1 or plaintext fallback.
 */
enum class FtpsTlsPolicy { MODERN, TLS12_ONLY }

data class FtpsTarget(
    val host: String,
    val port: Int = 21,
    val mode: FtpsMode = FtpsMode.EXPLICIT_TLS,
    val tlsPolicy: FtpsTlsPolicy = FtpsTlsPolicy.MODERN
) {
    init {
        require(host.isNotBlank() && host.length <= 253 &&
            host.none { it.isWhitespace() || it == '/' || it == '\\' ||
                it == '@' || it == ':' || it.code < 32 || it.code == 127 }) { "INVALID_HOST" }
        require(port in 1..65535) { "INVALID_PORT" }
    }
}
data class FtpsEntry(
    val name: String, val isDirectory: Boolean, val size: Long,
    /** Optional server-reported timestamp, for display only, never mutation proof. */
    val modifiedMillis: Long? = null
)

/** An explicitly queried MLST identity, never inferred from a LIST preview. */
data class FtpsFileObservation(
    val name: String, val size: Long, val modifiedMillis: Long
) {
    val revision: String get() = "$size:$modifiedMillis"
}

/**
 * UI-only directory preview. An FTP LIST response has implementation-specific
 * parsing and is NEVER evidence for collision-free writes or destructive plans.
 * "authoritative" is FALSE on truncated MLSD or legacy LIST. It is still
 * UI metadata rather than a complete mutation witness, which must be
 * separately observed under the immutable planner's strict preflight.
 */
data class FtpsBrowsePreview(
    val entries: List<FtpsEntry>,
    val method: String,
    val authoritative: Boolean,
    val truncated: Boolean
)

/** Bounded read-only expansion; LIST fallback remains non-authoritative. */
object FtpsBrowseBounds {
    const val INITIAL = 200
    const val STEP = 200
    const val MAX = 2000

    fun requireValid(limit: Int): Int = limit.also {
        require(it in 1..MAX) { "INVALID_FTPS_BROWSE_WINDOW" }
    }

    fun next(limit: Int): Int {
        requireValid(limit)
        require(limit >= INITIAL && limit % STEP == 0) {
            "INVALID_PROGRESSIVE_FTPS_STEP"
        }
        return (limit + STEP).coerceAtMost(MAX)
    }
}

/** Remote names are never normalized by guessing OS path semantics. */
object FtpsPath {
    fun checkedChild(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "INVALID_REMOTE_CHILD_NAME" }
        return name
    }
    fun checked(absolute: String): String {
        require(absolute.startsWith("/") && absolute.length <= 4096) { "PATH_MUST_BE_ABSOLUTE" }
        require(absolute.none { it == '\\' || it.code < 32 || it.code == 127 }) {
            "PATH_CONTROL_CHAR"
        }
        require(absolute.toByteArray(StandardCharsets.UTF_8).size <= 4096)
        if (absolute != "/") {
            require(absolute.split('/').drop(1).all { it.isNotBlank() && it != "." && it != ".." }) {
                "PATH_TRAVERSAL"
            }
        }
        return absolute
    }
}

/**
 * Sanitized protocol failure. Numeric FTP response codes contain no server
 * response body; no remote path, host, account or raw exception is retained.
 * A missing response is reported as 000.
 */
class FtpsSafeFailure internal constructor(
    val stage: String, val kind: String, val numericReply: Int
) : IOException("FTPS_${stage}_${kind}_REPLY_${numericReply.toString().padStart(3, '0')}")

internal object FtpsFailureCodes {
    fun classify(stage: String, reply: Int, error: Throwable?): FtpsSafeFailure {
        // Some FTP libraries wrap the TLS exception in an IOException.
        // Classify only exception *types*, never message/stack/hostname/causes.
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val kind = when {
            error == null -> "NO_DATA_CONNECTION_OR_COMMAND_REJECTED"
            chain.any { it is SSLHandshakeException } -> "TLS_HANDSHAKE"
            chain.any { it is SSLException } -> "TLS_DATA"
            chain.any { it is SocketTimeoutException } -> "TIMEOUT"
            chain.any { it is SocketException } -> "DATA_SOCKET"
            chain.any { it is IOException } -> "IO"
            else -> "PARSER_OR_POLICY"
        }
        val numeric = if (reply in 100..599) reply else 0
        return FtpsSafeFailure(stage, kind, numeric)
    }
}

/** Encrypted FTPS control/data adapter with explicit or implicit TLS. */
object SecureFtps {
    fun configuredClient(
        mode: FtpsMode = FtpsMode.EXPLICIT_TLS,
        tlsPolicy: FtpsTlsPolicy = FtpsTlsPolicy.MODERN
    ): FTPSClient = FTPSClient(mode == FtpsMode.IMPLICIT_TLS).apply {
        // Commons Net's default certificate manager validates dates but NOT CA
        // trust. Use the platform trust anchors (or explicit user-trusted store).
        // Hostname matching alone cannot authenticate an untrusted certificate.
        setTrustManager(TrustManagerUtils.getDefaultTrustManager(null))
        setEndpointCheckingEnabled(true) // Commons Net's default is FALSE
        setRemoteVerificationEnabled(true)
        setEnabledProtocols(
            if (tlsPolicy == FtpsTlsPolicy.TLS12_ONLY) arrayOf("TLSv1.2")
            else arrayOf("TLSv1.3", "TLSv1.2")
        )
        connectTimeout = 12_000
        defaultTimeout = 12_000
        setDataTimeout(Duration.ofSeconds(30))
        setUseEPSVwithIPv4(true)
    }

    fun connect(
        target: FtpsTarget, username: String, password: CharArray
    ): FtpsSession {
        require(username.isNotBlank() && username.length <= 255 &&
            username.none { it == '\n' || it == '\r' || it == '\u0000' }) { "INVALID_USER" }
        require(password.isNotEmpty() && password.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "INVALID_SECRET"
        }
        val client = configuredClient(target.mode, target.tlsPolicy)
        try {
            client.connect(target.host, target.port)
            client.soTimeout = 30_000
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("FTPS_WELCOME_REJECTED")
            // Explicit mode upgrades via AUTH TLS; implicit mode starts with TLS.
            // Both authenticate only after control TLS is established.
            if (!client.login(username, String(password))) throw IOException("FTPS_AUTH_REJECTED")
            client.execPBSZ(0L)
            client.execPROT("P") // mandatory encrypted data transfers
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("FTPS_BINARY_REJECTED")
            client.enterLocalPassiveMode()
            return FtpsSession(client)
        } catch (_: Exception) {
            runCatching { if (client.isConnected) client.disconnect() }
            // Do not surface raw server replies or accidentally retry plaintext.
            throw IOException("FTPS_CONNECTION_OR_POLICY_FAILED")
        }
    }
}

/**
 * MLSD represents the current/parent directory with cdir/pdir entries on some
 * implementations. They are transport metadata, never selectable user items.
 * Other malformed entries fail the entire listing rather than pretending
 * a partial directory is authoritative.
 */
internal object FtpsMlsdEntries {
    fun decode(files: Array<FTPFile>): List<FtpsEntry> {
        val entries = files.asSequence()
            .filter { it.name != "." && it.name != ".." }
            .map {
                FtpsEntry(FtpsPath.checkedChild(it.name ?: ""), it.isDirectory, it.size,
                    it.timestamp?.timeInMillis?.takeIf { millis -> millis > 0L })
            }.toList()
        // A hostile or faulty server can return two identically named rows.
        // They are not unique file identities, and keyed Compose previews
        // would otherwise crash or offer ambiguous source selection.
        require(entries.map { it.name }.toSet().size == entries.size) {
            "AMBIGUOUS_FTPS_DIRECTORY"
        }
        return entries
    }
}

/** Single-threaded control connection. Do not start a second command mid-RETR. */
class FtpsSession internal constructor(private val client: FTPSClient) : AutoCloseable {
    fun list(absoluteFolder: String): List<FtpsEntry> {
        // Keep MLSD as the only authoritative listing source until LIST fallback
        // is explicitly marked incomplete. A missing or failed MLSD reply must
        // never become an apparently empty, complete directory.
        val path = FtpsPath.checked(absoluteFolder)
        // An absolute MLSD argument is not consistently implemented across FTP
        // servers. CWD first, then ask MLSD for the *current* directory.
        // This session is single-threaded and used for read-only browsing here.
        val entered = try {
            client.changeWorkingDirectory(path)
        } catch (error: Exception) {
            throw FtpsFailureCodes.classify("CWD", client.replyCode, error)
        }
        if (!entered) throw FtpsFailureCodes.classify("CWD", client.replyCode, null)
        val files = try {
            client.mlistDir()
        } catch (error: Exception) {
            throw FtpsFailureCodes.classify("LIST", client.replyCode, error)
        } ?: throw FtpsFailureCodes.classify("LIST", client.replyCode, null)
        return try {
            FtpsMlsdEntries.decode(files)
        } catch (error: Exception) {
            throw FtpsFailureCodes.classify("LIST", client.replyCode, error)
        }
    }

    /**
     * A read-only compatibility view. Use verified MLSD if populated. On
     * empty MLSD or explicit command-unsupported replies alone, try legacy
     * LIST without treating its parsed result as authoritative. A TLS,
     * timeout, permissions or unknown data-channel failure is never retried.
     */
    fun browseReadOnly(absoluteFolder: String, maxEntries: Int = 200): FtpsBrowsePreview {
        FtpsBrowseBounds.requireValid(maxEntries)
        val result = runCatching { list(absoluteFolder) }
        val entries = result.getOrNull()
        if (entries != null && entries.isNotEmpty()) {
            return FtpsBrowsePreview(entries.take(maxEntries),
                "MLSD", authoritative = entries.size <= maxEntries,
                truncated = entries.size > maxEntries)
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            val unsupported = failure is FtpsSafeFailure &&
                failure.stage == "LIST" &&
                failure.kind == "NO_DATA_CONNECTION_OR_COMMAND_REJECTED" &&
                failure.numericReply in listOf(500, 502, 504)
            if (!unsupported) throw failure
        }
        // list() already changed to this checked directory. No command is
        // issued after TLS/socket failure or a denied CWD.
        val raw = try { client.listFiles() }
            catch (error: Exception) {
                throw FtpsFailureCodes.classify("LIST_PREVIEW", client.replyCode, error)
            } ?: throw FtpsFailureCodes.classify("LIST_PREVIEW", client.replyCode, null)
        val decoded = try { FtpsMlsdEntries.decode(raw) }
            catch (error: Exception) {
                throw FtpsFailureCodes.classify("LIST_PREVIEW", client.replyCode, error)
            }
        return FtpsBrowsePreview(decoded.take(maxEntries), "LIST",
            authoritative = false, truncated = decoded.size > maxEntries)
    }

    /** Require authoritative file metadata before a future remote SAF copy. */
    fun statFile(absoluteFile: String): FtpsFileObservation {
        val path = FtpsPath.checked(absoluteFile)
        require(path != "/") { "FTPS_FILE_PATH_REQUIRED" }
        val stat = try {
            client.mlistFile(path)
        } catch (error: Exception) {
            throw FtpsFailureCodes.classify("STAT", client.replyCode, error)
        } ?: throw FtpsFailureCodes.classify("STAT", client.replyCode, null)
        val expectedName = FtpsPath.checkedChild(path.substringAfterLast('/'))
        // MLST can return an absolute pathname or its final component.
        // A different name is not an observation of the requested file,
        // even if its type/size/time happen to match. Never manufacture a
        // source identity from our request without checking server evidence.
        val serverName = stat.name
        val modified = stat.timestamp?.timeInMillis
        if (!stat.isFile || stat.size < 0L || modified == null ||
            modified <= 0L || (serverName != expectedName && serverName != path))
            throw IOException("FTPS_FILE_STAT_UNVERIFIED")
        return FtpsFileObservation(expectedName, stat.size, modified)
    }

    fun openRead(absoluteFile: String): InputStream {
        val path = FtpsPath.checked(absoluteFile)
        val raw = try {
            client.retrieveFileStream(path)
        } catch (error: Exception) {
            throw FtpsFailureCodes.classify("READ", client.replyCode, error)
        } ?: throw FtpsFailureCodes.classify("READ", client.replyCode, null)
        // RFC 959 final completion reply is distinct from stream EOF; a failed
        // close/complete means the executor cannot advertise successful transfer.
        return object : FilterInputStream(raw) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                // Even if the stream's close implementation fails, consume
                // the final 226/250 completion reply (or its failure). A
                // third-party transport can throw unchecked exceptions with
                // private paths/credentials in their messages: never expose
                // them directly, and never record mere EOF as success.
                var failure: Exception? = null
                try { super.close() }
                catch (error: Exception) { failure = error }
                var finalError: Exception? = null
                val completed = try { client.completePendingCommand() }
                    catch (error: Exception) {
                        finalError = error
                        false
                    }
                if (failure != null || !completed)
                    throw FtpsFailureCodes.classify("READ_FINAL", client.replyCode,
                        failure ?: finalError)
            }
        }
    }

    override fun close() {
        if (!client.isConnected) return
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}
