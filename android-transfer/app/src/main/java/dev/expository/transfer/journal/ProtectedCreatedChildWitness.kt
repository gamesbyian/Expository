package dev.expository.transfer.journal

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.TreeListing
import dev.expository.transfer.saf.SafFolderPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Immutable, non-executable post-create evidence for ONE journaled SAF item.
 * The provider's raw parent/child document IDs and grant are not persisted.
 * Encrypted name and salted-by-scope hashes only guide a fresh READ-only review.
 */
internal data class ProtectedCreatedChildWitness(
    val parentFingerprint: String,
    val childFingerprint: String,
    val createdName: String,
    val directory: Boolean
) {
    override fun toString(): String = "ProtectedCreatedChildWitness(redacted)"
}

internal enum class CreatedChildRelation {
    NO_WITNESS,
    DIFFERENT_DIRECT_PARENT,
    INCOMPLETE_PARENT,
    MISSING_OR_AMBIGUOUS_CHILD,
    DIFFERENT_CHILD_IDENTITY,
    SAME_RECORDED_CHILD_IDENTITY,
    UNDETERMINED
}

/** A match proves only provider-reported child identity, never content bytes. */
internal object RecoveryCreatedChildIdentity {
    private val DIGEST = Regex("[0-9a-f]{64}")

    internal fun valid(w: ProtectedCreatedChildWitness): Boolean =
        DIGEST.matches(w.parentFingerprint) &&
            DIGEST.matches(w.childFingerprint) &&
            w.createdName.isNotBlank() &&
            w.createdName.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            w.createdName.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun hash(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val b = part.toByteArray(StandardCharsets.UTF_8)
            val n = b.size
            md.update(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(),
                (n ushr 8).toByte(), n.toByte()))
            md.update(b)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun scope(authority: String, parentId: String): String =
        hash(listOf("EXPOSITORY_CREATED_PARENT_V1", authority, parentId))

    private fun child(authority: String, parentId: String, childId: String,
        name: String, directory: Boolean): String =
        hash(listOf("EXPOSITORY_CREATED_CHILD_V1", authority, parentId, childId,
            name, if (directory) "DIRECTORY" else "FILE"))

    fun capture(tree: Uri, parentId: String, childId: String,
        createdName: String, directory: Boolean): ProtectedCreatedChildWitness {
        require(LocalTreeReader.validTree(tree)) { "INVALID_CREATED_CHILD_TREE" }
        val authority = requireNotNull(tree.authority)
        require(authority.length in 1..255 &&
            parentId.length in 1..4096 && childId.length in 1..4096 &&
            childId != parentId && createdName.isNotBlank()) {
            "INVALID_CREATED_CHILD_SCOPE"
        }
        val witness = ProtectedCreatedChildWitness(
            scope(authority, parentId),
            child(authority, parentId, childId, createdName, directory),
            createdName, directory)
        require(valid(witness)) { "INVALID_CREATED_CHILD_WITNESS" }
        return witness
    }

    /**
     * The user must select the EXACT original direct parent with a NEW
     * Android tree READ grant. Incomplete listings prove nothing, including
     * absence. This is deliberately not an authority or a journal transition.
     */
    fun compare(witness: ProtectedCreatedChildWitness?, pickedParent: Uri,
        listing: TreeListing): CreatedChildRelation {
        if (witness == null) return CreatedChildRelation.NO_WITNESS
        if (!valid(witness)) return CreatedChildRelation.UNDETERMINED
        val authority = pickedParent.authority ?: return CreatedChildRelation.UNDETERMINED
        val root = runCatching { LocalTreeReader.rootId(pickedParent) }.getOrNull()
            ?: return CreatedChildRelation.UNDETERMINED
        if (scope(authority, root) != witness.parentFingerprint)
            return CreatedChildRelation.DIFFERENT_DIRECT_PARENT
        if (!listing.complete || listing.hasMore)
            return CreatedChildRelation.INCOMPLETE_PARENT
        val normalized = SafFolderPolicy.normalized(witness.createdName)
        val candidates = listing.children.filter {
            SafFolderPolicy.normalized(it.title) == normalized
        }
        if (candidates.size != 1 ||
            candidates.single().title != witness.createdName ||
            candidates.single().directory != witness.directory)
            return CreatedChildRelation.MISSING_OR_AMBIGUOUS_CHILD
        return if (child(authority, root, candidates.single().documentId,
                candidates.single().title, candidates.single().directory) ==
            witness.childFingerprint)
            CreatedChildRelation.SAME_RECORDED_CHILD_IDENTITY
        else CreatedChildRelation.DIFFERENT_CHILD_IDENTITY
    }
}

/**
 * Write-once AES-GCM sidecar keyed by the existing opaque journal key.
 * A missing witness NEVER proves that a create did not happen: the provider
 * can mutate before returning a document ID. Never recover AtomicFile sidecars
 * on a read-only scan, including an interrupted writer's .bak/.new files.
 */
