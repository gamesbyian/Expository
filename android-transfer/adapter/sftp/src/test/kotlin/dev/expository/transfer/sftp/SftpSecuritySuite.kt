package dev.expository.transfer.sftp

import java.security.KeyPairGenerator

object SftpSecuritySuite {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkCase(id: String, test: () -> Unit) {
            test(); passed++; println("PASS SFTP " + id)
        }
        // Synthetic keys generated in memory; never production host keys.
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val key1 = keys.generateKeyPair().public
        val key2 = keys.generateKeyPair().public
        val pin = SftpHostKeyPin(SftpHostKeyPin.observed(key1))

        checkCase("explicit SHA256 trusted fingerprint") {
            check(pin.fingerprint.startsWith("SHA256:"))
            check(pin.verifies(key1))
        }
        checkCase("changed host key must reject") {
            check(!pin.verifies(key2))
        }
        checkCase("missing/weak pin must reject") {
            for (v in listOf("", "MD5:00:11", "SHA1:abc", "SHA256:", "AcceptAll")) {
                check(runCatching { SftpHostKeyPin(v) }.isFailure)
            }
        }
        checkCase("configured verifier never accepts unknown key") {
            val client = SecureSftp.configuredClient(pin)
            try {
                val verifier = client.serverKeyVerifier
                check(verifier.verifyServerKey(null, null, key1))
                check(!verifier.verifyServerKey(null, null, key2))
            } finally { client.stop() }
        }
        checkCase("absolute paths and children are conservatively validated") {
            check(SftpPath.requireAbsolute("/Downloads/Season 01") == "/Downloads/Season 01")
            check(SftpPath.validateChild("Café (2026).mkv") == "Café (2026).mkv")
            for (v in listOf("relative", "/../bad", "/safe/./bad", "/safe//bad",
                "/safe\nCOMMAND", "/hidden\tname", "/control\u001bname", "/delete\u007fname")) check(runCatching { SftpPath.requireAbsolute(v) }.isFailure)
            for (v in listOf("", ".", "..", "a/b", "foo\rbar", "foo\tbar", "a\u0001b", "a\u007fb"))
                check(runCatching { SftpPath.validateChild(v) }.isFailure)
        }
        checkCase("bounded incremental listing windows keep narrow compatibility") {
            check(SftpBrowseBounds.requireValid(64) == 64) // manual smoke compatibility
            check(SftpBrowseBounds.next(200) == 400)
            check(SftpBrowseBounds.next(1800) == 2000)
            check(SftpBrowseBounds.next(2000) == 2000)
            for (invalid in listOf(0, -1, 2001, Int.MAX_VALUE))
                check(runCatching { SftpBrowseBounds.requireValid(invalid) }.isFailure)
            for (invalid in listOf(64, 201, 1999))
                check(runCatching { SftpBrowseBounds.next(invalid) }.isFailure)
        }
        checkCase("complete mutation witness accepts 205 but refuses partial and overlimit") {
            val complete205 = SftpBrowsePage(
                (1..205).map { SftpRemoteEntry("synthetic-" + it, false, 1, null) },
                truncated = false)
            check(SftpMutationListing.requireComplete(complete205) === complete205)
            check(runCatching { SftpMutationListing.requireComplete(
                complete205.copy(truncated = true)
            ) }.isFailure)
            val exactly2000 = SftpBrowsePage(
                (1..SftpBrowseBounds.MAX).map {
                    SftpRemoteEntry("synthetic-" + it, false, 1, null)
                }, truncated = false)
            check(SftpMutationListing.requireComplete(exactly2000) === exactly2000)
            check(runCatching { SftpMutationListing.requireComplete(
                exactly2000.copy(truncated = true)
            ) }.isFailure)
            check(runCatching { SftpMutationListing.requireComplete(
                exactly2000.copy(entries = exactly2000.entries +
                    SftpRemoteEntry("extra", false, 0, null))
            ) }.isFailure)
        }
        checkCase("NFC and casefold ambiguous SFTP sibling names refuse mutation") {
            fun rows(first: String, second: String) = SftpBrowsePage(listOf(
                SftpRemoteEntry(first, false, 10L, 1L),
                SftpRemoteEntry(second, false, 20L, 2L)), false)
            val ordinary = rows("Café.txt", "Different.txt")
            check(SftpMutationListing.requireComplete(ordinary) === ordinary)
            for (ambiguous in listOf(
                rows("Café.txt", "Cafe\u0301.txt"),
                rows("Episode01.mkv", "episode01.MKV"),
                rows("Folder", "folder"),
                rows("exact", "exact"),
                rows("safe", "../unsafe")
            )) {
                check(runCatching {
                    SftpMutationListing.requireComplete(ambiguous)
                }.isFailure)
            }
            // An incomplete browse page must remain blocked regardless of
            // whether currently shown names happen to be unique.
            check(runCatching {
                SftpMutationListing.requireComplete(ordinary.copy(truncated = true))
            }.isFailure)
        }
        checkCase("display-only SFTP filter and stable name/size/type/newest sort") {
            val original = listOf(
                SftpRemoteEntry("Zeta.mkv", false, 400, 100L),
                SftpRemoteEntry("alpha.TXT", false, 100, null),
                SftpRemoteEntry("beta.mkv", false, 200, 300L),
                SftpRemoteEntry("Folder", true, 0, 200L),
                SftpRemoteEntry("Archive", true, 0, null)
            )
            fun ordered(filter: String, sort: SftpFolderSort) =
                SftpBrowsePresentation.rows(original, filter, sort).map { it.name }
            check(ordered("", SftpFolderSort.NAME) ==
                listOf("Archive", "Folder", "alpha.TXT", "beta.mkv", "Zeta.mkv"))
            check(ordered("", SftpFolderSort.SIZE) ==
                listOf("Archive", "Folder", "alpha.TXT", "beta.mkv", "Zeta.mkv"))
            check(ordered("", SftpFolderSort.TYPE) ==
                listOf("Archive", "Folder", "beta.mkv", "Zeta.mkv", "alpha.TXT"))
            check(ordered("", SftpFolderSort.NEWEST) ==
                listOf("Folder", "Archive", "beta.mkv", "Zeta.mkv", "alpha.TXT"))
            check(ordered(" MKV ", SftpFolderSort.NEWEST) ==
                listOf("beta.mkv", "Zeta.mkv"))
            check(original.map { it.name } ==
                listOf("Zeta.mkv", "alpha.TXT", "beta.mkv", "Folder", "Archive"))
            // A filtered/truncated browse page remains truncated and unusable
            // for mutation, even if the on-screen rows would be empty.
            val incomplete = SftpBrowsePage(original, truncated = true)
            check(SftpBrowsePresentation.rows(incomplete.entries, "unseen",
                SftpFolderSort.NAME).isEmpty())
            check(runCatching {
                SftpMutationListing.requireComplete(incomplete)
            }.isFailure)
        }
        checkCase("invalid target blocked before network") {
            check(runCatching { SftpTarget("user@example.invalid", trustedHostKey = pin) }.isFailure)
            for (host in listOf("host\u0000x", "host\u001bx", "host\u007fx", "host\tname")) {
                check(runCatching {
                    SftpTarget(host, trustedHostKey = pin)
                }.isFailure)
            }
            check(SftpTarget("example.invalid", trustedHostKey = pin).host == "example.invalid")
            check(runCatching { SftpTarget("example.invalid", 65536, pin) }.isFailure)
        }
        println("PASS SFTP total=" + passed + " evidence=synthetic-host-key-only")
    }
}
