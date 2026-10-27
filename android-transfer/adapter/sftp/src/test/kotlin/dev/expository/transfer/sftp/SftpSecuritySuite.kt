package dev.expository.transfer.sftp

import java.security.KeyPairGenerator

object SftpSecuritySuite {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkCase(id: String, test: () -> Unit) {
            test(); passed++; println("PASS SFTP " + id)
        }
        // Synthetic keys generated in memory; never production host keys.
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val key1 = keys.generateKeyPair().public
        val key2 = keys.generateKeyPair().public
        val pin = SftpHostKeyPin(SftpHostKeyPin.observed(key1))

        checkCase("explicit SHA256 trusted fingerprint") {
            check(pin.fingerprint.startsWith("SHA256:"))
            check(pin.verifies(key1))
        }
        checkCase("changed host key must reject") {
            check(!pin.verifies(key2))
        }
        checkCase("missing/weak pin must reject") {
            for (v in listOf("", "MD5:00:11", "SHA1:abc", "SHA256:", "AcceptAll")) {
                check(runCatching { SftpHostKeyPin(v) }.isFailure)
            }
        }
        checkCase("configured verifier never accepts unknown key") {
            val client = SecureSftp.configuredClient(pin)
            try {
                val verifier = client.serverKeyVerifier
                check(verifier.verifyServerKey(null, null, key1))
                check(!verifier.verifyServerKey(null, null, key2))
            } finally { client.stop() }
        }
        checkCase("absolute paths and children are conservatively validated") {
            check(SftpPath.requireAbsolute("/Downloads/Season 01") == "/Downloads/Season 01")
            for (v in listOf("relative", "/../bad", "/safe/./bad", "/safe//bad",
                "/safe\nCOMMAND")) check(runCatching { SftpPath.requireAbsolute(v) }.isFailure)
            for (v in listOf("", ".", "..", "a/b", "foo\rbar"))
                check(runCatching { SftpPath.validateChild(v) }.isFailure)
        }
        checkCase("bounded incremental listing windows keep narrow compatibility") {
            check(SftpBrowseBounds.requireValid(64) == 64) // manual smoke compatibility
            check(SftpBrowseBounds.next(200) == 400)
            check(SftpBrowseBounds.next(1800) == 2000)
            check(SftpBrowseBounds.next(2000) == 2000)
            for (invalid in listOf(0, -1, 2001, Int.MAX_VALUE))
                check(runCatching { SftpBrowseBounds.requireValid(invalid) }.isFailure)
            for (invalid in listOf(64, 201, 1999))
                check(runCatching { SftpBrowseBounds.next(invalid) }.isFailure)
        }
        checkCase("invalid target blocked before network") {
            check(runCatching { SftpTarget("user@example.invalid", trustedHostKey = pin) }.isFailure)
            check(runCatching { SftpTarget("example.invalid", 65536, pin) }.isFailure)
        }
        println("PASS SFTP total=" + passed + " evidence=synthetic-host-key-only")
    }
}
