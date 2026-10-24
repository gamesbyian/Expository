package dev.expository.transfer.sftp

/**
 * Read-only optional external SFTP interoperability, never PR CI. A trusted
 * SHA-256 fingerprint must be supplied out of band before authentication.
 * Never learn or accept the key from the same untrusted connection.
 */
object SftpExternalSmokeSuite {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        val env = System.getenv()
        val profile = env["EXPOSITORY_SMOKE_TARGET"]
        val host: String
        val port: Int
        val user: String
        val password: String
        when (profile) {
            "rebex-sftp" -> {
                host = "test.rebex.net"; port = 22
                user = "demo"; password = "password"
            }
            "wing-sftp" -> {
                host = "demo.wftpserver.com"; port = 2222
                user = "demo"; password = "demo"
            }
            "owner-sftp" -> {
                host = env["EXPOSITORY_SMOKE_HOST"].orEmpty()
                port = env["EXPOSITORY_SMOKE_PORT"]?.toIntOrNull() ?: 22
                user = env["EXPOSITORY_SMOKE_USER"].orEmpty()
                password = env["EXPOSITORY_SMOKE_PASSWORD"].orEmpty()
                require(host.isNotBlank() && user.isNotBlank() && password.isNotBlank()) {
                    "OWNER_SMOKE_CONFIGURATION_MISSING"
                }
            }
            else -> error("EXPLICIT_EXTERNAL_SMOKE_TARGET_REQUIRED")
        }
        val trusted = env["EXPOSITORY_SMOKE_SFTP_PIN"].orEmpty()
        require(trusted.isNotBlank()) { "INDEPENDENTLY_VERIFIED_HOST_KEY_REQUIRED" }
        val pin = SftpHostKeyPin(trusted)
        try {
            SecureSftp.connect(SftpTarget(host, port, pin), user, password.toCharArray())
                .use { session ->
                    val page = session.browse("/", maxEntries = 64)
                    check(page.entries.isNotEmpty()) { "EMPTY_LISTING" }
                    println("PASS SFTP external pinned SSH login + bounded browse entries=" +
                        page.entries.size + " truncated=" + page.truncated)
                    if (profile == "rebex-sftp") {
                        val file = page.entries.singleOrNull { it.name == "readme.txt" && !it.directory }
                        if (file != null) {
                            check(file.size in 1..65_536) { "SAMPLE_SIZE_BOUND_EXCEEDED" }
                            var received = 0L
                            session.openRead("/readme.txt").use { input ->
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    if (n == 0) continue
                                    received += n
                                    check(received <= 65_536L) { "READ_BOUND_EXCEEDED" }
                                }
                            }
                            check(received == file.size) { "SAMPLE_SIZE_MISMATCH" }
                            println("PASS SFTP external bounded read bytes=" + received)
                        } else {
                            println("INFO SFTP external sample not in first browse page; listing only")
                        }
                    }
                }
        } catch (_: Exception) {
            throw IllegalStateException("SFTP_EXTERNAL_SMOKE_FAILED_NO_RAW_SERVER_LOG")
        }
    }
}
