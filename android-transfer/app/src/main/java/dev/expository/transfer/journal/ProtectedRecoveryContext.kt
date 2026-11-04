package dev.expository.transfer.journal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A MANUAL-review hint, never an executable transfer request or saved grant.
 *
 * These fields are user-visible, originally observed from an OS-authorized
 * document selection. Provider-returned labels can be personal information,
 * therefore the entire record is protected at rest. No URI, document ID,
 * username, path, credential, source bytes, grant or access token is retained.
 * A nullable version-2 SHA-256 fingerprint of a previously selected SAF tree
 * root is encrypted with the labels; it is not an access grant or item receipt.
 */
data class ProtectedRecoveryContext(
    val operation: String,
    val sourceLabel: String,
    val destinationLabel: String,
    val destinationProvider: String,
    val destinationTreeRootFingerprint: String? = null,
    /** Optional original provider document ID digest; never executable. */
    val sourceDocumentFingerprint: String? = null
) {
    // These labels are separately encrypted at rest, but their generated
    // data-class toString previously leaked them into any incidental debug
    // log, exception wrapper or test report. UI reads named fields directly.
    override fun toString(): String = "ProtectedRecoveryContext(redacted)"
}

/**
 * Versioned, one-shot, device-bound AES-256-GCM context beside the EXISTING
 * minimal write-ahead attempt journal. Deliberately separate from journal
 * serialization: a failed or lost hint can never erase or replay an attempt.
 *
 * This is a local operator aid only. Re-opening any document still requires a
 * NEW Android picker grant and independent destination observation. The
 * non-authentication-bound Keystore key does not imply biometric protection.
 */
class ProtectedRecoveryContextStore(context: Context) {
    private val app = context.applicationContext
    private val dir = context.noBackupFilesDir

    companion object {
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val MAGIC = "EXPRCTX1".toByteArray(StandardCharsets.US_ASCII)
        private const val PREFIX = "transfer_recovery_context_v1_"
        private const val KEY_ALIAS = "expository-transfer-recovery-context-v1"
        private const val MAX_RECORD = 2048
        private const val MAX_TEXT = 180
        private val TREE_ROOT_FINGERPRINT = Regex("[0-9a-f]{64}")
        private val SOURCE_DOCUMENT_FINGERPRINT = Regex("[0-9a-f]{64}")
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 128
        private val LOCK = Any()
    }

    private fun destinationFile(digest: String): File {
        require(DIGEST.matches(digest)) { "INVALID_RECOVERY_KEY" }
        return File(dir, PREFIX + digest)
    }

