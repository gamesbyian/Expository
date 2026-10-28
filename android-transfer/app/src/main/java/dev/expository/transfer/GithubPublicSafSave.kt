package dev.expository.transfer

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.planner.*
import dev.expository.transfer.saf.SafSingleDocumentCopy
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Android's Save As provider receives a truthful content type for ordinary
 * original files. It is a hint, never a request to convert a Google Doc; the
 * byte-for-byte destination readback remains mandatory for verified receipts.
 */
internal object GithubOriginalMime {
    fun forFile(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val known = when (extension) {
            "md", "markdown" -> "text/markdown"
            "txt", "log", "csv" -> if (extension == "csv") "text/csv" else "text/plain"
            "json" -> "application/json"
            "yaml", "yml" -> "application/yaml"
            "svg" -> "image/svg+xml"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            else -> null
        }
        return known ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) }
            ?: "application/octet-stream"
    }
}

/** A frozen, read-only public GitHub file and its direct parent at picker launch. */
internal data class PublicGithubSaveSource(
    val repository: PublicGithubRepository,
    val folder: String,
    val entry: PublicGithubEntry
) {
    init {
        require(entry.file && entry.sha.matches(Regex("[0-9a-fA-F]{40}")) &&
            (entry.bytes == null || entry.bytes in 0..GithubPublicBlob.MAX_FILE_BYTES.toLong()) &&
            GithubPublicDirectory.checkedPath(entry.path) ==
            GithubPublicDirectory.checkedPath(folder) + entry.name) {
            "UNSUPPORTED_GITHUB_SAVE_SOURCE"
        }
    }
}

/**
 * Git's blob SHA is the immutable object identity, not a mutable raw branch URL.
 * This first source-only Copy route caps each original at 4 MB, validates the
 * response's Git-object SHA-1 and independently verifies Android readback.
 * SHA-256 Git repositories and larger blobs are deliberately unsupported here.
 */
internal object GithubPublicBlob {
    const val MAX_FILE_BYTES = 4_000_000
    const val MAX_JSON_BYTES = 6_500_000

    private fun gitSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(("blob " + bytes.size + "\u0000").toByteArray(StandardCharsets.US_ASCII))
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Synthetic-fixture-friendly decoder; no network or provider side effects. */
    fun decode(json: String, expectedSha: String, expectedSize: Long?): ByteArray {
        require(expectedSha.matches(Regex("[0-9a-fA-F]{40}")) &&
            json.toByteArray(StandardCharsets.UTF_8).size <= MAX_JSON_BYTES) {
            "INVALID_GITHUB_BLOB_RESPONSE"
        }
        val record = JSONObject(json)
        require(record.getString("sha").equals(expectedSha, ignoreCase = true) &&
            record.getString("encoding") == "base64" &&
            record.getLong("size") in 0..MAX_FILE_BYTES.toLong()) {
            "GITHUB_BLOB_IDENTITY_MISMATCH"
        }
        val content = record.getString("content")
        // Prevent a decoder from allocating beyond the bounded source size.
        require(content.length <= MAX_JSON_BYTES) { "GITHUB_BLOB_TOO_LARGE" }
        val bytes = Base64.decode(content, Base64.DEFAULT)
        // Git LFS exposes a tiny pointer blob, not the original large file.
        // Never label a copied LFS pointer as an original-file Save As.
        val firstLine = bytes.take(50).toByteArray().toString(StandardCharsets.US_ASCII)
            .substringBefore('\n').trimEnd('\r')
        require(firstLine != "version https://git-lfs.github.com/spec/v1") {
            "GITHUB_LFS_POINTER_ORIGINAL_UNAVAILABLE"
        }
        require(bytes.size.toLong() == record.getLong("size") &&
            (expectedSize == null || bytes.size.toLong() == expectedSize) &&
            gitSha(bytes).equals(expectedSha, ignoreCase = true)) {
            "GITHUB_BLOB_CONTENT_MISMATCH"
        }
        return bytes
    }

