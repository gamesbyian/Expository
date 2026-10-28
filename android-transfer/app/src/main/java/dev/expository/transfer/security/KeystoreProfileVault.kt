package dev.expository.transfer.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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
 * Device-bound encrypted-at-rest primitive for FUTURE explicitly consented
 * remote profiles. No login screen currently calls this class.
 *
 * Contents are opaque caller-owned bytes: any eventual profile codec must
 * include host/username/port/key material INSIDE the authenticated ciphertext,
 * and wipe its own temporary plaintext promptly. Slot IDs are random UUIDs,
 * never a server name, username or file path. Records are stored in no-backup
 * app-private storage; the AES key cannot be exported from AndroidKeyStore.
 *
 * Encryption is AES-256-GCM with a fresh platform-generated IV per creation,
 * and AAD binds the encrypted record to this app and its exact slot. The
 * Keystore key here is NOT user-authentication-bound: a real BiometricPrompt
 * and SessionAccessGate UI enforcement are separate required features.
 * This class therefore must not be described as a biometric profile vault.
 *
 * Creation is intentionally one-shot. A previous committed or interrupted
 * record can never be silently replaced after a missing key, crash or failed
 * authentication. No automatic credentials restoration or migration.
 */
class KeystoreProfileVault(context: Context) {
    private val app = context.applicationContext
    private val dir = app.noBackupFilesDir
    private val alias = "expository-transfer-device-profiles-v1"

