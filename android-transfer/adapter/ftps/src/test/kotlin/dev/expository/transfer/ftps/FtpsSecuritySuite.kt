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
        checkCase("TLS 1.2 compatibility is explicit without disabling certificate or hostname checks") {
            val target = FtpsTarget("example.invalid", 21,
                FtpsMode.EXPLICIT_TLS, FtpsTlsPolicy.TLS12_ONLY)
            check(target.tlsPolicy == FtpsTlsPolicy.TLS12_ONLY)
            check(FtpsTarget("example.invalid").tlsPolicy == FtpsTlsPolicy.MODERN)
            for (policy in FtpsTlsPolicy.entries) {
                val client = SecureFtps.configuredClient(FtpsMode.EXPLICIT_TLS, policy)
                check(client.isEndpointCheckingEnabled)
                check(client.isRemoteVerificationEnabled)
                check(client.connectTimeout > 0 && client.dataTimeout.toMillis() > 0L)
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
        checkCase("MLST file observation is required for remote file identity") {
            val expected = org.apache.commons.net.ftp.FTPFile().apply {
                name = "file.txt"
                type = org.apache.commons.net.ftp.FTPFile.FILE_TYPE
                size = 12
                timestamp = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
            }
            val client = object : org.apache.commons.net.ftp.FTPSClient(false) {
                override fun mlistFile(pathname: String): org.apache.commons.net.ftp.FTPFile? =
                    if (pathname == "/safe/file.txt") expected else null
            }
            FtpsSession(client).use { session ->
                val stat = session.statFile("/safe/file.txt")
                check(stat.name == "file.txt" && stat.size == 12L)
                check(stat.revision.isNotBlank())
                check(runCatching { session.statFile("/safe/unknown") }.isFailure)
            }
        }
        checkCase("MLST missing timestamp or file identity fails closed") {
            val bad = org.apache.commons.net.ftp.FTPFile().apply {
                name = "file.txt"
                type = org.apache.commons.net.ftp.FTPFile.FILE_TYPE
                size = 12
            }
            val client = object : org.apache.commons.net.ftp.FTPSClient(false) {
                override fun mlistFile(pathname: String): org.apache.commons.net.ftp.FTPFile? = bad
            }
            val result = runCatching {
                FtpsSession(client).use { it.statFile("/safe/file.txt") }
            }
            check(result.isFailure && result.exceptionOrNull()?.message == "FTPS_FILE_STAT_UNVERIFIED")
        }
        checkCase("truncated MLSD preview is never authoritative") {
            fun file(name: String): org.apache.commons.net.ftp.FTPFile =
                org.apache.commons.net.ftp.FTPFile().apply {
                    this.name = name
                    this.type = org.apache.commons.net.ftp.FTPFile.FILE_TYPE
                    this.size = 10
                }
            val client = object : org.apache.commons.net.ftp.FTPSClient(false) {
                override fun changeWorkingDirectory(pathname: String): Boolean = true
                override fun mlistDir(): Array<org.apache.commons.net.ftp.FTPFile> =
                    arrayOf(file("one"), file("two"), file("three"))
            }
            FtpsSession(client).use { session ->
                val short = session.browseReadOnly("/folder", maxEntries = 2)
                check(short.method == "MLSD" && short.truncated)
                check(!short.authoritative)
                check(short.entries.map { it.name } == listOf("one", "two"))
                val whole = session.browseReadOnly("/folder", maxEntries = 3)
                check(!whole.truncated && whole.authoritative)
                check(whole.entries.size == 3)
            }
        }
        checkCase("empty MLSD permits LIST preview but never mutation witness") {
            fun file(name: String): org.apache.commons.net.ftp.FTPFile =
                org.apache.commons.net.ftp.FTPFile().apply {
                    this.name = name
                    this.type = org.apache.commons.net.ftp.FTPFile.FILE_TYPE
                    this.size = 4
                }
            var cwd = ""
            var listCalled = false
            val client = object : org.apache.commons.net.ftp.FTPSClient(false) {
                override fun changeWorkingDirectory(pathname: String): Boolean {
                    cwd = pathname
                    return true
                }
                override fun mlistDir(): Array<org.apache.commons.net.ftp.FTPFile> = emptyArray()
                override fun listFiles(): Array<org.apache.commons.net.ftp.FTPFile> {
                    listCalled = true
                    return arrayOf(file("first.txt"), file("second.txt"), file("third.txt"))
                }
            }
            FtpsSession(client).use { session ->
                val result = session.browseReadOnly("/data", 2)
                check(cwd == "/data" && listCalled)
                check(result.method == "LIST" && !result.authoritative)
                check(result.truncated && result.entries.size == 2)
            }
        }
        checkCase("TLS data error never degrades to legacy LIST") {
            var listCalled = false
            val client = object : org.apache.commons.net.ftp.FTPSClient(false) {
                override fun changeWorkingDirectory(pathname: String): Boolean = true
                override fun mlistDir(): Array<org.apache.commons.net.ftp.FTPFile> {
                    throw javax.net.ssl.SSLHandshakeException("private host and credentials")
                }
                override fun listFiles(): Array<org.apache.commons.net.ftp.FTPFile> {
                    listCalled = true
                    return emptyArray()
                }
            }
            val error = runCatching {
                FtpsSession(client).use { it.browseReadOnly("/data") }
            }.exceptionOrNull()
            check(error is FtpsSafeFailure && error.kind == "TLS_HANDSHAKE")
            check(!listCalled)
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
        checkCase("wrapped TLS read errors remain classifiable but redacted") {
            val nested = java.io.IOException("server private error",
                javax.net.ssl.SSLHandshakeException("hostname account password path"))
            val result = FtpsFailureCodes.classify("READ", 425, nested)
            check(result.kind == "TLS_HANDSHAKE" && result.numericReply == 425)
            check(result.cause == null && !result.message.orEmpty().contains("password"))
            check(result.message.orEmpty().contains("FTPS_READ_TLS_HANDSHAKE_REPLY_425"))
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
