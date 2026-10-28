package dev.expository.transfer.ftps

/**
 * Deliberately disposable E3 fixture: real localhost FTP control/data
 * connections, dummy account, browse only. This test must never target a
 * real service or be passed an externally supplied hostname.
 */
object PlainFtpLocalhostSuite {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "FIXTURE_PORT_REQUIRED" }
        val port = args[0].toInt()
        require(port in 1..65535)
        val target = PlainFtpTarget("127.0.0.1", port)
        val password = "synthetic-only-secret".toCharArray()
        try {
            require(runCatching {
                PlainFtp.connect(target, "synthetic-user", password,
                    explicitlyApproved = false)
            }.isFailure) { "CONNECT_WITHOUT_CONSENT" }

            PlainFtp.connect(target, "synthetic-user", password,
                explicitlyApproved = true).use { session ->
                val names = session.browseReadOnly("/").entries.map { it.name }.toSet()
                require("synthetic.txt" in names && "folder" in names) {
                    "MISSING_SYNTHETIC_DIRECTORY_ENTRY"
                }
                val page = session.browseReadOnly("/", 1)
                require(page.truncated && !page.authoritative && page.entries.size == 1) {
                    "BROWSE_PREVIEW_MUST_REMAIN_NONAUTHORITATIVE"
                }
                val nested = session.browseReadOnly("/folder")
                require(nested.entries.map { it.name } == listOf("nested.txt")) {
                    "NESTED_BROWSE_MISMATCH"
                }
                require(runCatching { session.browseReadOnly("/missing") }.isFailure) {
                    "MISSING_FOLDER_MUST_FAIL"
                }
            }
            println("PASS E3 plaintext FTP: explicit opt-in, real localhost control/data browse, bounded preview, nested paths and safe rejection")
        } finally {
            password.fill('\u0000')
        }
    }
}
