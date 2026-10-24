package dev.expository.transfer.ftps

import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * Runs only against CI's disposable loopback explicit FTPS server.
 * Separate Gradle invocations ensure trusted and untrusted JVM TLS states
 * cannot contaminate each other's verification.
 */
object FtpsLabSuite {
    @JvmStatic fun main(args: Array<String>) {
        when (args.singleOrNull()) {
            "reject-untrusted" -> rejectUntrustedCertificate()
            "trusted-read" -> verifyTrustedEncryptedRead()
            else -> error("Expected reject-untrusted or trusted-read")
        }
    }

    private fun rejectUntrustedCertificate() {
        val client = SecureFtps.configuredClient()
        try {
            // Exercise the underlying TLS handshake directly: a generic connection
            // failure would not prove that an untrusted certificate was rejected.
            val failure = runCatching { client.connect("localhost", 2121) }.exceptionOrNull()
            check(failure != null) { "Untrusted certificate unexpectedly accepted" }
            check(generateSequence(failure) { it.cause }.any { it is SSLHandshakeException }) {
                "Expected TLS handshake rejection, got " + failure::class.java.simpleName
            }
            println("PASS FTPS lab untrusted certificate rejected by TLS handshake")
        } finally {
            if (client.isConnected) client.disconnect()
        }
    }

    private fun verifyTrustedEncryptedRead() {
        // The trusted-run Gradle task loads a truststore containing ONLY the
        // ephemeral lab certificate. Production default trust stays untouched.
        check(System.getProperty("javax.net.ssl.trustStore")?.isNotBlank() == true) {
            "Explicit test truststore required for trusted-read"
        }
        SecureFtps.connect(
            FtpsTarget("localhost", 2121),
            "synthetic",
            charArrayOf('s','y','n','t','h','e','t','i','c','-','s','e','c','r','e','t')
        ).use { session ->
            val entries = session.list("/")
            check(entries.any { it.name == "synthetic.txt" && !it.isDirectory }) {
                "Encrypted MLSD listing did not include expected synthetic entry"
            }
            val observation = session.statFile("/synthetic.txt")
            check(observation.name == "synthetic.txt" &&
                observation.size == "FTPS synthetic fixture\\n".toByteArray(Charsets.UTF_8).size.toLong())
            val contents = session.openRead("/synthetic.txt").use { input ->
                input.readBytes()
            }
            check(session.statFile("/synthetic.txt").revision == observation.revision) {
                "Remote file metadata changed during controlled read"
            }
            check(contents.contentEquals("FTPS synthetic fixture\n".toByteArray(Charsets.UTF_8))) {
                "Authenticated RETR or final 226 completion failed content verification"
            }
        }
        println("PASS FTPS lab explicitly trusted certificate, MLSD and protected RETR")
    }
}
