package dev.expository.transfer.journal

import android.net.Uri
import dev.expository.transfer.LocalTreeReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A private, non-executable fingerprint of the exact Android tree ROOT chosen
 * for a Copy. The raw URI/document ID and its access grant are never saved.
 * The fingerprint is written only inside the AES-GCM protected context.
 *
 * This may establish correspondence of provider authority + root document ID
 * after a FRESH user picker grant. It cannot prove an individual created child,
 * document content, source identity, or whether an interrupted write succeeded.
 */
internal enum class RecoveryTreeRootRelation {
    SAME_RECORDED_ROOT,
    DIFFERENT_RECORDED_ROOT,
    NO_SAVED_ROOT,
    UNDETERMINED
}

internal object RecoveryTreeRootIdentity {
    private val sha256 = Regex("[0-9a-f]{64}")

    fun fingerprint(tree: Uri): String {
        require(LocalTreeReader.validTree(tree)) { "INVALID_RECOVERY_TREE" }
        val authority = requireNotNull(tree.authority)
        val rootId = LocalTreeReader.rootId(tree)
        // A malicious provider cannot force an unbounded persisted identity.
        require(authority.length in 1..255 && rootId.length in 1..4096) {
            "INVALID_RECOVERY_ROOT_SCOPE"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf("EXPOSITORY_SAF_TREE_ROOT_V1", authority, rootId)) {
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            val size = bytes.size
            digest.update(byteArrayOf(
                (size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte()
            ))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun compare(recorded: String?, newlyPicked: Uri): RecoveryTreeRootRelation {
        if (recorded == null) return RecoveryTreeRootRelation.NO_SAVED_ROOT
        if (!sha256.matches(recorded)) return RecoveryTreeRootRelation.UNDETERMINED
        val observed = runCatching { fingerprint(newlyPicked) }.getOrNull()
            ?: return RecoveryTreeRootRelation.UNDETERMINED
        return if (MessageDigest.isEqual(
                recorded.toByteArray(StandardCharsets.US_ASCII),
                observed.toByteArray(StandardCharsets.US_ASCII)
            )) RecoveryTreeRootRelation.SAME_RECORDED_ROOT
            else RecoveryTreeRootRelation.DIFFERENT_RECORDED_ROOT
    }
}
