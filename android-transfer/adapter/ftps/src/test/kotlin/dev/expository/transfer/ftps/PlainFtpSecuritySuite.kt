package dev.expository.transfer.ftps

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile

/** Offline-only: no server, credentials, or network needed. */
object PlainFtpSecuritySuite {
    private fun row(name: String): FTPFile = FTPFile().apply {
        this.name = name
        type = FTPFile.FILE_TYPE
        size = 42L
    }

    @JvmStatic fun main(args: Array<String>) {
        check(PlainFtpTarget("example.invalid", 21).port == 21)
        for (bad in listOf("", "user@host", "host:21", "host/dir", "host\nINJECT")) {
            check(runCatching { PlainFtpTarget(bad) }.isFailure)
        }
        check(runCatching { PlainFtpTarget("example.invalid", 0) }.isFailure)
        // Consent, username and secret are rejected without even opening a socket.
        check(runCatching {
            PlainFtp.connect(PlainFtpTarget("example.invalid"), "user",
                charArrayOf('p'), explicitlyApproved = false)
        }.exceptionOrNull()?.message == "PLAINTEXT_FTP_CONSENT_REQUIRED")
        val client = object : FTPClient() {
            override fun changeWorkingDirectory(pathname: String): Boolean = pathname == "/safe"
            override fun mlistDir(): Array<FTPFile> = arrayOf(row("one"), row("two"), row("three"))
        }
        PlainFtpSession(client).use { session ->
            val first = session.browseReadOnly("/safe", 2)
            check(first.method == "MLSD" && first.truncated && !first.authoritative)
            check(first.entries.map { it.name } == listOf("one", "two"))
            val whole = session.browseReadOnly("/safe", 3)
            check(whole.entries.size == 3 && !whole.authoritative)
            check(runCatching { session.browseReadOnly("../unsafe") }.isFailure)
            check(runCatching { session.browseReadOnly("/no-such-dir") }.isFailure)
        }
        val dup = object : FTPClient() {
            override fun changeWorkingDirectory(pathname: String): Boolean = true
            override fun mlistDir(): Array<FTPFile> = arrayOf(row("dup"), row("dup"))
        }
        PlainFtpSession(dup).use {
            check(runCatching { it.browseReadOnly("/") }.isFailure)
        }
        println("PASS plaintext FTP: explicit consent, host/path validation, bounded non-authoritative preview, ambiguous-name refusal")
    }
}
