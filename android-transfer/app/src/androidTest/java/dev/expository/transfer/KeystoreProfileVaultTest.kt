package dev.expository.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.security.KeystoreProfileVault
import dev.expository.transfer.security.SavedSftpBookmark
import dev.expository.transfer.security.SavedSftpBookmarks
import org.junit.Assert.assertEquals
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual AndroidKeyStore + AtomicFile on an API35 emulator. Fixtures are
 * synthetic inert bytes in private no-backup storage, not real account data.
 * No remote connection, biometric prompt or saved-login UI is implied.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreProfileVaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault get() = KeystoreProfileVault(context)
    private fun slot() = UUID.randomUUID().toString()
    private fun file(slot: String) =
        File(context.noBackupFilesDir, "remote_profile_v1_" + slot)

    @Test fun encryptsSyntheticRecordOnDiskAndReadsItBack() {
        val id = slot()
        val original = "Synthetic profile secret fixture. Never a real password."
            .toByteArray(StandardCharsets.UTF_8)
        try {
            vault.createNew(id, original)
            val ciphertext = file(id).readBytes()
            assertTrue(ciphertext.isNotEmpty())
            assertFalse(ciphertext.contentEquals(original))
            assertFalse(String(ciphertext, StandardCharsets.ISO_8859_1).contains(
                "Synthetic profile secret"))
            val recovered = vault.open(id) ?: error("missing encrypted fixture")
            try { assertArrayEquals(original, recovered) } finally { recovered.fill(0) }
        } finally {
            original.fill(0)
            vault.delete(id)
        }
        assertFalse(file(id).exists())
        assertTrue(vault.open(id) == null)
    }

    @Test fun existingSlotCanNeverBeSilentlyReplaced() {
        val id = slot()
        val original = "fixture-one".toByteArray(StandardCharsets.UTF_8)
        val replacement = "fixture-two".toByteArray(StandardCharsets.UTF_8)
        try {
            vault.createNew(id, original)
            val firstCiphertext = file(id).readBytes()
            assertTrue(runCatching { vault.createNew(id, replacement) }.isFailure)
            assertArrayEquals(firstCiphertext, file(id).readBytes())
            val recovered = vault.open(id) ?: error("slot unexpectedly empty")
            try { assertArrayEquals(original, recovered) } finally { recovered.fill(0) }
        } finally {
            original.fill(0)
            replacement.fill(0)
            vault.delete(id)
        }
    }

    @Test fun ciphertextTamperingMustFailAuthenticationWithoutDeletingRecord() {
        val id = slot()
        val bytes = "fixture-tamper".toByteArray(StandardCharsets.UTF_8)
        try {
            vault.createNew(id, bytes)
            val original = file(id).readBytes()
            val tampered = original.clone()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
            file(id).writeBytes(tampered)
            assertTrue(runCatching { vault.open(id) }.isFailure)
            assertTrue(file(id).exists())
            assertTrue(runCatching { vault.createNew(id, bytes) }.isFailure)
            file(id).writeBytes(original)
            val recovered = vault.open(id) ?: error("original fixture lost")
            try { assertArrayEquals(bytes, recovered) } finally { recovered.fill(0) }
        } finally {
            bytes.fill(0)
            vault.delete(id)
        }
    }

    @Test fun ciphertextFromAnotherSlotCannotBeSubstituted() {
        val a = slot()
        val b = slot()
        val left = "fixture-a".toByteArray(StandardCharsets.UTF_8)
        val right = "fixture-b".toByteArray(StandardCharsets.UTF_8)
        try {
            vault.createNew(a, left)
            vault.createNew(b, right)
            file(b).writeBytes(file(a).readBytes())
            assertTrue(runCatching { vault.open(b) }.isFailure)
            val restored = vault.open(a) ?: error("original slot lost")
            try { assertArrayEquals(left, restored) } finally { restored.fill(0) }
        } finally {
            left.fill(0)
            right.fill(0)
            vault.delete(a)
            vault.delete(b)
        }
    }

    @Test fun opaqueSlotValidationAndSizeLimitsBlockUnsafeRecords() {
        val bad = listOf("../secret", "host.example", "ABC", "", "1234/../../other")
        for (id in bad) {
            assertTrue(runCatching { vault.open(id) }.isFailure)
            assertTrue(runCatching {
                vault.createNew(id, "fixture".toByteArray(StandardCharsets.UTF_8))
            }.isFailure)
        }
        val id = slot()
        assertTrue(runCatching { vault.createNew(id, ByteArray(0)) }.isFailure)
        assertTrue(runCatching { vault.createNew(id, ByteArray(16 * 1024 + 1)) }.isFailure)
        assertFalse(file(id).exists())
    }

    @Test fun oversizedRecordMustNotBeReadOrReset() {
        val id = slot()
        try {
            file(id).writeBytes(ByteArray(8 + 12 + 16 * 1024 + 16 + 1) { 65 })
            assertTrue(runCatching { vault.open(id) }.isFailure)
            assertTrue(runCatching {
                vault.createNew(id, byteArrayOf(1, 2, 3))
            }.isFailure)
            assertTrue(file(id).exists())
        } finally {
            vault.delete(id)
        }
    }

    @Test fun bookmarkRoundtripRequiresManualPasswordAfterLoading() {
        val store = SavedSftpBookmarks(context)
        val bookmark = SavedSftpBookmark("example.org", 2222,
            "/uploads", "synthetic-user",
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val slot = store.save(bookmark)
        try {
            assertTrue(vault.listSlots().contains(slot))
            assertEquals(bookmark, store.load(slot))
            val record = file(slot).readBytes()
            val ciphertext = String(record, StandardCharsets.ISO_8859_1)
            assertFalse(ciphertext.contains("example.org"))
            assertFalse(ciphertext.contains("synthetic-user"))
            val plaintext = vault.open(slot) ?: error("bookmark ciphertext missing")
            try {
                val encoded = String(plaintext, StandardCharsets.UTF_8)
                assertFalse(encoded.contains("password", ignoreCase = true))
                assertFalse(encoded.contains("synthetic-password"))
                assertEquals(bookmark, SavedSftpBookmark.decode(plaintext))
            } finally { plaintext.fill(0) }
            assertTrue(store.list().any { it.slot == slot && it.bookmark == bookmark })
        } finally {
            store.delete(slot)
        }
        assertFalse(vault.listSlots().contains(slot))
    }

    @Test fun unreadableBookmarkRemainsVisibleUntilUserExplicitlyDeletesIt() {
        val store = SavedSftpBookmarks(context)
        val bookmark = SavedSftpBookmark("host.example", 22, "/",
            "test", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val id = store.save(bookmark)
        try {
            val bytes = file(id).readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file(id).writeBytes(bytes)
            assertTrue(store.list().any { it.slot == id && it.bookmark == null })
            assertTrue(runCatching { store.load(id) }.isFailure)
            assertTrue(file(id).exists())
        } finally {
            store.delete(id)
        }
    }

    @Test fun incompleteAtomicWriteRemainsDiscoverableForExplicitRemoval() {
        val id = slot()
        val pending = File(file(id).path + ".new")
        val store = SavedSftpBookmarks(context)
        try {
            pending.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(vault.listSlots().contains(id))
            assertTrue(store.list().any { it.slot == id && it.bookmark == null })
            assertTrue(runCatching {
                vault.createNew(id, byteArrayOf(4, 3, 2, 1))
            }.isFailure)
        } finally {
            store.delete(id)
        }
        assertFalse(pending.exists())
        assertFalse(vault.listSlots().contains(id))
    }

    @Test fun invalidSftpBookmarkNeverCreatesEncryptedRecord() {
        val count = vault.listSlots().size
        for (block in listOf<() -> Unit>(
            { SavedSftpBookmark("not/a/host", 22, "/", "user",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") },
            { SavedSftpBookmark("host.example", 0, "/", "user",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") },
            { SavedSftpBookmark("host.example", 22, "../escape", "user",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") },
            { SavedSftpBookmark("host.example", 22, "/", "user" + 10.toChar() + "next",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") },
            { SavedSftpBookmark("host.example", 22, "/", "user", "invalid") }
        )) {
            assertTrue(runCatching { block() }.isFailure)
        }
        assertEquals(count, vault.listSlots().size)
    }
}