internal class ProtectedCreatedChildWitnessStore(context: Context) {
    private val directory = context.noBackupFilesDir
    private val packageName = context.applicationContext.packageName

    companion object {
        private const val PREFIX = "transfer_created_child_v1_"
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val MAGIC = "EXPRCHD1".toByteArray(StandardCharsets.US_ASCII)
        private const val KEY_ALIAS = "expository-transfer-created-child-v1"
        private const val MAX_RECORD = 1024
        private val LOCK = Any()
    }

    private fun file(digest: String): File {
        require(DIGEST.matches(digest)) { "INVALID_CHILD_WITNESS_KEY" }
        return File(directory, PREFIX + digest)
    }

    private fun aad(digest: String): ByteArray =
        ("ExpositoryTransfer.CreatedChild.V1\u0000" + packageName +
            "\u0000" + digest).toByteArray(StandardCharsets.UTF_8)

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = ks.getKey(KEY_ALIAS, null)
        if (old != null) return old as? SecretKey
            ?: throw IOException("CHILD_WITNESS_KEY_INVALID")
        if (!create) throw IOException("CHILD_WITNESS_KEY_UNAVAILABLE")
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    fun recordNew(digest: String, witness: ProtectedCreatedChildWitness) =
        synchronized(LOCK) {
            require(RecoveryCreatedChildIdentity.valid(witness)) {
                "INVALID_CHILD_WITNESS"
            }
            val base = file(digest)
            if (base.exists() || File(base.path + ".new").exists() ||
                File(base.path + ".bak").exists())
                throw IOException("CHILD_WITNESS_ALREADY_RECORDED")
            val plain = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeByte(1)
                    out.writeUTF(witness.parentFingerprint)
                    out.writeUTF(witness.childFingerprint)
                    out.writeUTF(witness.createdName)
                    out.writeBoolean(witness.directory)
                }
            }.toByteArray()
            val encoded = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
                cipher.updateAAD(aad(digest))
                val iv = cipher.iv
                if (iv.size != 12) throw IOException("INVALID_CHILD_WITNESS_IV")
                MAGIC + iv + cipher.doFinal(plain)
            } catch (_: Exception) {
                throw IOException("CHILD_WITNESS_ENCRYPTION_UNAVAILABLE")
            } finally { plain.fill(0) }
            try {
                if (encoded.size > MAX_RECORD)
                    throw IOException("CHILD_WITNESS_OVERSIZE")
                val atomic = AtomicFile(base)
                val stream = atomic.startWrite()
                try {
                    stream.write(encoded)
                    atomic.finishWrite(stream)
                } catch (_: Exception) {
                    atomic.failWrite(stream)
                    throw IOException("CHILD_WITNESS_ATOMIC_WRITE_FAILED")
                }
            } finally { encoded.fill(0) }
        }

    fun read(digest: String): ProtectedCreatedChildWitness? = synchronized(LOCK) {
        val base = file(digest)
        if (File(base.path + ".new").exists() ||
            File(base.path + ".bak").exists())
            throw IOException("CHILD_WITNESS_INTERRUPTED_WRITE")
        val bytes = try {
            FileInputStream(base).use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(256)
                while (true) {
                    val amount = stream.read(buffer)
                    if (amount < 0) break
                    if (amount == 0) continue
                    result.write(buffer, 0, amount)
                    if (result.size() > MAX_RECORD)
                        throw IOException("CHILD_WITNESS_OVERSIZE")
                }
                result.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists())
                throw IOException("CHILD_WITNESS_INACCESSIBLE")
            return@synchronized null
        } catch (_: Exception) {
            throw IOException("CHILD_WITNESS_UNREADABLE")
        }
        try {
            if (bytes.size !in (MAGIC.size + 12 + 16)..MAX_RECORD ||
                !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
                throw IOException("CHILD_WITNESS_BAD_RECORD")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false),
                GCMParameterSpec(128,
                    bytes.copyOfRange(MAGIC.size, MAGIC.size + 12)))
            cipher.updateAAD(aad(digest))
            val plaintext = cipher.doFinal(bytes, MAGIC.size + 12,
                bytes.size - MAGIC.size - 12)
            try {
                DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                    if (input.readUnsignedByte() != 1)
                        throw IOException("CHILD_WITNESS_VERSION_UNSUPPORTED")
                    val item = ProtectedCreatedChildWitness(input.readUTF(),
                        input.readUTF(), input.readUTF(), input.readBoolean())
                    if (!RecoveryCreatedChildIdentity.valid(item) ||
                        input.available() != 0)
                        throw IOException("CHILD_WITNESS_INVALID")
                    return@synchronized item
                }
            } finally { plaintext.fill(0) }
        } catch (_: Exception) {
            throw IOException("CHILD_WITNESS_UNREADABLE_OR_TAMPERED")
        } finally { bytes.fill(0) }
    }
}
