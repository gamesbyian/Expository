package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Validate the single-browser SFTP batch destination returned by Android's
 * standard ACTION_OPEN_DOCUMENT_TREE picker. This grants only the selected
 * folder and never stores a URI or requests broad filesystem permission.
 *
 * No grant is presumed persistent. Every SFTP→SAF Copy separately rechecks
 * the provider, complete destination membership and create capability.
 */
internal object PickedSftpDownloadFolder {
    fun inspect(
        resolver: ContentResolver,
        treeUri: Uri?,
        intentFlags: Int,
        generation: Long
    ): LocalPaneDownloadTarget? = runCatching {
        require(LocalTreeReader.validTree(treeUri))
        require(intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            intentFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val tree = requireNotNull(treeUri)
        val root = LocalTreeReader.rootId(tree)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val canCreate = resolver.query(docUri, cols, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) false else {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(cols[0]))
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(cols[1]))
                val flags = cursor.getInt(cursor.getColumnIndexOrThrow(cols[2]))
                !cursor.moveToNext() && id == root &&
                    mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            }
        } == true
        require(canCreate) { "PICKED_FOLDER_CREATE_CAPABILITY_UNVERIFIED" }
        val children = LocalTreeReader.list(resolver, tree, root, LocalTreeReader.MAX_BROWSE)
        require(children.complete && children.children.size < LocalTreeReader.MAX_BROWSE) {
            "PICKED_FOLDER_MEMBERSHIP_NOT_COMPLETE"
        }
        LocalPaneDownloadTarget(tree, root, generation)
    }.getOrNull()
}
