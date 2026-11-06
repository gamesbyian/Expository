package dev.expository.transfer.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Shared minimum evidence for remote -> Android ACTION_CREATE_DOCUMENT Save As.
 *
 * Pass ONLY the original Android result.data.flags from the returned picker
 * Intent. A content URI alone, a READ grant, or persistable permission alone
 * cannot justify writing the user-selected target. The OS ContentResolver
 * remains the actual permission enforcer: a caller-manufactured flag is not
 * independently authoritative and does not grant access.
 *
 * This predicate never persists, widens or takes a URI permission.
 */
object SafCreatedDocumentWriteGrant {
    fun accepts(uri: Uri?, pickerResultFlags: Int): Boolean =
        uri?.scheme == ContentResolver.SCHEME_CONTENT &&
            !uri.authority.isNullOrBlank() &&
            pickerResultFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
}
