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
 */
data class ProtectedRecoveryContext(
    val operation: String,
    val sourceLabel: String,
    val destinationLabel: String,
    val destinationProvider: String
)

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
            require(context.operation in setOf("SAF_PICKER_COPY", "SAF_RECURSIVE_COPY") &&
                allowed(context.sourceLabel) &&
                allowed(context.destinationLabel) &&
                allowed(context.destinationProvider)) {
                "INVALID_RECOVERY_CONTEXT"
            }
            if (targetFile.exists() || File(targetFile.path + ".bak").exists() ||
                File(targetFile.path + ".new").exists())
                throw IOException("RECOVERY_CONTEXT_ALREADY_RECORDED")
            val plain = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeByte(1)
                    out.writeUTF(context.operation)
                    out.writeUTF(context.sourceLabel)
                    out.writeUTF(context.destinationLabel)
                    out.writeUTF(context.destinationProvider)
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
        // A stranded new file may represent a crash before AtomicFile's
        // first successful commit. It is NOT equivalent to a legacy absent
        // hint. Preserve it for inspection and signal damaged metadata.
        if (!base.exists() && File(base.path + ".new").exists())
            throw IOException("RECOVERY_CONTEXT_INTERRUPTED_WRITE")
        val atomic = AtomicFile(base)
        val bytes = try {
            atomic.openRead().use { stream ->
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
                    if (input.readUnsignedByte() != 1)
                        throw IOException("RECOVERY_CONTEXT_VERSION_UNSUPPORTED")
                    val context = ProtectedRecoveryContext(
                        input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
                    if (input.available() != 0 ||
                        context.operation !in setOf("SAF_PICKER_COPY", "SAF_RECURSIVE_COPY") ||
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
