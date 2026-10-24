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
        checkCase("MLSD cdir and pdir are not files or fatal parsing errors") {
            fun entry(name: String, type: Int): org.apache.commons.net.ftp.FTPFile =
                org.apache.commons.net.ftp.FTPFile().apply {
                    this.name = name
                    this.type = type
                    this.size = 42
                }
            val files = arrayOf(
                entry(".", org.apache.commons.net.ftp.FTPFile.DIRECTORY_TYPE),
                entry("..", org.apache.commons.net.ftp.FTPFile.DIRECTORY_TYPE),
                entry("safe.txt", org.apache.commons.net.ftp.FTPFile.FILE_TYPE),
                entry("Album", org.apache.commons.net.ftp.FTPFile.DIRECTORY_TYPE)
            )
            val decoded = FtpsMlsdEntries.decode(files)
            check(decoded.map { it.name } == listOf("safe.txt", "Album"))
            check(!decoded[0].isDirectory && decoded[1].isDirectory)
        }
        checkCase("malformed MLSD children cannot create a partial authoritative list") {
            fun entry(name: String): org.apache.commons.net.ftp.FTPFile =
                org.apache.commons.net.ftp.FTPFile().apply { this.name = name }
            for (invalid in listOf("a/b", "..\\bad", "name\\rmore")) {
                check(runCatching {
                    FtpsMlsdEntries.decode(arrayOf(entry("valid"), entry(invalid)))
                }.isFailure)
            }
        }
        checkCase("list failure taxonomy never leaks raw exception, path or credentials") {
            val details = "host=private.invalid user=private secret=not-for-logs /sensitive/video.mkv"
            val types = listOf(
                Triple(null, 500, "NO_DATA_CONNECTION_OR_COMMAND_REJECTED"),
                Triple(javax.net.ssl.SSLHandshakeException(details), 425, "TLS_HANDSHAKE"),
                Triple(javax.net.ssl.SSLException(details), 522, "TLS_DATA"),
                Triple(java.net.SocketTimeoutException(details), 0, "TIMEOUT"),
                Triple(java.net.SocketException(details), 426, "DATA_SOCKET"),
                Triple(java.io.IOException(details), 550, "IO"),
                Triple(IllegalArgumentException(details), 215, "PARSER_OR_POLICY")
            )
            for ((failure, response, expected) in types) {
                val result = FtpsFailureCodes.classify("LIST", response, failure)
                check(result.kind == expected)
                check(result.numericReply == response.coerceIn(0, 599))
                check(result.cause == null)
                check(!result.message.orEmpty().contains("private"))
                check(!result.message.orEmpty().contains("/sensitive"))
                check(!result.message.orEmpty().contains("secret"))
                check(result.message.orEmpty().contains("FTPS_LIST_"))
            }
            check(FtpsFailureCodes.classify("LIST", 999, null).numericReply == 0)
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
