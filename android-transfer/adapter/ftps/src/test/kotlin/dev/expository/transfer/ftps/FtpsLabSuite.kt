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
            "trusted-direct-read" -> verifyTrustedDirectFileAfterListingsRefused()
            else -> error("Expected reject-untrusted, trusted-read or trusted-direct-read")
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

    /**
     * An authenticated server can refuse both MLSD and LIST (e.g. 502)
     * while permitting a separately observed MLST and protected RETR.
     * This opt-in lab requires full TLS/hostname verification and the same
     * encrypted, read-only fixture as the normal directory route.
     */
    private fun verifyTrustedDirectFileAfterListingsRefused() {
        check(System.getProperty("javax.net.ssl.trustStore")?.isNotBlank() == true) {
            "Explicit synthetic truststore required"
        }
        SecureFtps.connect(
            FtpsTarget("localhost", 2122),
            "synthetic",
            "synthetic-secret".toCharArray()
        ).use { session ->
            val listing = runCatching { session.browseReadOnly("/") }
            listing.onSuccess { preview ->
                // Commons Net may represent a 502 LIST command as an empty
                // read-only preview rather than throwing. Either outcome
                // must remain non-authoritative and must not authorize Copy.
                check(preview.method == "LIST" &&
                    !preview.authoritative && preview.entries.isEmpty()) {
                    "Refused listings must not become authoritative empty directories"
                }
            }.onFailure { refusal ->
                check(FtpsDegradedListingPolicy.permitsManualMlst(refusal)) {
                    "Only narrow listing-command refusals may retain manual MLST"
                }
            }
            val remote = session.statFile("/synthetic.txt")
            val expected = "FTPS synthetic fixture\n".toByteArray(Charsets.UTF_8)
            check(remote.name == "synthetic.txt" &&
                remote.size == expected.size.toLong() && remote.revision.isNotBlank()) {
                "Separately authenticated MLST must bind exact remote identity"
            }
            val bytes = session.openRead("/synthetic.txt").use { it.readBytes() }
            check(bytes.contentEquals(expected)) {
                "Protected direct RETR or final command completion did not verify"
            }
            check(session.statFile("/synthetic.txt").revision == remote.revision) {
                "Direct-file source changed during controlled encrypted read"
            }
        }
        println("PASS FTPS lab rejected MLSD/LIST, authoritative MLST and encrypted RETR")
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
                observation.size == "FTPS synthetic fixture\n".toByteArray(Charsets.UTF_8).size.toLong())
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
