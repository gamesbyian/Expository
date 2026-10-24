package dev.expository.transfer.ftps

/**
 * Manually dispatched, read-only interoperability test against vendor-authorized demo
 * servers or an owner-authorized server. No mutation, directory recursion or raw
 * server replies in output. PR CI never runs this task.
 */
object FtpsExternalSmokeSuite {
    private data class Profile(
        val host: String, val port: Int, val mode: FtpsMode,
        val username: String, val password: String, val root: String,
        val sample: String?
    )

    private fun profile(): Profile {
        val vars = System.getenv()
        return when (vars["EXPOSITORY_SMOKE_TARGET"]) {
            "rebex-explicit" -> Profile("test.rebex.net", 21, FtpsMode.EXPLICIT_TLS,
                "demo", "password", "/pub/example", "/pub/example/readme.txt")
            "rebex-implicit" -> Profile("test.rebex.net", 990, FtpsMode.IMPLICIT_TLS,
                "demo", "password", "/pub/example", "/pub/example/readme.txt")
            "wing-implicit" -> Profile("demo.wftpserver.com", 990, FtpsMode.IMPLICIT_TLS,
                "demo", "demo", "/", null)
            "owner-explicit", "owner-implicit" -> {
                val host = vars["EXPOSITORY_SMOKE_HOST"].orEmpty()
                val username = vars["EXPOSITORY_SMOKE_USER"].orEmpty()
                val password = vars["EXPOSITORY_SMOKE_PASSWORD"].orEmpty()
                require(host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                    "OWNER_SMOKE_CONFIGURATION_MISSING"
                }
                val mode = if (vars["EXPOSITORY_SMOKE_TARGET"] == "owner-implicit")
                    FtpsMode.IMPLICIT_TLS else FtpsMode.EXPLICIT_TLS
                val port = vars["EXPOSITORY_SMOKE_PORT"]?.toIntOrNull()
                    ?: if (mode == FtpsMode.IMPLICIT_TLS) 990 else 21
                Profile(host, port, mode, username, password, "/", null)
            }
            else -> error("EXPLICIT_EXTERNAL_SMOKE_TARGET_REQUIRED")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        // Sanitize all network failures: no host, account, file, or raw reply
        // may accidentally enter a workflow log.
        val p = profile()
        var stage = "CONNECT"
        try {
            SecureFtps.connect(FtpsTarget(p.host, p.port, p.mode),
                p.username, p.password.toCharArray()).use { session ->
                stage = "LIST"
                val preview = session.browseReadOnly(FtpsPath.checked(p.root))
                val entries = preview.entries
                check(entries.isNotEmpty()) { "EMPTY_LISTING" }
                println("PASS FTPS external authenticated protected browse method=" +
                    preview.method + " authoritative=" + preview.authoritative +
                    " count=" + entries.size + " truncated=" + preview.truncated)
                // A legacy LIST preview can show file names to a human, but
                // is not proof of a complete conflict-free destination.
                val sample = p.sample
                if (sample != null) {
                    check(entries.any { it.name == sample.substringAfterLast('/') && !it.isDirectory }) {
                        "SAMPLE_ABSENT"
                    }
                    stage = "READ"
                    var received = 0
                    session.openRead(FtpsPath.checked(sample)).use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (n == 0) continue
                            received += n
                            check(received <= 65_536) { "SAMPLE_SIZE_BOUND_EXCEEDED" }
                        }
                    }
                    check(received > 0) { "EMPTY_SAMPLE" }
                    println("PASS FTPS external bounded RETR + final completion bytes=" + received)
                } else {
                    println("INFO FTPS external listing only; no arbitrary private file reads")
                }
            }
        } catch (failure: Exception) {
            // Explicitly surface only our safe classification. Never propagate
            // an original IOException: network messages may contain private data.
            val label = when {
                failure is FtpsSafeFailure -> failure.message
                failure is IllegalStateException && failure.message == "EMPTY_LISTING" ->
                    "FTPS_EXTERNAL_LIST_EMPTY_REDACTED"
                failure is IllegalStateException && failure.message == "SAMPLE_ABSENT" ->
                    "FTPS_EXTERNAL_READ_SAMPLE_ABSENT_REDACTED"
                else -> "FTPS_EXTERNAL_" + stage + "_FAILED_REDACTED"
            }
            throw IllegalStateException(label)
        }
    }
}
