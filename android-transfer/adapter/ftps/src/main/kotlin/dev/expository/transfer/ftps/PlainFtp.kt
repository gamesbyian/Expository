package dev.expository.transfer.ftps

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.time.Duration

/**
 * Deliberately insecure, read-only FTP adapter. Credentials and directory
 * metadata cross the network WITHOUT encryption. There is no automatic
 * fallback from SFTP/FTPS, no implicit consent and no FTP writer API.
 */
data class PlainFtpTarget(val host: String, val port: Int = 21) {
    init {
        // Reuse the strict shared host/port checks. This does not enable TLS.
        FtpsTarget(host, port)
    }
}

object PlainFtp {
    fun configuredClient(): FTPClient = FTPClient().apply {
        connectTimeout = 12_000
        defaultTimeout = 12_000
        setDataTimeout(Duration.ofSeconds(30))
        setUseEPSVwithIPv4(true)
    }

    fun connect(
        target: PlainFtpTarget,
        username: String,
        password: CharArray,
        explicitlyApproved: Boolean
    ): PlainFtpSession {
        require(explicitlyApproved) { "PLAINTEXT_FTP_CONSENT_REQUIRED" }
        require(username.isNotBlank() && username.length <= 255 &&
            username.none { it.code < 32 || it.code == 127 }) { "INVALID_USER" }
        require(password.isNotEmpty() &&
            password.none { it.code < 32 || it.code == 127 }) { "INVALID_SECRET" }
        val client = configuredClient()
        try {
            client.connect(target.host, target.port)
            client.soTimeout = 30_000
            if (!FTPReply.isPositiveCompletion(client.replyCode))
                throw IOException("FTP_WELCOME_REJECTED")
            if (!client.login(username, String(password)))
                throw IOException("FTP_AUTH_REJECTED")
            if (!client.setFileType(FTP.BINARY_FILE_TYPE))
                throw IOException("FTP_BINARY_REJECTED")
            client.enterLocalPassiveMode()
            return PlainFtpSession(client)
        } catch (_: Exception) {
            runCatching { if (client.isConnected) client.disconnect() }
            // Never include server replies, credentials, or remote host in UI.
            throw IOException("PLAINTEXT_FTP_CONNECT_FAILED")
        }
    }
}

class PlainFtpSession internal constructor(private val client: FTPClient) : AutoCloseable {
    /**
     * Browsing only. Even a complete MLSD response must NEVER authorize
     * mutation or imply a secure server. LIST is purely a display preview.
     */
    fun browseReadOnly(path: String, maxEntries: Int = FtpsBrowseBounds.INITIAL): FtpsBrowsePreview {
        FtpsBrowseBounds.requireValid(maxEntries)
        val safePath = FtpsPath.checked(path)
        try {
            if (!client.changeWorkingDirectory(safePath))
                throw IOException("FTP_DIRECTORY_UNAVAILABLE")
            val mlsd = client.mlistDir()
            val useMlsd = mlsd != null && mlsd.isNotEmpty()
            val raw = if (useMlsd) mlsd else client.listFiles()
            if (raw == null) throw IOException("FTP_LIST_UNAVAILABLE")
            val decoded = FtpsMlsdEntries.decode(raw)
            return FtpsBrowsePreview(
                decoded.take(maxEntries),
                if (useMlsd) "MLSD" else "LIST",
                authoritative = false,
                truncated = decoded.size > maxEntries
            )
        } catch (_: Exception) {
            throw IOException("PLAINTEXT_FTP_BROWSE_FAILED")
        }
    }

    override fun close() {
        if (!client.isConnected) return
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}
