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
 * The number and SHA-256 of bytes handed to a destination after BOTH streams
 * closed normally. Captured before any destination readback, under a previously
 * durable write-ahead attempt. This is neither destination byte verification
 * nor permission to replay an UNCERTAIN write.
 *
 * Content hashes can fingerprint private documents, so their sole durable
 * representation is a separately AES-GCM authenticated, app-private record.
 */
data class ProtectedTransferredByteEvidence(
    val byteCount: Long,
    val sourceSha256Hex: String
) {
    override fun toString(): String = "ProtectedTransferredByteEvidence(redacted)"
}

/** Independent write-once authenticated evidence, keyed to the neutral journal. */
internal class ProtectedTransferredBytesEvidenceStore(context: Context) {
    private val directory = context.noBackupFilesDir
    private val appPackage = context.applicationContext.packageName

    companion object {
        private const val PREFIX = "transfer_source_bytes_v1_"
        private const val KEY_ALIAS = "expository-transfer-source-bytes-v1"
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val MAGIC = "EXPRBYT1".toByteArray(StandardCharsets.US_ASCII)
        private const val MAX_RECORD = 512
        private const val IV_SIZE = 12
        private const val SHA_SIZE = 32
        private val LOCK = Any()
    }

    private fun target(digest: String): File {
        require(DIGEST.matches(digest)) { "INVALID_SOURCE_BYTE_WITNESS_KEY" }
        return File(directory, PREFIX + digest)
    }

    private fun aad(digest: String): ByteArray =
        ("ExpositoryTransfer.SourceBytes.V1\u0000" + appPackage + "\u0000" + digest)
            .toByteArray(StandardCharsets.UTF_8)

    private fun secret(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = store.getKey(KEY_ALIAS, null)
        if (old != null) return old as? SecretKey
            ?: throw IOException("SOURCE_BYTE_KEY_INVALID")
        if (!create) throw IOException("SOURCE_BYTE_KEY_UNAVAILABLE")
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

    /** Called only by a completed-stream callback, never by an index refresh. */
    fun recordNew(digest: String, byteCount: Long, sourceSha256: ByteArray): Unit =
        synchronized(LOCK) {
            require(byteCount >= 0L && sourceSha256.size == SHA_SIZE) {
                "INVALID_COMPLETED_BYTE_WITNESS"
            }
            val base = target(digest)
            if (base.exists() || File(base.path + ".new").exists() ||
                File(base.path + ".bak").exists())
                throw IOException("SOURCE_BYTE_WITNESS_ALREADY_RECORDED")
            val plain = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeByte(1)
                    out.writeLong(byteCount)
                    out.write(sourceSha256)
                }
            }.toByteArray()
            val ciphertext = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secret(create = true))
                cipher.updateAAD(aad(digest))
                val iv = cipher.iv
                if (iv.size != IV_SIZE) throw IOException("INVALID_SOURCE_BYTE_IV")
                MAGIC + iv + cipher.doFinal(plain)
            } catch (_: Exception) {
                throw IOException("SOURCE_BYTE_EVIDENCE_ENCRYPTION_UNAVAILABLE")
            } finally { plain.fill(0) }
            try {
                if (ciphertext.size > MAX_RECORD)
                    throw IOException("SOURCE_BYTE_EVIDENCE_OVERSIZE")
                val atomic = AtomicFile(base)
                val stream = atomic.startWrite()
                try {
                    stream.write(ciphertext)
                    atomic.finishWrite(stream)
                } catch (_: Exception) {
                    atomic.failWrite(stream)
                    throw IOException("SOURCE_BYTE_EVIDENCE_ATOMIC_WRITE_FAILED")
                }
            } finally { ciphertext.fill(0) }
        }

    /**
     * Never invoke AtomicFile.openRead() on an observational refresh: it can
     * rewrite .bak or discard .new belonging to an active/incomplete commit.
     * Missing evidence is null, not proof that the provider received no bytes.
     */
    fun read(digest: String): ProtectedTransferredByteEvidence? = synchronized(LOCK) {
        val base = target(digest)
        if (File(base.path + ".new").exists() ||
            File(base.path + ".bak").exists())
            throw IOException("SOURCE_BYTE_EVIDENCE_INTERRUPTED_WRITE")
        val encrypted = try {
            FileInputStream(base).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(128)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n)
                    if (output.size() > MAX_RECORD)
                        throw IOException("SOURCE_BYTE_EVIDENCE_OVERSIZE")
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists())
                throw IOException("SOURCE_BYTE_EVIDENCE_INACCESSIBLE")
            return@synchronized null
        } catch (_: Exception) {
            throw IOException("SOURCE_BYTE_EVIDENCE_UNREADABLE")
        }
        try {
            if (encrypted.size !in (MAGIC.size + IV_SIZE + 16)..MAX_RECORD ||
                !encrypted.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
                throw IOException("SOURCE_BYTE_EVIDENCE_BAD_RECORD")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(create = false),
                GCMParameterSpec(128,
                    encrypted.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)))
            cipher.updateAAD(aad(digest))
            val plain = cipher.doFinal(encrypted, MAGIC.size + IV_SIZE,
                encrypted.size - MAGIC.size - IV_SIZE)
            try {
                DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    if (input.readUnsignedByte() != 1)
                        throw IOException("SOURCE_BYTE_EVIDENCE_VERSION_UNSUPPORTED")
                    val count = input.readLong()
                    val hash = ByteArray(SHA_SIZE)
                    input.readFully(hash)
                    if (count < 0L || input.available() != 0)
                        throw IOException("SOURCE_BYTE_EVIDENCE_INVALID")
                    return@synchronized ProtectedTransferredByteEvidence(
                        count, hash.joinToString("") { "%02x".format(it) })
                }
            } finally { plain.fill(0) }
        } catch (_: Exception) {
            throw IOException("SOURCE_BYTE_EVIDENCE_UNREADABLE_OR_TAMPERED")
        } finally { encrypted.fill(0) }
    }
}
