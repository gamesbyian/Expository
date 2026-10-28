package dev.expository.transfer.security

import android.content.Context
import dev.expository.transfer.sftp.SftpHostKeyPin
import dev.expository.transfer.sftp.SftpPath
import dev.expository.transfer.sftp.SftpTarget
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Explicitly saved SFTP connection coordinates, not credentials.
 * A password is NEVER included in this schema, serialized, indexed or loaded.
 * One bookmark merely pre-fills the secure connection form; it never connects.
 */
internal data class SavedSftpBookmark(
    val host: String,
    val port: Int,
    val folder: String,
    val username: String,
    val hostKeyPin: String
) {
    init {
        SftpTarget(host, port, SftpHostKeyPin(hostKeyPin))
        SftpPath.requireAbsolute(folder)
        require(username.isNotBlank() && username.length <= 128 &&
            username.toByteArray(StandardCharsets.UTF_8).size <= 512 &&
            username.none { it.code < 32 || it.code == 127 }) {
            "INVALID_BOOKMARK_USERNAME"
        }
    }

    fun encode(): ByteArray =
        JSONObject().put("version", 1)
            .put("host", host).put("port", port).put("folder", folder)
            .put("username", username).put("hostKeyPin", hostKeyPin)
            .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): SavedSftpBookmark {
            require(bytes.isNotEmpty() && bytes.size <= 16 * 1024) {
                "INVALID_BOOKMARK_SIZE"
            }
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(json.length() == 6 && json.getInt("version") == 1) {
                "UNSUPPORTED_BOOKMARK_SCHEMA"
            }
            return SavedSftpBookmark(
                json.getString("host"), json.getInt("port"),
                json.getString("folder"), json.getString("username"),
                json.getString("hostKeyPin")
            )
        }
    }
}

internal data class SavedSftpBookmarkSlot(val slot: String,
    val bookmark: SavedSftpBookmark?)

/**
 * App-private, device-bound AES-GCM encrypted favorites. No plaintext index,
 * biometric claim, password storage, automatic session restoration or network.
 * Corrupt/unreadable slots remain discoverable for explicit user deletion.
 */
internal class SavedSftpBookmarks(context: Context) {
    private val vault = KeystoreProfileVault(context)

    companion object {
        const val MAX_BOOKMARKS = 24
    }

    fun save(bookmark: SavedSftpBookmark): String {
        require(vault.listSlots().size < MAX_BOOKMARKS) {
            "BOOKMARK_LIMIT_REACHED"
        }
        val slot = UUID.randomUUID().toString()
        val plaintext = bookmark.encode()
        try {
            vault.createNew(slot, plaintext)
        } finally {
            plaintext.fill(0)
        }
        return slot
    }

    fun list(): List<SavedSftpBookmarkSlot> =
        vault.listSlots().take(MAX_BOOKMARKS + 1).map { slot ->
            SavedSftpBookmarkSlot(slot, runCatching { load(slot) }.getOrNull())
        }

    fun load(slot: String): SavedSftpBookmark {
        val plaintext = vault.open(slot) ?: throw IllegalStateException("BOOKMARK_NOT_FOUND")
        return try {
            SavedSftpBookmark.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun delete(slot: String) = vault.delete(slot)
}
