package dev.expository.transfer

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import java.util.UUID

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local JSON fixtures only: zero network, tokens, private repositories or
 * third-party GitHub account assumptions. E2 decoder proof, not live E4.
 */
@RunWith(AndroidJUnit4::class)
class GithubPublicDirectoryAndroidTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    private fun row(name: String, path: String, type: String = "file"): JSONObject =
        JSONObject().put("name", name).put("path", path)
            .put("type", type).put("sha", sha).put("size", 13)

    @Test fun folderDecodesAndSortsWithoutInferringWriteAuthority() {
        val json = JSONArray()
            .put(row("readme.md", "docs/readme.md"))
            .put(row("nested", "docs/nested", "dir"))
            .put(row("link", "docs/link", "symlink")).toString()
        val list = GithubPublicDirectory.decodeDirectory(json, "docs")
        assertEquals(3, list.entries.size)
        assertEquals("nested", list.entries[0].name)
        assertTrue(list.entries[0].directory)
        assertEquals("link", list.entries[1].name)
        assertFalse(list.entries[1].directory)
        assertFalse(list.maybeIncomplete)
        assertEquals("docs", list.parent)
    }

    @Test fun publicGithubReverseSortingPreservesOpaqueRowsAndUnknownSizes() {
        val entries = listOf(
            PublicGithubEntry("z-dir", "z-dir", sha, "dir", null),
            PublicGithubEntry("alpha.txt", "alpha.txt", sha, "file", 5),
            PublicGithubEntry("a-dir", "a-dir", sha, "dir", null),
            PublicGithubEntry("beta.png", "beta.png", sha, "file", 22),
            PublicGithubEntry("unknown", "unknown", sha, "file", null)
        )
        fun names(sort: GithubFolderSort, descending: Boolean) =
            GithubFolderPresentation.rows(entries, "", sort, descending).map { it.name }
        assertEquals(listOf("a-dir", "z-dir", "alpha.txt", "beta.png", "unknown"),
            names(GithubFolderSort.NAME, false))
        assertEquals(listOf("z-dir", "a-dir", "unknown", "beta.png", "alpha.txt"),
            names(GithubFolderSort.NAME, true))
        assertEquals(listOf("a-dir", "z-dir", "beta.png", "alpha.txt", "unknown"),
            names(GithubFolderSort.SIZE, true))
        assertEquals(listOf("a-dir", "z-dir", "alpha.txt", "beta.png", "unknown"),
            names(GithubFolderSort.SIZE, false))
        assertEquals(listOf("a-dir", "z-dir", "alpha.txt"),
            GithubFolderPresentation.rows(entries, "a", GithubFolderSort.NAME)
                .filter { it.name == "a-dir" || it.name == "alpha.txt" }
                .map { it.name })
        assertEquals(listOf("z-dir", "alpha.txt", "a-dir", "beta.png", "unknown"),
            entries.map { it.name }) // no mutation of provider/source identity
    }

    @Test fun ambiguousSourceRowsAndEscapedParentPathsBlock() {
        for (rows in listOf(
            JSONArray().put(row("dup", "dup")).put(row("dup", "dup")),
            JSONArray().put(row("outside", "outside")).put(row("evil", "other/evil")),
            JSONArray().put(row("..", "..")),
            JSONArray().put(row("slash/name", "slash/name")),
            JSONArray().put(row("a", "a", "unsupported"))
        )) {
            assertTrue(runCatching {
                GithubPublicDirectory.decodeDirectory(rows.toString(), "")
            }.isFailure)
        }
        assertTrue(runCatching {
            GithubPublicDirectory.decodeDirectory(
                JSONArray().put(row("doc", "wrong/doc")).toString(), "docs")
        }.isFailure)
    }

    @Test fun repositoryAndRefCannotRedirectHttpsOrTraverse() {
        val repository = PublicGithubRepository("example-org", "example.repo", "feature/docs")
        val url = repository.apiDirectory("src/nested")
        assertEquals("https", url.protocol)
        assertEquals("api.github.com", url.host)
        assertTrue(url.toString().contains("/contents/src/nested?ref=feature%2Fdocs"))
        for (owner in listOf("", "a@", "x/y", "-leading", "trailing-",
            "a?x=1", "a\nvalue")) {
            assertTrue(runCatching {
                PublicGithubRepository(owner, "repo")
            }.isFailure)
        }
        for (path in listOf("/etc", "../secret", "a/../b", "a//b", "a/")) {
            assertTrue(runCatching { repository.apiDirectory(path) }.isFailure)
        }
        assertTrue(runCatching {
            PublicGithubRepository("owner", "repo", "main\r\nAuthorization: x")
        }.isFailure)
    }

    @Test fun exactGithubContentsLimitIsExplicitlyIncomplete() {
        val rows = JSONArray()
        for (i in 0 until GithubPublicDirectory.MAX_ROWS) {
            rows.put(row("file" + i + ".txt", "file" + i + ".txt"))
        }
        val listing = GithubPublicDirectory.decodeDirectory(rows.toString(), "")
        assertEquals(GithubPublicDirectory.MAX_ROWS, listing.entries.size)
        assertTrue(listing.maybeIncomplete)
        rows.put(row("extra", "extra"))
        assertTrue(runCatching {
            GithubPublicDirectory.decodeDirectory(rows.toString(), "")
        }.isFailure)
    }

    @Test fun responseSizeIsBoundedBeforeParsing() {
        val overlong = "[" + " ".repeat(GithubPublicDirectory.MAX_RESPONSE_BYTES) + "]"
        assertTrue(runCatching {
            GithubPublicDirectory.decodeDirectory(overlong, "")
        }.isFailure)
    }

    @Test fun defaultBranchUriStaysOnFixedGithubHost() {
        val repository = PublicGithubRepository("octocat", "Hello-World")
        assertEquals("https://api.github.com/repos/octocat/Hello-World/contents",
            repository.apiDirectory("").toString())
        assertEquals("https://api.github.com/repos/octocat/Hello-World/contents/README.md",
            repository.apiDirectory("README.md").toString())
    }

    @Test fun gitBlobDecoderRequiresOriginalObjectIdentityAndSize() {
        val expected = "ce013625030ba8dba906f756967f9e9ca394464a"
        fun objectJson(content: String, sha: String = expected,
                       size: Long = 6L, encoding: String = "base64"): String =
            JSONObject().put("sha", sha).put("encoding", encoding)
                .put("size", size).put("content", content).toString()
        val actual = "hello\n".toByteArray(Charsets.UTF_8)
        val encoded = android.util.Base64.encodeToString(actual, android.util.Base64.NO_WRAP)
        assertArrayEquals(actual, GithubPublicBlob.decode(objectJson(encoded), expected, 6))
        assertTrue(runCatching {
            GithubPublicBlob.decode(objectJson(encoded), "0000000000000000000000000000000000000000", 6)
        }.isFailure)
        assertTrue(runCatching {
            GithubPublicBlob.decode(objectJson(encoded, size = 7), expected, 6)
        }.isFailure)
        assertTrue(runCatching {
            GithubPublicBlob.decode(objectJson(encoded, encoding = "none"), expected, 6)
        }.isFailure)
        assertTrue(runCatching {
            GithubPublicBlob.decode(objectJson("AAAAAA=="), expected, 6)
        }.isFailure)
        assertTrue(runCatching {
            GithubPublicBlob.decode(objectJson(encoded), expected, 9)
        }.isFailure)
        assertTrue(runCatching {
            GithubPublicBlob.decode(objectJson(encoded, size = GithubPublicBlob.MAX_FILE_BYTES + 1L),
                expected, 6)
        }.isFailure)
    }

    @Test fun onlyDirectPublicRegularFilesCanRequestScopedSave() {
        val repository = PublicGithubRepository("octocat", "Hello-World", "main")
        val original = PublicGithubEntry("hello.txt", "src/hello.txt",
            "ce013625030ba8dba906f756967f9e9ca394464a", "file", 6)
        assertEquals(original,
            PublicGithubSaveSource(repository, "src", original).entry)
        for (invalid in listOf(
            original.copy(path = "other/hello.txt"),
            original.copy(type = "symlink"),
            original.copy(type = "submodule"),
            original.copy(type = "dir"),
            original.copy(sha = "bad"),
            original.copy(bytes = GithubPublicBlob.MAX_FILE_BYTES + 1L))) {
            assertTrue(runCatching {
                PublicGithubSaveSource(repository, "src", invalid)
            }.isFailure)
        }
    }

    @Test fun syntheticGitHubOriginalCopiesToActualAndroidProviderWithReadback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val bytes = "hello\n".toByteArray(Charsets.UTF_8)
        val original = PublicGithubEntry("hello.txt", "src/hello.txt",
            "ce013625030ba8dba906f756967f9e9ca394464a", "file", bytes.size.toLong())
        val source = PublicGithubSaveSource(
            PublicGithubRepository("octocat", "Hello-World", "main"), "src", original)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "expository-public-github-fixture-" + UUID.randomUUID() + ".txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("SYNTHETIC_PROVIDER_FIXTURE_UNAVAILABLE")
        try {
            val receipt = GithubPublicSafSave(resolver, { digest ->
                AndroidAtomicAttemptStorage(context.applicationContext, digest)
            }, listSource = { repository, folder ->
                assertEquals(source.repository, repository)
                assertEquals("src", folder)
                PublicGithubListing(listOf(original), false, folder)
            }, readBlob = {
                assertEquals(source, it)
                bytes
            }).copy(source, uri, continueRequested = { true })
            assertEquals(CopyProof.BYTES_VERIFIED, receipt.proof)
            assertEquals(bytes.size.toLong(), receipt.bytesWritten)
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun syntheticGitHubSourceRevisionDriftBlocksBeforeAndroidWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val original = PublicGithubEntry("hello.txt", "src/hello.txt",
            "ce013625030ba8dba906f756967f9e9ca394464a", "file", 6L)
        val source = PublicGithubSaveSource(
            PublicGithubRepository("octocat", "Hello-World", "main"), "src", original)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "expository-public-github-blocked-" + UUID.randomUUID() + ".txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("SYNTHETIC_PROVIDER_FIXTURE_UNAVAILABLE")
        try {
            val receipt = GithubPublicSafSave(resolver, { digest ->
                AndroidAtomicAttemptStorage(context.applicationContext, digest)
            }, listSource = { _, folder ->
                PublicGithubListing(listOf(original.copy(
                    sha = "0000000000000000000000000000000000000000")), false, folder)
            }, readBlob = { error("UNAPPROVED_SOURCE_MUST_NOT_BE_READ") })
                .copy(source, uri, continueRequested = { true })
            assertEquals(CopyProof.BLOCKED, receipt.proof)
            assertEquals(0L, receipt.bytesWritten)
            // Some providers do not materialize a newly inserted pending row until
            // its first write; an absent read stream also proves no bytes were written.
            val untouched = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            assertTrue(untouched == null || untouched.isEmpty())
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun gitLfsPointerCannotBeLabeledOriginalSaveAs() {
        val pointer = "version https://git-lfs.github.com/spec/v1\n" +
            "oid sha256:" + "0".repeat(64) + "\nsize 123456789\n"
        val encoded = android.util.Base64.encodeToString(
            pointer.toByteArray(Charsets.US_ASCII), android.util.Base64.NO_WRAP)
        val sha = "e5dc7be26cec9cf29e3967a40beae0700133a2a2"
        val json = JSONObject().put("sha", sha).put("encoding", "base64")
            .put("size", pointer.toByteArray(Charsets.US_ASCII).size)
            .put("content", encoded).toString()
        assertTrue("Git LFS pointer bytes are not the requested original", runCatching {
            GithubPublicBlob.decode(json, sha, pointer.length.toLong())
        }.isFailure)
    }

    @Test fun publicGithubSaveAsUsesOriginalMimeWithoutGuessingFormats() {
        assertEquals("text/markdown", GithubOriginalMime.forFile("README.md"))
        assertEquals("application/json", GithubOriginalMime.forFile("package.JSON"))
        assertEquals("image/svg+xml", GithubOriginalMime.forFile("icon.svg"))
        assertEquals("text/csv", GithubOriginalMime.forFile("data.csv"))
        assertEquals("application/pdf", GithubOriginalMime.forFile("manual.pdf"))
        assertEquals("application/octet-stream", GithubOriginalMime.forFile("opaque.unknown-extension-xyz"))
        assertEquals("application/octet-stream", GithubOriginalMime.forFile("README"))
    }

    @Test fun canonicalGithubLinkOpensOnlyPublicRepoOrPinnedCommitFolder() {
        val root = GithubPublicUrl.parse("https://github.com/octocat/Hello-World")
        assertEquals(PublicGithubRepository("octocat", "Hello-World"), root.repository)
        assertEquals("", root.folder)
        assertEquals(root, GithubPublicUrl.parse("https://github.com/octocat/Hello-World/"))

        val commit = "0123456789abcdef0123456789abcdef01234567"
        val nested = GithubPublicUrl.parse(
            "https://github.com/octocat/Hello-World/tree/" + commit + "/docs/api")
        assertEquals(PublicGithubRepository("octocat", "Hello-World", commit),
            nested.repository)
        assertEquals("docs/api", nested.folder)
        assertEquals("", GithubPublicUrl.parse(
            "https://github.com/octocat/Hello-World/tree/" + commit).folder)
        assertEquals("https", nested.repository.apiDirectory(nested.folder).protocol)
        assertEquals("api.github.com", nested.repository.apiDirectory(nested.folder).host)
    }

    @Test fun githubLinkRejectsAmbiguousBranchesRedirectsCredentialsAndTraversal() {
        val bad = listOf(
            "", "https://github.com", "https://github.com/octocat",
            "http://github.com/octocat/repo",
            "https://github.com.evil.test/octocat/repo",
            "https://user:token@github.com/octocat/repo",
            "https://github.com:443/octocat/repo",
            "https://github.com/octocat/repo?token=secret",
            "https://github.com/octocat/repo#private",
            "https://github.com/octocat/repo/issues",
            "https://github.com/octocat/repo/tree/main/dir",
            "https://github.com/octocat/repo/blob/" +
                "0123456789abcdef0123456789abcdef01234567/secret",
            "https://github.com/octocat/repo/tree/" +
                "0123456789abcdef0123456789abcdef01234567/../secret",
            "https://github.com/octocat/repo/tree/" +
                "0123456789abcdef0123456789abcdef01234567/%2e%2e/secret",
            "https://github.com/octocat/re%2fpo",
            "https://github.com/octocat/repo//",
            "https://github.com/octocat/repo\nAuthorization: x"
        )
        bad.forEach { link ->
            assertTrue("Untrusted or ambiguous GitHub link must fail", runCatching {
                GithubPublicUrl.parse(link)
            }.isFailure)
        }
    }

    @Test fun folderFilterAndSortNeverAlterSourceListingOrMutationProof() {
        val rows = listOf(
            PublicGithubEntry("alpha", "alpha", sha, "dir", null),
            PublicGithubEntry("beta", "beta", sha, "dir", null),
            PublicGithubEntry("readme.TXT", "readme.TXT", sha, "file", 90L),
            PublicGithubEntry("preview.png", "preview.png", sha, "file", 5L),
            PublicGithubEntry("plain.txt", "plain.txt", sha, "file", null),
            PublicGithubEntry("READ_ME.md", "READ_ME.md", sha, "file", 11L)
        )
        val original = rows.toList()
        val name = GithubFolderPresentation.rows(rows, "", GithubFolderSort.NAME)
        assertEquals(listOf("alpha", "beta"), name.take(2).map { it.name })
        val sizes = GithubFolderPresentation.rows(rows, "", GithubFolderSort.SIZE)
        assertEquals(listOf("preview.png", "READ_ME.md", "readme.TXT", "plain.txt"),
            sizes.drop(2).map { it.name })
        val types = GithubFolderPresentation.rows(rows, "", GithubFolderSort.TYPE)
        assertEquals(listOf("READ_ME.md", "preview.png", "plain.txt", "readme.TXT"),
            types.drop(2).map { it.name })
        assertEquals(listOf("READ_ME.md", "readme.TXT"),
            GithubFolderPresentation.rows(rows, "reAd", GithubFolderSort.NAME)
                .map { it.name })
        assertEquals(emptyList<PublicGithubEntry>(),
            GithubFolderPresentation.rows(rows, "not-here", GithubFolderSort.NAME))
        assertEquals(original, rows)
    }
}
