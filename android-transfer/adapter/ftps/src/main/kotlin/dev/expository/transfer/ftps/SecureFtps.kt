package dev.expository.transfer.ftps

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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

/** Single-threaded control connection. Do not start a second command mid-RETR. */
class FtpsSession internal constructor(private val client: FTPSClient) : AutoCloseable {
    fun list(absoluteFolder: String): List<FtpsEntry> {
        val files = client.mlistDir(FtpsPath.checked(absoluteFolder))
            ?: throw IOException("MLSD_NOT_VERIFIED")
        return files.map {
            FtpsEntry(FtpsPath.checkedChild(it.name ?: ""), it.isDirectory, it.size)
        }
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
