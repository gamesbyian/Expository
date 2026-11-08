package dev.expository.transfer

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.journal.AndroidRenameStepStorage
import dev.expository.transfer.planner.JournalState
import java.io.File
import java.util.UUID
import dev.expository.transfer.journal.AttemptRecoveryIndex
import dev.expository.transfer.journal.inspectionReference
import dev.expository.transfer.journal.sourceInspectionReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No real journal files are touched; tests input enumeration failures. */
@RunWith(AndroidJUnit4::class)
class RecoveryIndexFailurePolicyTest {
    @Test fun freshReadOnlySourceByteCheckNeverAuthorizesAnOldAttempt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = app.contentResolver
        val name = "expository-recovery-source-" + UUID.randomUUID() + ".bin"
        val original = ByteArray(137_119) { ((it * 17 + 11) and 255).toByte() }
        val uri = requireNotNull(resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    "application/octet-stream")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }))
        val readGrant = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val inspector = dev.expository.transfer.journal.RecoveryManualSourceInspector
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(original) }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(original).joinToString("") { "%02x".format(it) }
            val evidence = dev.expository.transfer.journal.ProtectedTransferredByteEvidence(
                original.size.toLong(), hash)
            // MediaStore may asynchronously revise indexed metadata just
            // after its output stream closes on a newly created test row.
            // The production inspector correctly refuses an apparent match
            // whenever metadata drifts DURING a read. Retry only that
            // explicit transient observation; every other outcome remains
            // exact and cannot be laundered into a pass.
            fun afterMediaIndexSettles(
                compared: dev.expository.transfer.journal.ProtectedTransferredByteEvidence
            ): dev.expository.transfer.journal.FreshSourceByteRelation {
                var observed = inspector.inspect(resolver, uri, readGrant, compared)
                repeat(8) {
                    if (observed != dev.expository.transfer.journal.FreshSourceByteRelation
                            .SOURCE_METADATA_CHANGED_DURING_READ) return observed
                    Thread.sleep(300L)
                    observed = inspector.inspect(resolver, uri, readGrant, compared)
                }
                return observed
            }
            assertTrue(inspector.acceptsPickerResult(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertTrue(!inspector.acceptsPickerResult(uri, 0))
            assertTrue(!inspector.acceptsPickerResult(
                android.net.Uri.parse("file:///private/stale.bin"),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertEquals(dev.expository.transfer.journal.FreshSourceByteRelation.CURRENT_SELECTION_BYTES_MATCH_RECORDED_TRANSFER,
                afterMediaIndexSettles(evidence))
            assertEquals(dev.expository.transfer.journal.FreshSourceByteRelation.INSPECTION_CANCELED,
                inspector.inspect(resolver, uri, readGrant, evidence,
                    continueRequested = { false }))
            assertEquals(dev.expository.transfer.journal.FreshSourceByteRelation.NO_COMPLETED_TRANSFER_BYTES,
                inspector.inspect(resolver, uri, readGrant, null))
            assertEquals(dev.expository.transfer.journal.FreshSourceByteRelation.BOUNDED_READ_TOO_LARGE,
                inspector.inspect(resolver, uri, readGrant, evidence.copy(
                    byteCount = inspector.MAX_MANUAL_BYTES + 1)))
            assertEquals(dev.expository.transfer.journal.FreshSourceByteRelation.CURRENT_SELECTION_BYTES_DIFFER_FROM_RECORDED_TRANSFER,
                afterMediaIndexSettles(evidence.copy(
                    sourceSha256Hex = "0".repeat(64))))
            assertEquals(dev.expository.transfer.journal.FreshSourceByteRelation.INVALID_SOURCE_GRANT,
                inspector.inspect(resolver,
                    android.net.Uri.parse("file:///private/stale.bin"), readGrant, evidence))
            assertEquals("Readability without a fresh picker grant is insufficient",
                dev.expository.transfer.journal.FreshSourceByteRelation.INVALID_SOURCE_GRANT,
                inspector.inspect(resolver, uri, 0, evidence))
            assertArrayEquals("Fresh READ-only inspection must preserve source bytes",
                original, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue("An observational hash is not source identity or replay authority",
                evidence.toString() == "ProtectedTransferredByteEvidence(redacted)")
        } finally {
            runCatching { resolver.delete(uri, null, null) }
        }
    }


    @Test fun canceledDestinationByteReviewDoesNotBecomeProviderFailure() {
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext.contentResolver
        // No installed provider at this authority. The first parent listing
        // and protected child identity are synthetic, read-only observations;
        // opening the missing content stream must fail without filesystem I/O.
        val parent = android.net.Uri.parse(
            "content://expository.uninstalled.review.provider/tree/fresh-parent")
        val created = dev.expository.transfer.journal.RecoveryCreatedChildIdentity
            .capture(parent, "fresh-parent", "new-file-43", "Episode.bin",
                directory = false)
        val listing = TreeListing(
            listOf(TreeEntry("new-file-43", "Episode.bin", false, 0L)), true)
        val sent = dev.expository.transfer.journal.ProtectedTransferredByteEvidence(
            0L, "0".repeat(64))
        val inspector = dev.expository.transfer.journal.RecoveryDestinationByteInspector
        assertEquals(
            dev.expository.transfer.journal.CreatedChildRelation
                .SAME_RECORDED_CHILD_IDENTITY,
            dev.expository.transfer.journal.RecoveryCreatedChildIdentity
                .compare(created, parent, listing))
        // A genuinely active review still reports an unavailable provider.
        assertEquals(
            dev.expository.transfer.journal.ManualDestinationBytes
                .DESTINATION_READ_UNAVAILABLE,
            inspector.inspect(resolver, parent, listing, created, sent))
        // The operator can cancel while a provider read is failing.
        // That race must not be reported as a current provider failure.
        val checks = java.util.concurrent.atomic.AtomicInteger(0)
        assertEquals(
            dev.expository.transfer.journal.ManualDestinationBytes
                .INSPECTION_CANCELED,
            inspector.inspect(resolver, parent, listing, created, sent,
                continueRequested = { checks.incrementAndGet() <= 2 }))
        assertTrue("Cancellation must be rechecked after provider failure",
            checks.get() >= 3)
        assertEquals(
            dev.expository.transfer.journal.ManualDestinationBytes
                .INSPECTION_CANCELED,
            inspector.inspect(resolver, parent, listing, created, sent,
                continueRequested = { false }))
    }

    @Test fun orphanSidecarsCannotCrowdOutARealInterruptedAttempt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "recovery-priority-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "f".repeat(64)
        val journal = DurableAttemptJournal(
            dev.expository.transfer.journal.AndroidAtomicAttemptStorage(
                isolated, digest))
        try {
            journal.confirm(digest)
            journal.beginAttempt(digest, "real_attempt_has_priority")
            val privateJournal = File(sandbox, "transfer_attempt_" + digest)
            val originalJournal = privateJournal.readBytes()
            // These 40 validly named but orphaned encrypted-context records
            // are inspection anomalies, not evidence of a transfer attempt.
            // They should NEVER consume all 32 review slots ahead of the
            // one real unresolved journal.
            val orphanFiles = (0 until 40).map { index ->
                File(sandbox, "transfer_recovery_context_v1_" +
                    index.toString(16).padStart(64, '0')).apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            }
            val summary = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, summary.requiresReview)
            assertEquals(40, summary.damagedOrUnrecognized)
            assertEquals(32, summary.reviewReferences.size)
            assertEquals(9, summary.additionalReviewCount)
            assertEquals(41L, summary.totalReferenceCount)
            assertEquals(0, summary.referenceWindow)
            // The remaining orphan hints can now be inspected on demand,
            // without promoting them or increasing the 32-row memory cap.
            val nextWindow = AttemptRecoveryIndex.recover(isolated, referenceWindow = 1)
            assertEquals(1, nextWindow.referenceWindow)
            assertEquals(41L, nextWindow.totalReferenceCount)
            assertEquals(9, nextWindow.reviewReferences.size)
            assertEquals(32, nextWindow.additionalReviewCount)
            assertTrue(nextWindow.reviewReferences.none { it.recordKey == digest })
            assertEquals(digest,
                summary.reviewReferences.first().recordKey)
            assertTrue(summary.reviewReferences.first().status
                .contains("Requires independent destination review"))
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(originalJournal, privateJournal.readBytes())
            orphanFiles.forEach { file ->
                assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            }
            // Repeated scans must not promote, consume or reorder the
            // genuinely interrupted attempt behind orphan-only hints.
            val second = AttemptRecoveryIndex.recover(isolated)
            assertEquals(digest, second.reviewReferences.first().recordKey)
            assertEquals(summary.additionalReviewCount, second.additionalReviewCount)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun hiddenReviewCountDoesNotDuplicateAnAlreadyVisibleSidecarWarning() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "hidden-recovery-count-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "d".repeat(64)
        val journal = DurableAttemptJournal(
            dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
        try {
            dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
                .recordNew(digest, dev.expository.transfer.journal.ProtectedRecoveryContext(
                    "SAF_DUAL_COPY", "Synthetic source", "Synthetic destination",
                    "test.provider"))
            journal.confirm(digest)
            journal.beginAttempt(digest, "hidden_review_count_test")
            // Corrupted encrypted evidence is visible as a warning on THIS
            // attempt. It must not invent another unlisted review record.
            File(sandbox, "transfer_source_bytes_v1_" + digest)
                .writeBytes(byteArrayOf(1, 2, 3))
            val shown = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, shown.requiresReview)
            assertEquals(1, shown.damagedOrUnrecognized)
            assertEquals(1, shown.reviewReferences.size)
            assertEquals(0, shown.additionalReviewCount)
            assertTrue(shown.inspectionReference(digest)?.transferredBytesWarning != null)

            // An unparseable sidecar filename has no trustworthy reference,
            // and therefore DOES contribute one undisplayed anomaly.
            File(sandbox, "transfer_source_bytes_v1_invalid").writeBytes(byteArrayOf(4))
            val malformed = AttemptRecoveryIndex.recover(isolated)
            assertEquals(2, malformed.damagedOrUnrecognized)
            assertEquals(1, malformed.additionalReviewCount)

            // An orphan with a well-formed digest gets its own displayed
            // reference; it must not also inflate the unlisted count.
            File(sandbox, "transfer_created_child_v1_" + "e".repeat(64))
                .writeBytes(byteArrayOf(5))
            val orphan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(3, orphan.damagedOrUnrecognized)
            assertEquals(2, orphan.reviewReferences.size)
            assertEquals(1, orphan.additionalReviewCount)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun completedByteWitnessRemainsEncryptedReadOnlyAndNeverResolvesJournal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "completed-bytes-evidence-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val payload = "private source bytes were closed".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        val hex = hash.joinToString("") { "%02x".format(it) }
        val expected = dev.expository.transfer.journal.ProtectedTransferredByteEvidence(
            payload.size.toLong(), hex)
        try {
            val store = dev.expository.transfer.journal.ProtectedTransferredBytesEvidenceStore(isolated)
            assertEquals(null, store.read(digest))
            assertTrue(runCatching { store.recordNew(digest, -1, hash) }.isFailure)
            assertTrue(runCatching { store.recordNew(digest, 0, byteArrayOf(1)) }.isFailure)
            store.recordNew(digest, payload.size.toLong(), hash)
            assertEquals(expected, store.read(digest))
            assertTrue(!expected.toString().contains(hex))
            assertTrue(runCatching {
                store.recordNew(digest, payload.size.toLong(), hash)
            }.isFailure)
            val record = File(sandbox, "transfer_source_bytes_v1_" + digest)
            val encrypted = record.readBytes()
            val encoded = encrypted.toString(Charsets.ISO_8859_1)
            assertTrue(!encoded.contains(hex) && !encoded.contains("private source"))
            assertEquals(1, AttemptRecoveryIndex.recover(isolated).damagedOrUnrecognized)
            assertEquals(0, AttemptRecoveryIndex.recover(isolated).verifiedReceipts)
            val contextStore = dev.expository.transfer.journal.ProtectedRecoveryContextStore(
                isolated)
            contextStore.recordNew(digest,
                dev.expository.transfer.journal.ProtectedRecoveryContext(
                    "SAF_DUAL_COPY", "Source", "Destination", "test.provider"))
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "source_bytes_test")
            val review = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, review.requiresReview)
            assertEquals(0, review.damagedOrUnrecognized)
            assertEquals(expected, review.inspectionReference(digest)?.transferredBytes)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(encrypted, record.readBytes())
            assertTrue(runCatching { journal.beginAttempt(digest, "blind_replay") }.isFailure)

            val sidecar = File(record.path + ".new")
            sidecar.writeBytes(byteArrayOf(9, 8, 7))
            val concurrentScan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, concurrentScan.requiresReview)
            assertEquals(1, concurrentScan.damagedOrUnrecognized)
            assertEquals(null, concurrentScan.inspectionReference(digest)?.transferredBytes)
            assertTrue(concurrentScan.inspectionReference(digest)
                ?.transferredBytesWarning?.contains("unreadable") == true)
            assertArrayEquals(encrypted, record.readBytes())
            assertArrayEquals(byteArrayOf(9, 8, 7), sidecar.readBytes())
            sidecar.delete()

            record.writeBytes(encrypted.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            })
            assertTrue(runCatching { store.read(digest) }.isFailure)
            val tampered = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, tampered.requiresReview)
            assertEquals(1, tampered.damagedOrUnrecognized)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun createdChildWitnessIsEncryptedBoundToOneJournalAndReadOnly() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "created-child-evidence-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val tree = android.net.Uri.parse("content://test.copy.target.scoped/tree/target-root")
        val wrongParent = android.net.Uri.parse(
            "content://test.copy.target.scoped/tree/another-parent")
        val identity = dev.expository.transfer.journal.RecoveryCreatedChildIdentity
        val saved = identity.capture(tree, "target-root", "new-child-42",
            "Episode.bin", directory = false)
        fun listing(id: String) = TreeListing(
            listOf(TreeEntry(id, "Episode.bin", false, 123L)), true)
        try {
            val store = dev.expository.transfer.journal.ProtectedCreatedChildWitnessStore(
                isolated)
            assertEquals(null, store.read(digest))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.NO_WITNESS,
                identity.compare(null, tree, listing("new-child-42")))
            store.recordNew(digest, saved)
            assertEquals(saved, store.read(digest))
            assertTrue(runCatching { store.recordNew(digest, saved) }.isFailure)
            val orphan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, orphan.damagedOrUnrecognized)
            assertTrue(orphan.reviewReferences.single().status
                .contains("Created-child witness has no matching attempt journal"))
            val raw = File(sandbox, "transfer_created_child_v1_" + digest)
            val encrypted = raw.readBytes()
            val ciphertext = encrypted.toString(Charsets.ISO_8859_1)
            assertTrue(!ciphertext.contains("Episode.bin") &&
                !ciphertext.contains("new-child-42") &&
                !ciphertext.contains(saved.childFingerprint))
            assertTrue(!saved.toString().contains("Episode.bin"))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.SAME_RECORDED_CHILD_IDENTITY,
                identity.compare(saved, tree, listing("new-child-42")))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.DIFFERENT_CHILD_IDENTITY,
                identity.compare(saved, tree, listing("replaced-child")))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.DIFFERENT_DIRECT_PARENT,
                identity.compare(saved, wrongParent, listing("new-child-42")))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.INCOMPLETE_PARENT, identity.compare(
                saved, tree, TreeListing(emptyList(), false, true)))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.MISSING_OR_AMBIGUOUS_CHILD, identity.compare(
                saved, tree, TreeListing(emptyList(), true)))
            assertEquals(dev.expository.transfer.journal.CreatedChildRelation.MISSING_OR_AMBIGUOUS_CHILD, identity.compare(
                saved, tree, TreeListing(listOf(
                    TreeEntry("new-child-42", "Episode.bin", false, 123L),
                    TreeEntry("other", "episode.bin", false, 1L)), true)))

            dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
                .recordNew(digest, dev.expository.transfer.journal.ProtectedRecoveryContext(
                    "SAF_DUAL_COPY", "Private source", "Private target",
                    "test.copy.target.scoped"))
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "created_child_test")
            val observed = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, observed.requiresReview)
            assertEquals(saved, observed.inspectionReference(digest)?.createdChild)
            assertEquals(0, observed.damagedOrUnrecognized)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(encrypted, raw.readBytes())
            assertTrue(runCatching {
                journal.beginAttempt(digest, "no_duplicate_write")
            }.isFailure)

            val sidecar = File(raw.path + ".new")
            sidecar.writeBytes(byteArrayOf(1, 2, 3))
            val interrupted = AttemptRecoveryIndex.recover(isolated)
            assertEquals(null, interrupted.inspectionReference(digest)?.createdChild)
            assertTrue(interrupted.inspectionReference(digest)
                ?.createdChildWarning?.contains("unreadable") == true)
            assertArrayEquals(encrypted, raw.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), sidecar.readBytes())
            sidecar.delete()

            raw.writeBytes(encrypted.copyOf().also { it[it.lastIndex] =
                (it.last().toInt() xor 1).toByte() })
            assertTrue(runCatching { store.read(digest) }.isFailure)
            assertEquals(1, AttemptRecoveryIndex.recover(isolated).requiresReview)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedTreeRootWitnessDistinguishesSameProviderDifferentRoot() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "root-witness-recovery-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val original = android.net.Uri.parse(
            "content://synthetic.identity.provider/tree/account%3ARoot-One")
        val same = android.net.Uri.parse(
            "content://synthetic.identity.provider/tree/account%3ARoot-One")
        val otherRoot = android.net.Uri.parse(
            "content://synthetic.identity.provider/tree/account%3ARoot-Two")
        val otherAuthority = android.net.Uri.parse(
            "content://different.identity.provider/tree/account%3ARoot-One")
        val invalid = android.net.Uri.parse("content://synthetic.identity.provider/document/not-a-tree")
        val identity = dev.expository.transfer.journal.RecoveryTreeRootIdentity
        val fingerprint = identity.fingerprint(original)
        assertEquals(64, fingerprint.length)
        assertEquals(dev.expository.transfer.journal.RecoveryTreeRootRelation.SAME_RECORDED_ROOT, identity.compare(fingerprint, same))
        assertEquals(dev.expository.transfer.journal.RecoveryTreeRootRelation.DIFFERENT_RECORDED_ROOT,
            identity.compare(fingerprint, otherRoot))
        assertEquals(dev.expository.transfer.journal.RecoveryTreeRootRelation.DIFFERENT_RECORDED_ROOT,
            identity.compare(fingerprint, otherAuthority))
        assertEquals(dev.expository.transfer.journal.RecoveryTreeRootRelation.NO_SAVED_ROOT, identity.compare(null, original))
        assertEquals(dev.expository.transfer.journal.RecoveryTreeRootRelation.UNDETERMINED, identity.compare("invalid", original))
        assertEquals(dev.expository.transfer.journal.RecoveryTreeRootRelation.UNDETERMINED, identity.compare(fingerprint, invalid))

        val digest = "c".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_DUAL_COPY", "Source", "Selected Android root",
            "synthetic.identity.provider", fingerprint)
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            val opaque = File(sandbox, "transfer_recovery_context_v1_" + digest)
                .readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(!opaque.contains("account:Root-One") &&
                !opaque.contains("synthetic.identity.provider") &&
                !opaque.contains(fingerprint))
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "root_witness_test")
            val recovered = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, recovered.requiresReview)
            assertEquals(fingerprint,
                recovered.inspectionReference(digest)?.context?.destinationTreeRootFingerprint)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            // Same recorded tree root is only a correspondence signal.
            // Neither the original bytes nor a retry authorization exist.
            assertTrue(runCatching {
                journal.beginAttempt(digest, "replay_attempt_blocked")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedOriginalSourceDocumentWitnessMatchesOnlyFreshDocumentIdentity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "source-id-recovery-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val identity = dev.expository.transfer.journal.RecoverySourceDocumentIdentity
        val original = android.net.Uri.parse(
            "content://synthetic.source.provider/document/account%3Aoriginal")
        val sameFromTree = android.net.Uri.parse(
            "content://synthetic.source.provider/tree/account%3ARoot/document/account%3Aoriginal")
        val another = android.net.Uri.parse(
            "content://synthetic.source.provider/document/account%3Aunrelated")
        val anotherProvider = android.net.Uri.parse(
            "content://different.source.provider/document/account%3Aoriginal")
        val undocument = android.net.Uri.parse(
            "content://synthetic.source.provider/item/account%3Aoriginal")
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val saved = requireNotNull(identity.fingerprint(original))
        assertEquals(64, saved.length)
        assertEquals(saved, identity.fingerprint(sameFromTree))
        assertEquals(null, identity.fingerprint(undocument))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.SAME_RECORDED_DOCUMENT_ID,
            identity.compare(saved, sameFromTree, read))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.DIFFERENT_RECORDED_DOCUMENT_ID,
            identity.compare(saved, another, read))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.DIFFERENT_RECORDED_DOCUMENT_ID,
            identity.compare(saved, anotherProvider, read))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.NO_RECORDED_DOCUMENT_ID,
            identity.compare(null, original, read))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.INDETERMINATE,
            identity.compare(saved, original, 0))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.INDETERMINATE,
            identity.compare(saved, undocument, read))
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.INDETERMINATE,
            identity.compare("not-a-digest", original, read))
        // The newer inspector requires an actually readable provider. An
        // invented content URI does not pass as source reauthorization.
        assertEquals(dev.expository.transfer.journal.RecoverySourceDocumentRelation.INDETERMINATE,
            identity.inspect(app.contentResolver, original, read, saved))
        val malformed = android.net.Uri.parse(
            "content://synthetic.source.provider/document/%EF%BF%BD")
        assertTrue(identity.fingerprint(malformed) != saved)
        val digest = "b".repeat(64)
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
                "SAF_PICKER_COPY", "Original source", "Created destination",
                "synthetic.destination.provider", sourceDocumentFingerprint = saved)
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            val raw = File(sandbox, "transfer_recovery_context_v1_" + digest)
                .readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(!raw.contains(saved) && !raw.contains("account:original") &&
                !raw.contains("synthetic.destination.provider"))
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "source_id_observation")
            val index = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, index.requiresReview)
            assertEquals(saved,
                index.inspectionReference(digest)?.context?.sourceDocumentFingerprint)
            assertEquals(saved,
                index.sourceInspectionReference(digest)?.context?.sourceDocumentFingerprint)
            // An old incomplete Copy with only original source-ID proof can
            // offer source review without a completed transfer byte hash.
            assertEquals(null, index.sourceInspectionReference(digest)?.transferredBytes)
            // A missing destination context can still leave authenticated
            // byte evidence useful for independent source review.
            val bytesOnly = dev.expository.transfer.journal.RecoveryReviewReference(
                "test-ref", "Unresolved", recordKey = "e".repeat(64),
                transferredBytes =
                    dev.expository.transfer.journal.ProtectedTransferredByteEvidence(
                        4L, "f".repeat(64)))
            val withoutDestinationHint = dev.expository.transfer.journal.RecoveryCounts(
                1, 0, 0, 0, listOf(bytesOnly))
            assertEquals(null,
                withoutDestinationHint.inspectionReference("e".repeat(64)))
            assertTrue(withoutDestinationHint.sourceInspectionReference("e".repeat(64)) != null)
            assertEquals(null,
                withoutDestinationHint.sourceInspectionReference("wrong"))
            // A matching source-ID witness can never resume an old attempt.
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertTrue(runCatching {
                journal.beginAttempt(digest, "repeat_forbidden")
            }.isFailure)
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
            assertTrue(runCatching {
                store.recordNew("c".repeat(64),
                    hint.copy(operation = "SAF_RECURSIVE_COPY"))
            }.isFailure)
            assertTrue(runCatching {
                store.recordNew("d".repeat(64),
                    hint.copy(sourceDocumentFingerprint = "invalid"))
            }.isFailure)
            assertTrue(!File(sandbox,
                "transfer_recovery_context_v1_" + "c".repeat(64)).exists())
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun freshProviderMetadataComparisonDetectsChangeWithoutAnyWrite() {
        // The source DocumentsProvider lives inside the disposable Android
        // test APK. Real ContentResolver calls, no fabricated query results
        // and no production provider, credential or URI grant persistence.
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val tree = android.net.Uri.parse(
            "content://test.source.scoped/tree/source-root")
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, "clip")
        val identity = dev.expository.transfer.journal.RecoverySourceDocumentIdentity
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        requireNotNull(resolver.call(tree, "reset", null, null))
        try {
            val captured = requireNotNull(identity.captureMetadata(resolver, document))
            assertEquals(64, captured.length)
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .SAME_RECORDED_METADATA,
                identity.inspectMetadata(resolver, document, read, captured))
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .INDETERMINATE,
                identity.inspectMetadata(resolver, document, 0, captured))
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .INDETERMINATE,
                identity.inspectMetadata(resolver, document, read, captured) { false })
            requireNotNull(resolver.call(tree, "changedName", null, null))
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .DIFFERENT_RECORDED_METADATA,
                identity.inspectMetadata(resolver, document, read, captured))
            assertEquals("Provider ID may survive a filename revision",
                identity.fingerprint(document),
                identity.fingerprint(document))
            // No journal or protected context is created by inspection.
        } finally {
            requireNotNull(resolver.call(tree, "reset", null, null))
        }
    }

    @Test fun protectedV4SourceMetadataIsEncryptedBackwardCompatibleAndNonExecutable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "metadata-v4-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val source = android.net.Uri.parse(
            "content://synthetic.recovery.provider/document/id%3Aone")
        val identity = dev.expository.transfer.journal.RecoverySourceDocumentIdentity
        val sourceId = requireNotNull(identity.fingerprint(source))
        val metadata = "a".repeat(64)
        val base = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", "Original", "Created", "synthetic",
            sourceDocumentFingerprint = sourceId,
            sourceMetadataFingerprint = metadata)
        val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
        try {
            val v2Key = "1".repeat(64)
            val v3Key = "2".repeat(64)
            val v4Key = "3".repeat(64)
            store.recordNew(v2Key, base.copy(
                sourceDocumentFingerprint = null, sourceMetadataFingerprint = null))
            store.recordNew(v3Key, base.copy(sourceMetadataFingerprint = null))
            store.recordNew(v4Key, base)
            assertEquals(null, store.read(v2Key)?.sourceDocumentFingerprint)
            assertEquals(null, store.read(v3Key)?.sourceMetadataFingerprint)
            assertEquals(base, store.read(v4Key))
            val encrypted = File(sandbox,
                "transfer_recovery_context_v1_" + v4Key).readBytes()
            val raw = String(encrypted, Charsets.ISO_8859_1)
            assertTrue(!raw.contains(metadata) && !raw.contains(sourceId) &&
                !raw.contains("Original") && !raw.contains("synthetic.recovery.provider"))
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .INDETERMINATE,
                identity.inspectMetadata(app.contentResolver, source,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION, metadata))
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .INDETERMINATE,
                identity.inspectMetadata(app.contentResolver, source, 0, metadata))
            assertEquals(
                dev.expository.transfer.journal.RecoverySourceMetadataRelation
                    .NO_RECORDED_METADATA,
                identity.inspectMetadata(app.contentResolver, source,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION, null))
            assertEquals(null, identity.captureMetadata(app.contentResolver, source))
            val rejected = listOf(
                base.copy(sourceDocumentFingerprint = null),
                base.copy(sourceMetadataFingerprint = "malformed"),
                base.copy(sourceMetadataFingerprint = "A".repeat(64)),
                base.copy(operation = "SAF_RECURSIVE_COPY"),
                base.copy(sourceMetadataFingerprint = "b".repeat(65))
            )
            rejected.forEachIndexed { index, hint ->
                val id = (index + 4).toString().repeat(64)
                assertTrue(runCatching { store.recordNew(id, hint) }.isFailure)
                assertTrue(!File(sandbox,
                    "transfer_recovery_context_v1_" + id).exists())
            }
            // The new metadata witness cannot reenter the old write attempt.
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, v4Key))
            journal.confirm(v4Key)
            journal.beginAttempt(v4Key, "metadata_v4_test")
            val index = AttemptRecoveryIndex.recover(isolated)
            assertEquals(metadata,
                index.sourceInspectionReference(v4Key)?.context?.sourceMetadataFingerprint)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertTrue(runCatching {
                journal.beginAttempt(v4Key, "replay_forbidden")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun legacyEncryptedV1ContextRemainsReadableWithoutAnInventedRoot() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "legacy-root-hint-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val bootstrapKey = "a".repeat(64)
        val legacyKey = "b".repeat(64)
        val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
        val original = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", "Legacy source", "Legacy target", "legacy.provider")
        try {
            // Create the same app-private Keystore key as a genuine V2 write.
            store.recordNew(bootstrapKey, original)
            val keystore = java.security.KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
            val key = keystore.getKey(
                "expository-transfer-recovery-context-v1", null) as javax.crypto.SecretKey
            val plain = java.io.ByteArrayOutputStream().also { buffer ->
                java.io.DataOutputStream(buffer).use { out ->
                    out.writeByte(1)
                    out.writeUTF(original.operation)
                    out.writeUTF(original.sourceLabel)
                    out.writeUTF(original.destinationLabel)
                    out.writeUTF(original.destinationProvider)
                }
            }.toByteArray()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val aad = ("ExpositoryTransfer.RecoveryContext.V1" + 0.toChar() +
                app.packageName + 0.toChar() + legacyKey).toByteArray(Charsets.UTF_8)
            cipher.updateAAD(aad)
            val record = "EXPRCTX1".toByteArray(Charsets.US_ASCII) +
                cipher.iv + cipher.doFinal(plain)
            File(sandbox, "transfer_recovery_context_v1_" + legacyKey)
                .writeBytes(record)
            plain.fill(0)
            assertEquals(original, store.read(legacyKey))
            assertEquals(null, store.read(legacyKey)?.destinationTreeRootFingerprint)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun identicalDisplaySuffixesCannotBindPickerToTheWrongJournal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "same-suffix-recovery-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        // The visible suffix is intentionally identical. Neither a picker
        // ticket nor the protected destination hint may use it as identity.
        val suffix = "deadbeef0123"
        val firstKey = "a".repeat(52) + suffix
        val secondKey = "b".repeat(52) + suffix
        val firstHint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_DUAL_COPY", "First source", "First target", "first.provider")
        val secondHint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_DUAL_COPY", "Second source", "Second target", "second.provider")
        try {
            val hints = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            hints.recordNew(firstKey, firstHint)
            hints.recordNew(secondKey, secondHint)
            for (key in listOf(firstKey, secondKey)) {
                val journal = DurableAttemptJournal(
                    dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, key))
                journal.confirm(key)
                journal.beginAttempt(key, "same_suffix_test_" + key.take(8))
            }
            val recovery = AttemptRecoveryIndex.recover(isolated)
            assertEquals(2, recovery.requiresReview)
            assertEquals(2, recovery.reviewReferences.size)
            assertEquals(setOf(suffix),
                recovery.reviewReferences.map { it.opaqueSuffix }.toSet())
            assertEquals(firstHint, recovery.inspectionReference(firstKey)?.context)
            assertEquals(secondHint, recovery.inspectionReference(secondKey)?.context)
            // A display suffix and a fabricated otherwise-valid key are not
            // authorized to inspect either context.
            assertEquals(null, recovery.inspectionReference(suffix))
            assertEquals(null,
                recovery.inspectionReference("c".repeat(52) + suffix))
            assertTrue(!recovery.toString().contains(firstKey))
            assertTrue(!recovery.toString().contains(secondKey))
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun recoveryBoardDescribesEachUnverifiedJournalStageWithoutMutatingIt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "journal-stage-review-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val states = listOf(JournalState.ATTEMPT_RECORDED,
            JournalState.WRITE_ACKNOWLEDGED, JournalState.UNCERTAIN)
        val keys = List(states.size) {
            UUID.randomUUID().toString().replace("-", "").repeat(2)
        }
        try {
            val journals = keys.mapIndexed { index, key ->
                DurableAttemptJournal(
                    dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, key)
                ).also { journal ->
                    journal.confirm(key)
                    journal.beginAttempt(key, "stage_review_attempt_$index")
                    when (states[index]) {
                        JournalState.WRITE_ACKNOWLEDGED ->
                            journal.markWriteAcknowledged(key)
                        JournalState.UNCERTAIN -> journal.markUncertain(key)
                        else -> Unit
                    }
                }
            }
            val summary = AttemptRecoveryIndex.recover(isolated)
            assertEquals(3, summary.requiresReview)
            assertEquals(0, summary.verifiedReceipts)
            assertEquals(3, summary.reviewReferences.size)
            val status = keys.map { key ->
                summary.reviewReferences.single {
                    it.opaqueSuffix == key.takeLast(12)
                }.status
            }
            assertTrue(status.all {
                it.contains("Requires independent destination review")
            })
            assertTrue(status[0].contains("whether any destination write occurred is unknown"))
            assertTrue(status[1].contains("write acknowledged") &&
                status[1].contains("bytes are not verified"))
            assertTrue(status[2].contains("do not retry"))
            // A status refresh must be purely observational. In particular,
            // neither the ACK nor the started state is silently rewritten.
            assertEquals(states, journals.map { it.current()?.state })
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun privateRecoveryDisplayObjectsNeverPrintUserContent() {
        val source = "PRIVATE SOURCE 40977"
        val destination = "PRIVATE DESTINATION 80412"
        val provider = "secret.provider.account"
        val child = "PRIVATE CHILD NAME 70132"
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_DUAL_COPY", source, destination, provider)
        val reference = dev.expository.transfer.journal.RecoveryReviewReference(
            "abcdef012345", "Requires independent destination review",
            context = hint,
            contextWarning = "Sensitive provider detail " + provider)
        val summary = dev.expository.transfer.journal.RecoveryCounts(
            1, 0, 0, 0, listOf(reference))
        val listing = dev.expository.transfer.journal.ManualRecoveryListing(
            1, true, listOf(child))
        for (text in listOf(hint.toString(), reference.toString(),
                summary.toString(), listing.toString())) {
            check(!text.contains(source) && !text.contains(destination) &&
                !text.contains(provider) && !text.contains(child) &&
                !text.contains("abcdef012345"))
        }
        // Redaction of debug strings must not remove information from the
        // actual operator-facing UI or alter immutable value equality.
        assertEquals(source, hint.sourceLabel)
        assertEquals(destination, reference.context?.destinationLabel)
        assertEquals(provider, reference.context?.destinationProvider)
        assertEquals(listOf(child), listing.sampleNames)
        assertEquals(hint, hint.copy())
    }

    @Test fun manualRecoveryInspectionRequiresActualTreePickerReadGrant() {
        val valid = android.net.Uri.parse("content://private.synthetic/tree/root")
        val nonTree = android.net.Uri.parse("content://private.synthetic/document/item")
        val unscoped = android.net.Uri.parse("file:///sdcard/private.txt")
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertTrue(dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(valid, read))
        assertTrue(dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(valid, read or write))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(valid, write))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(nonTree, read))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(unscoped, read))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(null, read))
        // None of these negative observations may open a provider or modify
        // the neutral journal state. The folder chosen during manual review
        // is never proof it was the old destination.
    }

    @Test fun missingDirectoryListingIsAnInspectionErrorNotZeroUncertain() {
        val result = runCatching { AttemptRecoveryIndex.journalDigests(null) }
        assertTrue(result.isFailure)
        assertEquals("JOURNAL_DIRECTORY_UNREADABLE", result.exceptionOrNull()?.message)
    }

    @Test fun validBackupsAreCountedOnceAndUnrelatedFilesIgnored() {
        val digest = "a".repeat(64)
        val index = AttemptRecoveryIndex.journalDigests(
            listOf("transfer_attempt_" + digest, "transfer_attempt_" + digest + ".bak",
                "transfer_attempt_" + digest + ".new",
                "transfer_attempt_invalid", "other_app_file")
        )
        assertEquals(listOf(digest), index)
        assertEquals(1, AttemptRecoveryIndex.malformedJournalCount(
            listOf("transfer_attempt_invalid", "unrelated_file",
                "transfer_attempt_" + digest + ".new")))
        assertEquals(emptyList<String>(), AttemptRecoveryIndex.journalDigests(emptyList()))
    }

    @Test fun orphanedEncryptedContextAndMalformedHintsDemandManualInspection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "orphan-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val orphanDigest = "7".repeat(64)
        val otherDigest = "8".repeat(64)
        val names = listOf(
            "transfer_recovery_context_v1_" + orphanDigest,
            "transfer_recovery_context_v1_" + orphanDigest + ".bak",
            "transfer_recovery_context_v1_" + orphanDigest + ".new",
            "transfer_recovery_context_v1_" + otherDigest,
            "transfer_recovery_context_v1_bad-digest",
            "unrelated_app_file"
        )
        assertEquals(setOf(orphanDigest, otherDigest),
            AttemptRecoveryIndex.contextDigests(names).toSet())
        assertEquals(1, AttemptRecoveryIndex.malformedContextCount(names))
        try {
            val saved = dev.expository.transfer.journal.ProtectedRecoveryContext(
                "SAF_PICKER_COPY", "Sensitive synthetic source",
                "Sensitive synthetic destination", "synthetic.provider")
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(orphanDigest, saved)
            // A broken hint is counted separately even without a readable
            // journal; no raw orphan label is displayed or deserialized.
            File(sandbox, "transfer_recovery_context_v1_bad-digest")
                .writeText("invalid record")
            val orphan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, orphan.requiresReview)
            assertEquals(0, orphan.verifiedReceipts)
            assertEquals(0, orphan.notStarted)
            assertEquals(2, orphan.damagedOrUnrecognized)
            assertEquals(1, orphan.reviewReferences.size)
            assertEquals(orphanDigest.takeLast(12),
                orphan.reviewReferences.single().opaqueSuffix)
            assertEquals(null, orphan.reviewReferences.single().context)
            assertTrue(orphan.reviewReferences.single().status.contains(
                "no matching attempt journal"))
            assertTrue(orphan.reviewReferences.single().contextWarning
                ?.contains("Do not retry") == true)
            // A correctly matched, separately journaled hint is not an
            // orphan. Once an attempt starts, the neutral record governs it.
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(
                    isolated, orphanDigest))
            journal.confirm(orphanDigest)
            journal.beginAttempt(orphanDigest, "orphan_review_test")
            val journaled = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, journaled.requiresReview)
            assertEquals(1, journaled.damagedOrUnrecognized)
            assertEquals(saved, journaled.reviewReferences.single().context)
            assertTrue(runCatching {
                journal.beginAttempt(orphanDigest, "unapproved_retry")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun twoRenameStepsHaveSeparateRealAtomicJournalsAndRestartReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val digest = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val firstKey = AndroidRenameStepStorage.privateKey(digest, 0)
        val secondKey = AndroidRenameStepStorage.privateKey(digest, 1)
        assertTrue(firstKey != secondKey)
        val first = File(context.noBackupFilesDir, "transfer_attempt_" + firstKey)
        val second = File(context.noBackupFilesDir, "transfer_attempt_" + secondKey)
        try {
            val journalA = DurableAttemptJournal(
                AndroidRenameStepStorage.forStep(context, digest, 0))
            val journalB = DurableAttemptJournal(
                AndroidRenameStepStorage.forStep(context, digest, 1))
            journalA.confirm(digest)
            journalA.beginAttempt(digest, UUID.randomUUID().toString())
            journalA.markWriteAcknowledged(digest)
            journalA.markVerified(digest)
            assertEquals(JournalState.VERIFIED, journalA.current()?.state)
            // A second mutation is independent of the first terminal record.
            journalB.confirm(digest)
            journalB.beginAttempt(digest, UUID.randomUUID().toString())
            assertEquals(JournalState.ATTEMPT_RECORDED, journalB.current()?.state)
            val activeRecords = AttemptRecoveryIndex.journalDigests(
                context.noBackupFilesDir.listFiles()?.map { it.name })
            assertTrue(firstKey in activeRecords && secondKey in activeRecords)
            // Simulate a process restart: no uncertain second write may replay.
            val reconstructed = DurableAttemptJournal(
                AndroidRenameStepStorage.forStep(context, digest, 1))
            assertEquals(JournalState.UNCERTAIN,
                reconstructed.recoverAfterRestart()?.state)
            assertEquals(JournalState.VERIFIED,
                DurableAttemptJournal(AndroidRenameStepStorage.forStep(context, digest, 0))
                    .current()?.state)
            assertTrue(runCatching {
                reconstructed.beginAttempt(digest, UUID.randomUUID().toString())
            }.isFailure)
            assertTrue(first.exists() && second.exists())
        } finally {
            AtomicFile(first).delete()
            AtomicFile(second).delete()
        }
    }

    @Test fun renameStepPrivateKeyIsStableBoundedAndDoesNotExposeTargetNames() {
        val digest = "c".repeat(64)
        val k = AndroidRenameStepStorage.privateKey(digest, 5)
        assertTrue(k.matches(Regex("[0-9a-f]{64}")))
        assertEquals(k, AndroidRenameStepStorage.privateKey(digest, 5))
        assertTrue(k != AndroidRenameStepStorage.privateKey(digest, 6))
        assertTrue(k != AndroidRenameStepStorage.privateKey("d".repeat(64), 5))
        assertTrue(runCatching {
            AndroidRenameStepStorage.privateKey("server.example", 5)
        }.isFailure)
        assertTrue(runCatching {
            AndroidRenameStepStorage.privateKey(digest, -1)
        }.isFailure)
        assertTrue(runCatching {
            AndroidRenameStepStorage.privateKey(digest, 100_001)
        }.isFailure)
    }

    @Test fun encryptedRecoveryContextRequiresExplicitReviewAndRejectsTamper() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "private-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "1".repeat(64)
        val otherDigest = "2".repeat(64)
        val provider = "test.documents.provider"
        val sourceName = "PRIVATE Synthetic Source 49371"
        val destinationName = "PRIVATE Synthetic Destination 80265"
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", sourceName, destinationName, provider)
        val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
        val record = File(sandbox, "transfer_recovery_context_v1_" + digest)
        val wrong = File(sandbox, "transfer_recovery_context_v1_" + otherDigest)
        try {
            assertEquals(null, store.read(digest))
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            assertTrue(record.isFile)
            val ciphertext = record.readBytes()
            val visible = String(ciphertext, Charsets.ISO_8859_1)
            assertTrue(!visible.contains(sourceName) &&
                !visible.contains(destinationName) && !visible.contains(provider))
            // An existing record, even if unauthenticated or interrupted, may
            // never be silently reset to point to a different target.
            assertTrue(runCatching {
                store.recordNew(digest, hint.copy(destinationLabel = "replacement"))
            }.isFailure)
            assertEquals(hint, store.read(digest))
            // Ciphertext moved under another digest cannot authenticate:
            // AAD binds the operation to its exact private journal key.
            wrong.writeBytes(ciphertext)
            assertTrue(runCatching { store.read(otherDigest) }.isFailure)
            // Truncated/tampered hints never return invented labels.
            record.writeBytes(ciphertext.copyOf(ciphertext.size - 7))
            assertTrue(runCatching { store.read(digest) }.isFailure)
            // The original one-shot rule remains even after corruption.
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
            // A failed first commit that leaves only .new is reported as
            // damaged, never downgraded to "no prior record".
            record.delete()
            File(record.path + ".new").writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(runCatching { store.read(digest) }.isFailure)
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun journalStatusRefreshNeverRestoresOrDeletesAtomicSidecars() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "read-only-journal-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "e".repeat(64)
        val base = File(sandbox, "transfer_attempt_" + digest)
        val pending = File(base.path + ".new")
        val backup = File(base.path + ".bak")
        try {
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "recovery_read_only_fixture")
            val committed = base.readBytes()
            val pendingBytes = byteArrayOf(0, 1, 2, 3)
            pending.writeBytes(pendingBytes)
            val duringWrite = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, duringWrite.verifiedReceipts)
            assertTrue(duringWrite.damagedOrUnrecognized > 0)
            assertTrue(duringWrite.reviewReferences.any {
                it.opaqueSuffix == digest.takeLast(12) &&
                    it.status.contains("unreadable")
            })
            assertArrayEquals(committed, base.readBytes())
            assertArrayEquals(pendingBytes, pending.readBytes())

            pending.delete()
            val backupBytes = byteArrayOf(4, 5, 6, 7)
            backup.writeBytes(backupBytes)
            val duringBackup = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, duringBackup.verifiedReceipts)
            assertTrue(duringBackup.damagedOrUnrecognized > 0)
            assertArrayEquals(committed, base.readBytes())
            assertArrayEquals(backupBytes, backup.readBytes())

            // Removing only test-created sidecars returns to a readable
            // active journal; Refresh itself must not advance its state.
            backup.delete()
            val clean = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, clean.requiresReview)
            assertEquals(0, clean.damagedOrUnrecognized)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(committed, base.readBytes())
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedContextInspectionNeverRewritesAtomicSidecars() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "read-only-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "6".repeat(64)
        val base = File(sandbox, "transfer_recovery_context_v1_" + digest)
        val pending = File(base.path + ".new")
        val backup = File(base.path + ".bak")
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", "Fixture source", "Fixture destination",
            "fixture.provider")
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            val committedBytes = base.readBytes()
            assertEquals(hint, store.read(digest))
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "read_only_hint_fixture")

            // AtomicFile.openRead() deletes .new when base exists; a status
            // refresh must preserve BOTH artifacts for independent inspection.
            val pendingBytes = byteArrayOf(3, 1, 4, 1, 5)
            pending.writeBytes(pendingBytes)
            assertTrue(runCatching { store.read(digest) }.isFailure)
            val pendingScan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, pendingScan.requiresReview)
            assertTrue(pendingScan.reviewReferences.single().contextWarning != null)
            assertEquals(1, pendingScan.damagedOrUnrecognized)
            assertEquals(0, pendingScan.additionalReviewCount)
            assertArrayEquals(committedBytes, base.readBytes())
            assertArrayEquals(pendingBytes, pending.readBytes())
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)

            pending.delete()
            // AtomicFile.openRead() can rename .bak over an intact base;
            // even a malformed backup must remain untouched by inspection.
            val backupBytes = byteArrayOf(2, 7, 1, 8)
            backup.writeBytes(backupBytes)
            assertTrue(runCatching { store.read(digest) }.isFailure)
            val backupScan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, backupScan.requiresReview)
            assertTrue(backupScan.reviewReferences.single().contextWarning != null)
            assertEquals(1, backupScan.damagedOrUnrecognized)
            assertEquals(0, backupScan.additionalReviewCount)
            assertArrayEquals(committedBytes, base.readBytes())
            assertArrayEquals(backupBytes, backup.readBytes())
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)

            base.delete()
            assertTrue(runCatching { store.read(digest) }.isFailure)
            assertArrayEquals(backupBytes, backup.readBytes())
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedRecursiveHintIsDistinctFromPickerAndCannotAuthorizeReplay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "recursive-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "9".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_RECURSIVE_COPY", "Shows / Episode.bin",
            "Selected Android folder / Shows / Episode.bin",
            "private.scoped.provider")
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            assertTrue(runCatching {
                store.recordNew(digest, hint.copy(sourceLabel = "Other"))
            }.isFailure)
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "synthetic_rec_01")
            assertEquals(JournalState.UNCERTAIN, journal.recoverAfterRestart()?.state)
            val found = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, found.requiresReview)
            assertEquals(hint, found.reviewReferences.single().context)
            assertTrue(runCatching {
                journal.beginAttempt(digest, "synthetic_rec_02")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun dualFileCopyRecoveryHintIsOnlyAnEncryptedManualLocator() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "dual-copy-hint-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "d".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_DUAL_COPY", "Synthetic file.txt", "Folder: Selected destination",
            "synthetic.provider")
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            // A stopped transfer has only an uncertain neutral journal, not
            // an executable recovery request or an automatic retry token.
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "dual_copy_attempt_01")
            val recovered = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, recovered.requiresReview)
            assertEquals(hint, recovered.reviewReferences.single().context)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertTrue(runCatching {
                journal.beginAttempt(digest, "dual_copy_attempt_02")
            }.isFailure)
            assertTrue(runCatching {
                store.recordNew(digest, hint)
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedRecoveryHintIsOnlyManualContextForUncertainJournal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "review-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "3".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", "Synthetic Original.txt",
            "Synthetic Copy.txt", "synthetic.document.provider")
        try {
            dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
                .recordNew(digest, hint)
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "synthetic_attempt_01")
            val recovered = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, recovered.requiresReview)
            assertEquals(hint, recovered.reviewReferences.single().context)
            assertEquals(null, recovered.reviewReferences.single().contextWarning)
            assertEquals(JournalState.UNCERTAIN, journal.recoverAfterRestart()?.state)
            // The UI context can never authorize a fresh mutation or replay.
            assertTrue(runCatching {
                journal.beginAttempt(digest, "synthetic_attempt_02")
            }.isFailure)
            // Corrupting the supplementary encrypted hint must not hide the
            // authoritative write-ahead journal or allow a blind retry.
            val encrypted = File(sandbox, "transfer_recovery_context_v1_" + digest)
            encrypted.writeBytes(byteArrayOf(1, 2, 3))
            val damagedHint = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, damagedHint.requiresReview)
            assertEquals(null, damagedHint.reviewReferences.single().context)
            assertTrue(damagedHint.reviewReferences.single()
                .contextWarning?.contains("unreadable") == true)
            assertEquals(JournalState.UNCERTAIN,
                journal.recoverAfterRestart()?.state)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun recoveryReviewReferencesAreBoundedPrivateAndNeverAuthorizeReplay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "recovery-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "d".repeat(52) + "123456789abc"
        try {
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "isolated_test_attempt_01")
            val activeBytes = File(sandbox, "transfer_attempt_" + digest).readBytes()
            // A status refresh is allowed WHILE an actual writer is active.
            // It may report a review-required step, but must not downgrade
            // the durable journal and prevent a legitimate ACK/verification.
            val active = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, active.requiresReview)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(activeBytes,
                File(sandbox, "transfer_attempt_" + digest).readBytes())
            journal.markWriteAcknowledged(digest)
            val acknowledgedBytes = File(sandbox, "transfer_attempt_" + digest).readBytes()
            val record = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, record.requiresReview)
            assertEquals(0, record.verifiedReceipts)
            assertEquals(0, record.damagedOrUnrecognized)
            assertEquals(0, record.additionalReviewCount)
            assertEquals("123456789abc", record.reviewReferences.single().opaqueSuffix)
            assertEquals(
                "Requires independent destination review: " +
                    "write acknowledged; destination bytes are not verified",
                record.reviewReferences.single().status)
            assertEquals(JournalState.WRITE_ACKNOWLEDGED, journal.current()?.state)
            assertArrayEquals(acknowledgedBytes,
                File(sandbox, "transfer_attempt_" + digest).readBytes())
            assertTrue(runCatching {
                journal.beginAttempt(digest, "should_not_replay_01")
            }.isFailure)
            journal.markVerified(digest)
            val finished = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, finished.requiresReview)
            assertEquals(1, finished.verifiedReceipts)
            assertEquals(JournalState.VERIFIED, journal.current()?.state)
            // The indexed summary cannot expose any underlying destination,
            // user name, plaintext file path, source bytes, or account details.
            assertTrue(record.reviewReferences.single().opaqueSuffix.matches(
                Regex("[0-9a-f]{12}")))
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }


}
