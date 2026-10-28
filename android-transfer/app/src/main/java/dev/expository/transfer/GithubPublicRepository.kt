package dev.expository.transfer

import android.net.Uri
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * Anonymous public GitHub browse only. Never accepts an arbitrary URL, token,
 * private repository credential or write endpoint from a browser row.
 */
internal data class PublicGithubRepository(
    val owner: String,
    val repository: String,
    val ref: String = ""
) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))) {
            "INVALID_PUBLIC_GITHUB_OWNER"
        }
        require(repository.matches(Regex("[A-Za-z0-9_.-]{1,100}")) &&
            repository != "." && repository != ".." &&
            !repository.endsWith(".git")) { "INVALID_PUBLIC_GITHUB_REPOSITORY" }
        require(ref.length <= 255 && ref.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            ref.none { it.code < 32 || it.code == 127 || it == '\\' } &&
            !ref.contains("..") && !ref.contains("@{") &&
            !ref.startsWith("/") && !ref.endsWith("/")) {
            "INVALID_PUBLIC_GITHUB_REF"
        }
    }

    fun apiDirectory(path: String): URL {
        val segments = GithubPublicDirectory.checkedPath(path)
        val endpoint = "https://api.github.com/repos/" +
            Uri.encode(owner) + "/" + Uri.encode(repository) + "/contents" +
            if (segments.isEmpty()) "" else "/" +
                segments.joinToString("/") { Uri.encode(it) }
        val query = if (ref.isBlank()) "" else "?ref=" + Uri.encode(ref)
        return URL(endpoint + query)
    }
}

internal data class PublicGithubEntry(
    val name: String,
    val path: String,
    val sha: String,
    val type: String,
    val bytes: Long?
) {
    val directory: Boolean get() = type == "dir"
    val file: Boolean get() = type == "file"
}

internal data class PublicGithubListing(
    val entries: List<PublicGithubEntry>,
    val maybeIncomplete: Boolean,
    val parent: String
)

/**
 * The GitHub Contents API has a documented 1,000-row directory ceiling and
 * no continuation token. Exactly 1,000 entries cannot prove completeness.
 * This adapter therefore NEVER produces a destination mutation witness.
 */
internal object GithubPublicDirectory {
    const val MAX_ROWS = 1_000
    const val MAX_RESPONSE_BYTES = 2_000_000
    const val FIRST_PAGE = 200

    private fun validChild(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    fun checkedPath(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        require(path.length <= 4_096 && !path.startsWith("/") &&
            !path.endsWith("/")) { "INVALID_GITHUB_DIRECTORY_PATH" }
        return path.split('/').also { parts ->
            require(parts.size <= 64 && parts.all(::validChild)) {
                "INVALID_GITHUB_DIRECTORY_PATH"
            }
        }
    }

    /** Pure decoder, testable with synthetic JSON and no network account. */
    fun decodeDirectory(json: String, parent: String): PublicGithubListing {
        val parentSegments = checkedPath(parent)
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "OVERSIZED_GITHUB_DIRECTORY_RESPONSE"
        }
        val records = JSONArray(json)
        require(records.length() <= MAX_ROWS) { "GITHUB_CONTENTS_LIMIT_EXCEEDED" }
        val seen = HashSet<String>()
        val entries = ArrayList<PublicGithubEntry>(records.length())
        for (index in 0 until records.length()) {
            val item = records.getJSONObject(index)
            val name = item.getString("name")
            val path = item.getString("path")
            // GitHub reports some submodules as "file" for compatibility.
            // Never misrepresent these Git pointers as downloadable bytes.
            val reportedType = item.getString("type")
            val type = when {
                item.has("submodule_git_url") &&
                    !item.isNull("submodule_git_url") -> "submodule"
                item.has("target") && !item.isNull("target") -> "symlink"
                else -> reportedType
            }
            val sha = item.getString("sha")
            require(validChild(name) &&
                Normalizer.normalize(name, Normalizer.Form.NFC) == name &&
                seen.add(name) && type in setOf("file", "dir", "symlink", "submodule") &&
                sha.matches(Regex("[0-9a-fA-F]{40,64}")) &&
                checkedPath(path) == parentSegments + name) {
                "AMBIGUOUS_GITHUB_CONTENTS_ROW"
            }
            val size = if (item.has("size") && !item.isNull("size"))
                item.getLong("size").takeIf { it >= 0L } else null
            entries.add(PublicGithubEntry(name, path, sha, type, size))
        }
        return PublicGithubListing(
            entries.sortedWith(compareByDescending<PublicGithubEntry> { it.directory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }),
            records.length() == MAX_ROWS, parent
        )
    }

    private fun readCapped(stream: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(8_192)
        stream.use { source ->
            while (true) {
                val count = source.read(bytes)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > MAX_RESPONSE_BYTES)
                    throw IOException("GITHUB_DIRECTORY_RESPONSE_TOO_LARGE")
                output.write(bytes, 0, count)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    /** Bounded, read-only HTTPS request. No redirect, auth header or writes. */
    fun list(repository: PublicGithubRepository, path: String): PublicGithubListing {
        val url = repository.apiDirectory(path)
        require(url.protocol == "https" && url.host == "api.github.com")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Expository-Transfer-Android")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            if (connection.responseCode != 200)
                throw IOException("GITHUB_PUBLIC_BROWSE_HTTP_" + connection.responseCode)
            return decodeDirectory(readCapped(connection.inputStream), path)
        } finally {
            connection.disconnect()
        }
    }
}


/**
 * Import only a canonical HTTPS GitHub repository URL or an immutable
 * 40-character commit's directory permalink. Branch URLs are ambiguous when
 * branch names contain '/', so they must be entered using the separate ref
 * field instead of guessing where the folder path starts.
 *
 * No redirects, query/fragment tokens, userinfo, alternate hosts or encoded
 * path components may become an authority or path in this native browser.
 */
internal data class PublicGithubUrlTarget(
    val repository: PublicGithubRepository,
    val folder: String = ""
)

internal object GithubPublicUrl {
    fun parse(input: String): PublicGithubUrlTarget {
        require(input.isNotBlank() && input.length <= 4_096 &&
            input == input.trim() && input.none { it.code < 32 || it.code == 127 }) {
            "INVALID_GITHUB_LINK"
        }
        val uri = java.net.URI(input)
        require(uri.scheme == "https" && uri.host == "github.com" &&
            uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath != null &&
            !uri.rawPath.contains('%') && !uri.rawPath.contains('\\')) {
            "UNSAFE_GITHUB_LINK"
        }
        val path = uri.rawPath.removePrefix("/").removeSuffix("/")
        val parts = path.split('/')
        require(parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            "GITHUB_REPOSITORY_LINK_REQUIRED"
        }
        val owner = parts[0]
        val repository = parts[1]
        if (parts.size == 2) {
            return PublicGithubUrlTarget(PublicGithubRepository(owner, repository))
        }
        require(parts.size >= 4 && parts[2] == "tree" &&
            parts[3].matches(Regex("[0-9a-fA-F]{40}"))) {
            "GITHUB_LINK_REQUIRES_FULL_COMMIT_SHA"
        }
        val folder = parts.drop(4).joinToString("/")
        GithubPublicDirectory.checkedPath(folder)
        return PublicGithubUrlTarget(
            PublicGithubRepository(owner, repository, parts[3]), folder)
    }
}
