package dev.expository.transfer.sftp

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
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
                it == '@' || it == ':' || it == '\r' || it == '\n' }) { "INVALID_HOST" }
        require(port in 1..65535) { "INVALID_PORT" }
    }
}

object SftpPath {
    fun requireAbsolute(path: String): String {
        require(path.startsWith("/") && path.toByteArray(StandardCharsets.UTF_8).size <= 4096)
        require(path.none { it == '\\' || it == '\u0000' || it == '\r' || it == '\n' })
        if (path != "/") require(path.split('/').drop(1).all {
            it.isNotEmpty() && it != "." && it != ".."
        })
        return path
    }
    fun validateChild(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it == '\u0000' ||
                it == '\r' || it == '\n' }) { "INVALID_SFTP_CHILD" }
        return name
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

class SftpSession internal constructor(
    private val ssh: SshClient,
    private val session: ClientSession,
    private val sftp: SftpClient
) : AutoCloseable {
    fun list(absoluteFolder: String): List<String> =
        sftp.readDir(SftpPath.requireAbsolute(absoluteFolder))
            .mapNotNull {
                val name = it.filename
                when (name) {
                    ".", ".." -> null
                    else -> SftpPath.validateChild(name)
                }
            }.toList()

    fun openRead(absoluteFile: String): InputStream =
        sftp.read(SftpPath.requireAbsolute(absoluteFile))

    override fun close() {
        runCatching { sftp.close() }
        runCatching { session.close() }
        runCatching { ssh.stop() }
    }
}