    /** Fixed HTTPS GitHub host, fixed pinned SHA path, no redirects or tokens. */
    fun fetch(source: PublicGithubSaveSource): ByteArray {
        val repo = source.repository
        val sha = source.entry.sha
        val url = URL("https://api.github.com/repos/" +
            Uri.encode(repo.owner) + "/" + Uri.encode(repo.repository) + "/git/blobs/" + sha)
        require(url.protocol == "https" && url.host == "api.github.com")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Expository-Transfer-Android")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            if (connection.responseCode != 200) throw IOException("GITHUB_BLOB_UNAVAILABLE")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    if (output.size() + n > MAX_JSON_BYTES)
                        throw IOException("GITHUB_BLOB_RESPONSE_TOO_LARGE")
                    output.write(chunk, 0, n)
                }
            }
            return decode(output.toString(StandardCharsets.UTF_8.name()), sha, source.entry.bytes)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A single picked new Android document is the only destination. This does not
 * assume a Drive account, persistent URI grant or verified cloud sync.
 */
internal class GithubPublicSafSave(
    private val resolver: ContentResolver,
    private val storageForPlan: (String) -> AtomicAttemptStorage,
    private val listSource: (PublicGithubRepository, String) -> PublicGithubListing = GithubPublicDirectory::list,
    private val readBlob: (PublicGithubSaveSource) -> ByteArray = GithubPublicBlob::fetch
) {
    private val observer = SafSingleDocumentCopy(resolver)

    fun copy(
        source: PublicGithubSaveSource,
        destination: Uri,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        if (destination.scheme != ContentResolver.SCHEME_CONTENT)
            return CopyReceipt("", CopyProof.BLOCKED, 0L, "INVALID_PICKER_URI")

        val issued = System.currentTimeMillis()
        fun plan(now: Long): TransferPlan {
            val listing = listSource(source.repository, source.folder)
            require(!listing.maybeIncomplete &&
                listing.entries.singleOrNull { it.path == source.entry.path } == source.entry) {
                "GITHUB_SOURCE_CHANGED_OR_INCOMPLETE"
            }
            val saved = observer.observe(destination)
            val repo = source.repository
            val components = GithubPublicDirectory.checkedPath(source.folder)
            val authority = repo.owner + "/" + repo.repository
            val read = CapabilityProof(Support.SUPPORTED, "https-github-immutable-blob",
                issued + 60_000)
            val write = CapabilityProof(Support.SUPPORTED, "create-document-picker",
                issued + 60_000)
            val from = LocationRef(
                LocationKey(LocationKind.GITHUB_REPOSITORY, authority, "public-repository",
                    source.folder.ifBlank { "/" }, "anonymous:" + repo.ref),
                components, source.entry.sha, mapOf(Capability.READ to read))
            val to = LocationRef(
                LocationKey(LocationKind.LOCAL_SAF, destination.authority.orEmpty(),
                    "picker-created-document", destination.toString(), "write-uri-grant"),
                emptyList(), saved.revision, mapOf(Capability.WRITE to write))
            val item = ItemRef(ItemKey(authority, source.entry.path, source.entry.sha),
                from, components, source.entry.name, ItemKind.FILE)
            return OperationPlanner.transfer(
                TransferRequest(listOf(item), to, destinationListing = DestinationListing(
                    "picker-created:" + destination.toString(), true, emptyList(),
                    DestinationWitness.SAF_NEW_DOCUMENT_PICKER)), now)
        }

        val initial = try { plan(issued) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0L, "GITHUB_SOURCE_PREFLIGHT_FAILED")
            }
        val journal = try { DurableAttemptJournal(storageForPlan(initial.digest)) }
            catch (_: Exception) {
                return CopyReceipt(initial.digest, CopyProof.BLOCKED, 0L, "JOURNAL_UNAVAILABLE")
            }
        return JournaledCopyEngine.copy(initial, { plan(System.currentTimeMillis()) },
            object : CopyStreams {
                override fun openSource(): InputStream =
                    ByteArrayInputStream(readBlob(source))

                override fun openNewDestination(): OutputStream =
                    resolver.openOutputStream(destination, "w") ?: throw FileNotFoundException()

                override fun openDestinationReadBack(): InputStream? =
                    resolver.openInputStream(destination)
            }, journal, UUID.randomUUID().toString(), continueRequested, onProgress)
    }
}
