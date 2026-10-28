package dev.expository.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
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
}
