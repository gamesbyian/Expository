package dev.expository.transfer.engine

import dev.expository.transfer.planner.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object CopyEngineSuite {
    private fun proof() = CapabilityProof(Support.SUPPORTED, "fake-grant", 999999L)
    private fun plan(): TransferPlan {
        val from = LocationRef(LocationKey(LocationKind.LOCAL_SAF, "provider-a", "root", "selected"),
            emptyList(), "source-v1", mapOf(Capability.READ to proof()))
        val to = LocationRef(LocationKey(LocationKind.LOCAL_SAF, "provider-b", "root", "new-doc",
            "picker-grant"), emptyList(), "created-v1", mapOf(Capability.WRITE to proof()))
        return OperationPlanner.transfer(TransferRequest(listOf(ItemRef(
            ItemKey("provider-a", "file-1", "version-1"), from,
            emptyList(), "fixture.bin", ItemKind.FILE
        )), to, destinationListing = DestinationListing("picker-created-v1", true,
            emptyList(), DestinationWitness.SAF_NEW_DOCUMENT_PICKER)), 100L)
    }
    @JvmStatic fun main(args: Array<String>) {
        val p = plan()
        check(p.executable)
        val bytes = ByteArray(3 * 65536 + 31) { (it % 251).toByte() }
        var passed = 0
        fun scenario(name: String, test: () -> Unit) {
            test(); println("PASS engine " + name); passed++
        }
        scenario("real streaming preserves bytes and source") {
            val out = ByteArrayOutputStream()
            val s = object : CopyStreams {
                override fun openSource(): InputStream = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream =
                    ByteArrayInputStream(out.toByteArray())
            }
            val receipt = ForegroundCopyEngine.copy(p, { p }, s)
            check(receipt.proof == CopyProof.BYTES_VERIFIED &&
                receipt.bytesWritten == bytes.size.toLong() &&
                out.toByteArray().contentEquals(bytes))
        }
        scenario("empty file read-back verified") {
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(ByteArray(0))
                override fun openNewDestination(): OutputStream = ByteArrayOutputStream()
                override fun openDestinationReadBack() = ByteArrayInputStream(ByteArray(0))
            })
            check(receipt.proof == CopyProof.BYTES_VERIFIED && receipt.bytesWritten == 0L)
        }
        scenario("nonseekable provider gets acknowledgement not hash proof") {
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = ByteArrayOutputStream()
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(receipt.proof == CopyProof.WRITE_ACKNOWLEDGED)
        }
        scenario("mismatch blocks verified receipt") {
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = ByteArrayOutputStream()
                override fun openDestinationReadBack() = ByteArrayInputStream(byteArrayOf(1,2))
            })
            check(receipt.proof == CopyProof.UNCERTAIN && receipt.reason == "READBACK_MISMATCH")
        }
        scenario("source stream ends in exception after partial output") {
            var outBytes = 0
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = object : ByteArrayInputStream(bytes) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (pos >= 65536) throw IOException("synthetic read error")
                        return super.read(b, off, len.coerceAtMost(65536 - pos))
                    }
                }
                override fun openNewDestination() = object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        super.write(b, off, len); outBytes += len
                    }
                }
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(outBytes > 0 && receipt.proof == CopyProof.UNCERTAIN)
        }
        scenario("source open denied means no mutation") {
            var touched = false
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = throw IOException("permission revoked")
                override fun openNewDestination(): OutputStream { touched = true; return ByteArrayOutputStream() }
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(!touched && receipt.proof == CopyProof.BLOCKED)
        }
        scenario("plan drift blocks without opening streams") {
            var touched = false
            val changed = p.copy(digest = "stale")
            val receipt = ForegroundCopyEngine.copy(p, { changed }, object : CopyStreams {
                override fun openSource(): InputStream { touched = true; return ByteArrayInputStream(bytes) }
                override fun openNewDestination(): OutputStream { touched = true; return ByteArrayOutputStream() }
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(!touched && receipt.proof == CopyProof.BLOCKED)
        }
        scenario("cancellation after first chunk remains uncertain") {
            var count = 0
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = ByteArrayOutputStream()
                override fun openDestinationReadBack(): InputStream? = null
            }, continueRequested = { count++ < 3 })
            check(receipt.proof == CopyProof.UNCERTAIN)
        }
        scenario("writer throws: never report verified") {
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = object : OutputStream() {
                    override fun write(b: Int): Unit = throw IOException()
                    override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException()
                }
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(receipt.proof == CopyProof.UNCERTAIN)
        }
        println("PASS engine total=" + passed + " proof=synthetic-JVM-only")
    }
}
