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
        checkCase("bounded timeouts without relying on pre-connect TLS socket state") {
            val client = SecureFtps.configuredClient()
            run {
                check(client.connectTimeout > 0)
                check(client.dataTimeout.toMillis() > 0L)
            }
        }
        checkCase("implicit mode retains strict TLS verifier policy") {
            val target = FtpsTarget("example.invalid", 990, FtpsMode.IMPLICIT_TLS)
            check(target.port == 990 && target.mode == FtpsMode.IMPLICIT_TLS)
            val client = SecureFtps.configuredClient(target.mode)
            check(client.isEndpointCheckingEnabled && client.isRemoteVerificationEnabled)
            check(client.connectTimeout > 0 && client.dataTimeout.toMillis() > 0L)
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
        checkCase("malformed MLSD child names must fail closed") {
            check(FtpsPath.checkedChild("Episode 02.mkv") == "Episode 02.mkv")
            for (name in listOf("", "..", ".", "a/b", "a\\\\b", "x\u0000y")) {
                check(runCatching { FtpsPath.checkedChild(name) }.isFailure)
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