    companion object {
        private const val FILE_PREFIX = "remote_profile_v1_"
        private const val MAX_PLAINTEXT = 16 * 1024
        private const val IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private val MAGIC = "EXPRVLT1".toByteArray(StandardCharsets.US_ASCII)
        private const val MAX_RECORD = 8 + IV_SIZE + MAX_PLAINTEXT + 16
        private val UUID_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )
        // The Android app is currently a single process. Serialize all vault
        // instances inside that process to avoid competing AtomicFile writes.
        private val LOCK = Any()
    }

    private fun slotFile(slot: String): File {
        require(UUID_PATTERN.matches(slot)) { "INVALID_OPAQUE_PROFILE_SLOT" }
        return File(dir, FILE_PREFIX + slot)
    }

    private fun authenticatedContext(slot: String): ByteArray =
        ("ExpositoryTransfer.Profile.V1\u0000" + app.packageName + "\u0000" + slot)
            .toByteArray(StandardCharsets.UTF_8)

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun key(createIfAbsent: Boolean): SecretKey {
        val existing = keystore().getKey(alias, null)
        if (existing != null) return existing as? SecretKey
            ?: throw IOException("PROFILE_KEY_INVALID")
        if (!createIfAbsent) throw IOException("PROFILE_KEY_MISSING_NO_AUTO_RESET")
        val spec = KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            // The access gate is not implemented. Do not claim that this
            // encryption key itself requires biometric or device credentials.
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    private fun atomic(slot: String) = AtomicFile(slotFile(slot))

    /**
     * Store only a NEW explicitly approved slot. The caller must wipe its
     * supplied plaintext; this method never retains the array after return.
     * No profile listing, UI, saved-password setting or silent auto-save.
     */
    fun createNew(slot: String, plaintext: ByteArray) = synchronized(LOCK) {
        val file = slotFile(slot)
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT) {
            "INVALID_PROFILE_RECORD_SIZE"
        }
        if (file.exists() || File(file.path + ".bak").exists() ||
            File(file.path + ".new").exists()) throw IOException("PROFILE_SLOT_ALREADY_USED")
        val record = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(createIfAbsent = true))
            cipher.updateAAD(authenticatedContext(slot))
            val iv = cipher.iv
            require(iv.size == IV_SIZE) { "UNSUPPORTED_GCM_IV" }
            val encrypted = cipher.doFinal(plaintext)
            val packed = ByteArray(MAGIC.size + IV_SIZE + encrypted.size)
            MAGIC.copyInto(packed)
            iv.copyInto(packed, MAGIC.size)
            encrypted.copyInto(packed, MAGIC.size + IV_SIZE)
            encrypted.fill(0)
            packed
        } catch (_: Exception) {
            throw IOException("PROFILE_ENCRYPTION_UNAVAILABLE")
        }
        val target = atomic(slot)
        val output = try { target.startWrite() } catch (_: Exception) {
            record.fill(0)
            throw IOException("PROFILE_RECORD_CREATE_FAILED")
        }
        try {
            output.write(record)
            target.finishWrite(output)
        } catch (_: Exception) {
            target.failWrite(output)
            throw IOException("PROFILE_ATOMIC_WRITE_FAILED")
        } finally {
            record.fill(0)
        }
    }

    /**
     * Return a fresh plaintext array, or null if no record exists. Caller
     * MUST wipe the returned array after use. Corrupt/tampered records,
     * unavailable device-bound keys, or unreadable storage fail closed.
     */
    fun open(slot: String): ByteArray? = synchronized(LOCK) {
        val target = atomic(slot)
        val input = try { target.openRead() }
            catch (_: FileNotFoundException) { return@synchronized null }
            catch (_: Exception) { throw IOException("PROFILE_RECORD_UNREADABLE") }
        val record = ByteArray(MAX_RECORD + 1)
        var used = 0
        try {
            input.use { stream ->
                while (used < record.size) {
                    val n = stream.read(record, used, record.size - used)
                    if (n < 0) break
                    if (n == 0) {
                        val byte = stream.read()
                        if (byte < 0) break
                        record[used++] = byte.toByte()
                    } else used += n
                }
            }
            if (used < MAGIC.size + IV_SIZE + 16 || used > MAX_RECORD ||
                !record.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw IOException("PROFILE_RECORD_INVALID_OR_OVERSIZE")
            }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = record.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)
                cipher.init(Cipher.DECRYPT_MODE, key(createIfAbsent = false),
                    GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(authenticatedContext(slot))
                // Never disclose crypto/provider exception details or ciphertext.
                val plaintext = cipher.doFinal(record, MAGIC.size + IV_SIZE,
                    used - MAGIC.size - IV_SIZE)
                if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT) {
                    plaintext.fill(0)
                    throw IOException("PROFILE_PLAINTEXT_SIZE_INVALID")
                }
                plaintext
            } catch (_: Exception) {
                throw IOException("PROFILE_RECORD_AUTHENTICATION_FAILED")
            }
        } finally {
            record.fill(0)
        }
    }

    /**
     * Enumerate only opaque slots in app-private no-backup storage. Names,
     * hosts, usernames and passwords never enter filenames or a plaintext
     * index. Corrupt encrypted records remain visible for explicit deletion.
     */
    fun listSlots(): List<String> = synchronized(LOCK) {
        val names = dir.list() ?: throw IOException("PROFILE_SLOT_INDEX_UNAVAILABLE")
        // AtomicFile may leave a recoverable .bak or an uncommitted .new
        // after process death. Keep these opaque slots discoverable for
        // recovery or explicit deletion; NEVER silently auto-reset them.
        names.asSequence().filter { it.startsWith(FILE_PREFIX) }
            .map { it.removePrefix(FILE_PREFIX) }
            .map {
                when {
                    it.endsWith(".bak") -> it.removeSuffix(".bak")
                    it.endsWith(".new") -> it.removeSuffix(".new")
                    else -> it
                }
            }
            .filter { UUID_PATTERN.matches(it) }
            .distinct()
            .filter {
                val base = slotFile(it)
                base.isFile || File(base.path + ".bak").isFile ||
                    File(base.path + ".new").isFile
            }
            .sorted().take(256).toList()
    }

    /** Explicit profile removal only. Never delete on failed unlock or read. */
    fun delete(slot: String) = synchronized(LOCK) {
        val file = slotFile(slot)
        atomic(slot).delete()
        if (file.exists() || File(file.path + ".bak").exists() ||
            File(file.path + ".new").exists()) throw IOException("PROFILE_DELETE_UNCERTAIN")
    }
}
