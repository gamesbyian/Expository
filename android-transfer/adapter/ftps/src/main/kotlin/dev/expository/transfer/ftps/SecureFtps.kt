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

data class FtpsTarget(
    val host: String,
    val port: Int = 21,
    val mode: FtpsMode = FtpsMode.EXPLICIT_TLS
) {
    init {
        require(host.isNotBlank() && host.length <= 253 &&
            host.none { it.isWhitespace() || it == '/' || it == '\\' ||
                it == '@' || it == '\u0000' || it == ':' }) { "INVALID_HOST" }
        require(port in 1..65535) { "INVALID_PORT" }
    }
}
data class FtpsEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * UI-only directory preview. An FTP LIST response has implementation-specific
 * parsing and is NEVER evidence for collision-free writes or destructive plans.
 * Complete here means only within a bounded UI page, not a mutation witness.
 */
data class FtpsBrowsePreview(
    val entries: List<FtpsEntry>,
    val method: String,
    val authoritative: Boolean,
    val truncated: Boolean
)

/** Remote names are never normalized by guessing OS path semantics. */
object FtpsPath {
    fun checkedChild(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it == '\u0000' ||
                it == '\r' || it == '\n' }) { "INVALID_REMOTE_CHILD_NAME" }
        return name
    }
    fun checked(absolute: String): String {
        require(absolute.startsWith("/") && absolute.length <= 4096) { "PATH_MUST_BE_ABSOLUTE" }
        require(absolute.none { it == '\\' || it == '\u0000' || it == '\r' || it == '\n' }) {
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
        val kind = when (error) {
            null -> "NO_DATA_CONNECTION_OR_COMMAND_REJECTED"
            is SSLHandshakeException -> "TLS_HANDSHAKE"
            is SSLException -> "TLS_DATA"
            is SocketTimeoutException -> "TIMEOUT"
            is SocketException -> "DATA_SOCKET"
            is IOException -> "IO"
            else -> "PARSER_OR_POLICY"
        }
        val numeric = if (reply in 100..599) reply else 0
        return FtpsSafeFailure(stage, kind, numeric)
    }
}

/** Encrypted FTPS control/data adapter with explicit or implicit TLS. */
object SecureFtps {
    fun configuredClient(mode: FtpsMode = FtpsMode.EXPLICIT_TLS): FTPSClient =
        FTPSClient(mode == FtpsMode.IMPLICIT_TLS).apply {
        // Commons Net's default certificate manager validates dates but NOT CA
        // trust. Use the platform trust anchors (or explicit user-trusted store).
        // Hostname matching alone cannot authenticate an untrusted certificate.
        setTrustManager(TrustManagerUtils.getDefaultTrustManager(null))
        setEndpointCheckingEnabled(true) // Commons Net's default is FALSE
        setRemoteVerificationEnabled(true)
        setEnabledProtocols(arrayOf("TLSv1.3", "TLSv1.2"))
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
        val client = configuredClient(target.mode)
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
    fun decode(files: Array<FTPFile>): List<FtpsEntry> =
        files.asSequence()
            .filter { it.name != "." && it.name != ".." }
            .map {
                FtpsEntry(FtpsPath.checkedChild(it.name ?: ""), it.isDirectory, it.size)
            }.toList()
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
        require(maxEntries in 1..200)
        val result = runCatching { list(absoluteFolder) }
        val entries = result.getOrNull()
        if (entries != null && entries.isNotEmpty()) {
            return FtpsBrowsePreview(entries.take(maxEntries),
                "MLSD", authoritative = true, truncated = entries.size > maxEntries)
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

    fun openRead(absoluteFile: String): InputStream {
        val raw = client.retrieveFileStream(FtpsPath.checked(absoluteFile))
            ?: throw IOException("RETR_NOT_ACCEPTED")
        // RFC 959 final completion reply is distinct from stream EOF; a failed
        // close/complete means the executor cannot advertise successful transfer.
        return object : FilterInputStream(raw) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                var failure: IOException? = null
                try { super.close() }
                catch (e: IOException) { failure = e }
                val completed = try { client.completePendingCommand() }
                    catch (_: IOException) { false }
                if (failure != null || !completed) throw IOException("FTPS_READ_COMPLETION_UNCERTAIN")
            }
        }
    }

    override fun close() {
        if (!client.isConnected) return
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}