    private fun contextAAD(digest: String): ByteArray =
        ("ExpositoryTransfer.RecoveryContext.V1\u0000" + app.packageName +
            "\u0000" + digest).toByteArray(StandardCharsets.UTF_8)

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val current = store.getKey(KEY_ALIAS, null)
        if (current != null) return current as? SecretKey
            ?: throw IOException("RECOVERY_KEY_INVALID")
        if (!create) throw IOException("RECOVERY_KEY_UNAVAILABLE_NO_RESET")
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private fun allowed(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_TEXT &&
            value.none { it.code < 32 || it.code == 127 }

    /** One hint per operation digest. Existing .bak/.new are NOT overwritten. */
    fun recordNew(digest: String, context: ProtectedRecoveryContext): Unit =
        synchronized(LOCK) {
            val targetFile = destinationFile(digest)
            require(context.operation in setOf("SAF_PICKER_COPY", "SAF_RECURSIVE_COPY", "SAF_DUAL_COPY") &&
                allowed(context.sourceLabel) &&
                allowed(context.destinationLabel) &&
                allowed(context.destinationProvider) &&
                (context.destinationTreeRootFingerprint == null ||
                    TREE_ROOT_FINGERPRINT.matches(context.destinationTreeRootFingerprint)) &&
                (context.sourceDocumentFingerprint == null ||
                    SOURCE_DOCUMENT_FINGERPRINT.matches(context.sourceDocumentFingerprint))) {
                "INVALID_RECOVERY_CONTEXT"
            }
            if (targetFile.exists() || File(targetFile.path + ".bak").exists() ||
                File(targetFile.path + ".new").exists())
                throw IOException("RECOVERY_CONTEXT_ALREADY_RECORDED")
            val plain = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    // V1 (four text fields) and V2 (optional destination
                    // root digest) remain readable. V3 additionally seals
                    // the original source document ID digest, if available.
                    out.writeByte(3)
                    out.writeUTF(context.operation)
                    out.writeUTF(context.sourceLabel)
                    out.writeUTF(context.destinationLabel)
                    out.writeUTF(context.destinationProvider)
                    out.writeUTF(context.destinationTreeRootFingerprint.orEmpty())
                    out.writeUTF(context.sourceDocumentFingerprint.orEmpty())
                }
            }.toByteArray()
            val record = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
                cipher.updateAAD(contextAAD(digest))
                val iv = cipher.iv
                if (iv.size != IV_LENGTH) throw IOException("INVALID_RECOVERY_IV")
                val encrypted = cipher.doFinal(plain)
                MAGIC + iv + encrypted
            } catch (_: Exception) {
                throw IOException("RECOVERY_CONTEXT_ENCRYPTION_UNAVAILABLE")
            } finally {
                plain.fill(0)
            }
            try {
                if (record.size > MAX_RECORD) throw IOException("RECOVERY_CONTEXT_OVERSIZE")
                val atomic = AtomicFile(targetFile)
                val stream = atomic.startWrite()
                try {
                    stream.write(record)
                    atomic.finishWrite(stream)
                } catch (_: Exception) {
                    atomic.failWrite(stream)
                    throw IOException("RECOVERY_CONTEXT_ATOMIC_WRITE_FAILED")
                }
            } finally {
                record.fill(0)
            }
        }

    /** Missing legacy hint is null; unreadable/tampered hint FAILS CLOSED. */
    fun read(digest: String): ProtectedRecoveryContext? = synchronized(LOCK) {
        val base = destinationFile(digest)
        // A status refresh is purely observational. Android AtomicFile
        // openRead() can rename a legacy .bak over the base and delete an
        // outstanding .new while a writer is active. Never invoke it here:
        // sidecars are evidence of an interrupted/ambiguous commit, not
        // permission for a read path to repair or discard their bytes.
        if (File(base.path + ".bak").exists() ||
            File(base.path + ".new").exists())
            throw IOException("RECOVERY_CONTEXT_INTERRUPTED_WRITE")
        val bytes = try {
            FileInputStream(base).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val n = stream.read(buffer)
                    if (n == -1) break
                    if (n == 0) {
                        val b = stream.read()
                        if (b == -1) break
                        output.write(b)
                    } else output.write(buffer, 0, n)
                    if (output.size() > MAX_RECORD)
                        throw IOException("RECOVERY_CONTEXT_OVERSIZE")
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            // An existing base or backup that cannot be opened is a damaged
            // hint, not permission to report "legacy record, no context".
            if (base.exists() || File(base.path + ".bak").exists())
                throw IOException("RECOVERY_CONTEXT_INACCESSIBLE")
            return@synchronized null
        } catch (_: Exception) {
            throw IOException("RECOVERY_CONTEXT_UNREADABLE")
        }
        try {
            if (bytes.size !in (MAGIC.size + IV_LENGTH + 16)..MAX_RECORD ||
                !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
                throw IOException("RECOVERY_CONTEXT_BAD_RECORD")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false),
                GCMParameterSpec(TAG_LENGTH,
                    bytes.copyOfRange(MAGIC.size, MAGIC.size + IV_LENGTH)))
            cipher.updateAAD(contextAAD(digest))
            val plaintext = cipher.doFinal(bytes, MAGIC.size + IV_LENGTH,
                bytes.size - MAGIC.size - IV_LENGTH)
            try {
                DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                    val version = input.readUnsignedByte()
                    if (version !in 1..3)
                        throw IOException("RECOVERY_CONTEXT_VERSION_UNSUPPORTED")
                    val operation = input.readUTF()
                    val source = input.readUTF()
                    val destination = input.readUTF()
                    val provider = input.readUTF()
                    val root = if (version >= 2)
                        input.readUTF().takeUnless { it.isEmpty() }
                    else null
                    val sourceDocument = if (version >= 3)
                        input.readUTF().takeUnless { it.isEmpty() }
                    else null
                    val context = ProtectedRecoveryContext(
                        operation, source, destination, provider, root, sourceDocument)
                    if (input.available() != 0 ||
                        (root != null && !TREE_ROOT_FINGERPRINT.matches(root)) ||
                        (sourceDocument != null &&
                            !SOURCE_DOCUMENT_FINGERPRINT.matches(sourceDocument)) ||
                        context.operation !in setOf("SAF_PICKER_COPY", "SAF_RECURSIVE_COPY", "SAF_DUAL_COPY") ||
                        !allowed(context.sourceLabel) ||
                        !allowed(context.destinationLabel) ||
                        !allowed(context.destinationProvider))
                        throw IOException("RECOVERY_CONTEXT_INVALID")
                    return@synchronized context
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            throw IOException("RECOVERY_CONTEXT_UNREADABLE_OR_TAMPERED")
        } finally {
            bytes.fill(0)
        }
    }
}
