package dev.expository.transfer.ftps

object FtpsSecuritySuite {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkCase(id: String, test: () -> Unit) { test(); println("PASS FTPS " + id); passed++ }
        checkCase("default endpoint verification must be ON") {
            val client = SecureFtps.configuredClient()
            run {
                check(client.isEndpointCheckingEnabled)
                check(client.isRemoteVerificationEnabled)
            }
        }
        checkCase("TLS 1.2/1.3, no clear or SSLv3 fallback") {
            val client = SecureFtps.configuredClient()
            run {
                check(client.enabledProtocols?.toList() == listOf("TLSv1.3", "TLSv1.2"))
                check(client.connectTimeout > 0)
                check(client.dataTimeout.toMillis() > 0L)
            }
        }
        checkCase("valid but inert endpoint") {
            check(FtpsTarget("example.invalid", 21).port == 21)
        }
        checkCase("host and port injection rejected before sockets") {
            for (h in listOf("", "host\nPASS", "user@server", "host/path", "host:21")) {
                check(runCatching { FtpsTarget(h) }.isFailure)
            }
            check(runCatching { FtpsTarget("valid.invalid", 65536) }.isFailure)
        }
        checkCase("absolute path no ambiguous traversals") {
            check(FtpsPath.checked("/Downloads/Season 01") == "/Downloads/Season 01")
            for (p in listOf("relative", "/safe/../oops", "/safe/./oops", "/safe//oops",
                "/safe\nNOOP", "/safe\\oops")) {
                check(runCatching { FtpsPath.checked(p) }.isFailure)
            }
        }
        checkCase("invalid identity rejected without network") {
            check(runCatching {
                SecureFtps.connect(FtpsTarget("example.invalid"), "\r", charArrayOf('x'))
            }.isFailure)
            check(runCatching {
                SecureFtps.connect(FtpsTarget("example.invalid"), "tester", charArrayOf())
            }.isFailure)
        }
        println("PASS FTPS total=" + passed + " evidence=offline-policy-only")
    }
}
