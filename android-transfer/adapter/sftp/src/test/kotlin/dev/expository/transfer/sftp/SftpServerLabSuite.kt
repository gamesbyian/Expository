package dev.expository.transfer.sftp

import org.apache.sshd.server.SshServer
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Ephemeral loopback-only OpenSSH-compatible SFTP server, synthetic fixture.
 * No external services, production keys, passwords, files or user traffic.
 */
object SftpServerLabSuite {
    @JvmStatic fun main(args: Array<String>) {
        val tmp = Files.createTempDirectory("expository-sftp-fixture-")
        val payload = "Synthetic SFTP fixture\n"
        Files.writeString(tmp.resolve("synthetic.txt"), payload, StandardCharsets.UTF_8)

        // Fully generated in-memory host key. Avoid any on-disk persisted host
        // identity or silently trusting first-use keys in client code.
        val sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        val hostKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        sshd.keyPairProvider = KeyPairProvider.wrap(hostKey)
        sshd.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator {
            user, password, _ -> user == "synthetic" && password == "synthetic-password"
        }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        sshd.fileSystemFactory = VirtualFileSystemFactory(tmp)
        try {
            sshd.start()
            val port = sshd.port
            val actualHostKey = hostKey.public
            val correct = SftpHostKeyPin(SftpHostKeyPin.observed(actualHostKey))
            val wrong = SftpHostKeyPin(SftpHostKeyPin.observed(
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
            ))
            val trusted = SecureSftp.connect(SftpTarget("127.0.0.1", port, correct),
                "synthetic", "synthetic-password".toCharArray())
            trusted.use { session ->
                check("synthetic.txt" in session.list("/"))
                val browse = session.browse("/")
                check(!browse.truncated)
                check(browse.entries.any { it.name == "synthetic.txt" && !it.directory })
                val observed = session.statFile("/synthetic.txt")
                check(observed.size == payload.toByteArray(StandardCharsets.UTF_8).size.toLong())
                check(runCatching { session.statFile("/") }.isFailure)
                val received = session.openRead("/synthetic.txt").use { it.readBytes() }
                check(String(received, StandardCharsets.UTF_8) == payload)
                val uploadPayload = ("Synthetic upload bytes" + 10.toChar()).toByteArray(StandardCharsets.UTF_8)
                session.openExclusiveWrite("/synthetic-upload.txt").use {
                    it.write(uploadPayload)
                }
                check(session.statFile("/synthetic-upload.txt").size == uploadPayload.size.toLong())
                val readBack = session.openRead("/synthetic-upload.txt").use { it.readBytes() }
                check(readBack.contentEquals(uploadPayload))
                check(runCatching {
                    session.openExclusiveWrite("/synthetic.txt").use { it.write(byteArrayOf(0)) }
                }.isFailure) { "Exclusive write must never overwrite existing file" }
                check(session.openRead("/synthetic.txt").use { it.readBytes() }
                    .contentEquals(payload.toByteArray(StandardCharsets.UTF_8)))
                println("PASS SFTP LAB exclusive new upload/read-back and existing-file refusal")
            }
            println("PASS SFTP LAB trusted host fingerprint + authenticated directory read")
            val refused = runCatching {
                SecureSftp.connect(SftpTarget("127.0.0.1", port, wrong),
                    "synthetic", "synthetic-password".toCharArray()).close()
            }
            check(refused.isFailure) { "Unrecognized SFTP host key must be rejected" }
            println("PASS SFTP LAB wrong host fingerprint rejected during handshake")
        } finally {
            runCatching { sshd.stop(true) }
            runCatching { Files.deleteIfExists(tmp.resolve("synthetic.txt")) }
            runCatching { Files.deleteIfExists(tmp.resolve("synthetic-upload.txt")) }
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
}
