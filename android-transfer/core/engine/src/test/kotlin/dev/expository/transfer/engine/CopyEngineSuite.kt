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
        scenario("throwing progress display never corrupts copied bytes or proof") {
            val out = ByteArrayOutputStream()
            var notifications = 0
            val streams = object : CopyStreams {
                override fun openSource(): InputStream = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream =
                    ByteArrayInputStream(out.toByteArray())
            }
            val receipt = ForegroundCopyEngine.copy(p, { p }, streams,
                onProgress = {
                    notifications++
                    throw IllegalStateException("synthetic UI callback error")
                })
            check(notifications >= 1)
            check(receipt.proof == CopyProof.BYTES_VERIFIED)
            check(receipt.bytesWritten == bytes.size.toLong())
            check(out.toByteArray().contentEquals(bytes))
        }
        scenario("canceled progress observer halts subsequent streaming") {
            val out = ByteArrayOutputStream()
            var notifications = 0
            var readbackOpened = false
            val streams = object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream {
                    readbackOpened = true
                    return ByteArrayInputStream(out.toByteArray())
                }
            }
            val receipt = ForegroundCopyEngine.copy(p, { p }, streams,
                onProgress = {
                    notifications++
                    throw java.util.concurrent.CancellationException(
                        "synthetic canceled observer")
                })
            check(notifications == 1)
            check(out.size() == 65_536)
            check(!readbackOpened)
            check(receipt.proof == CopyProof.UNCERTAIN)
            check(receipt.bytesWritten == out.size().toLong())
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

        scenario("destination opening may create a document before throwing") {
            var created = false
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream {
                    // Android DocumentsProvider.createDocument may commit the
                    // new document, then fail before an output stream exists.
                    created = true
                    throw IOException("synthetic provider lost create response")
                }
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(created && receipt.proof == CopyProof.UNCERTAIN &&
                receipt.bytesWritten == 0L &&
                receipt.reason == "PARTIAL_OR_UNCERTAIN_WRITE")
        }
        scenario("unobservable destination-open denial is uncertain too") {
            var opened = false
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream {
                    opened = true
                    throw IOException("provider call outcome unknowable")
                }
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(opened && receipt.proof == CopyProof.UNCERTAIN)
        }
        scenario("readback opening exception cannot imply clean acknowledgement") {
            val output = ByteArrayOutputStream()
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = output
                override fun openDestinationReadBack(): InputStream? =
                    throw IOException("destination readback provider offline")
            })
            check(receipt.proof == CopyProof.UNCERTAIN &&
                receipt.reason == "DESTINATION_READBACK_OPEN_FAILED" &&
                output.toByteArray().contentEquals(bytes))
        }
        scenario("preflight cancellation exception is blocked without side effects") {
            var touched = false
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream {
                    touched = true
                    return ByteArrayInputStream(bytes)
                }
                override fun openNewDestination(): OutputStream {
                    touched = true
                    return ByteArrayOutputStream()
                }
                override fun openDestinationReadBack(): InputStream? = null
            }, continueRequested = { throw IllegalStateException("lifecycle ended") })
            check(!touched && receipt.proof == CopyProof.BLOCKED &&
                receipt.reason == "NOT_STARTED")
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
        scenario("stop while blocking source read returns before any additional write") {
            val out = ByteArrayOutputStream()
            var permitted = true
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = object : ByteArrayInputStream(bytes) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        permitted = false // user pressed Stop during provider read
                        return n
                    }
                }
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream =
                    ByteArrayInputStream(out.toByteArray())
            }, continueRequested = { permitted })
            check(out.size() == 0 && receipt.proof == CopyProof.UNCERTAIN &&
                receipt.bytesWritten == 0L)
        }
        scenario("stop while waiting for final readback EOF cannot claim verified") {
            var permitted = true
            val out = ByteArrayOutputStream()
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream =
                    object : ByteArrayInputStream(out.toByteArray()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n == -1) permitted = false
                            return n
                        }
                    }
            }, continueRequested = { permitted })
            check(out.toByteArray().contentEquals(bytes) &&
                receipt.proof == CopyProof.UNCERTAIN &&
                receipt.reason == "VERIFY_READ_FAILED")
        }
        scenario("stop after successful readback close preserves committed SHA proof") {
            var permitted = true
            val out = ByteArrayOutputStream()
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream =
                    object : ByteArrayInputStream(out.toByteArray()) {
                        override fun close() {
                            super.close()
                            permitted = false // Stop pressed exactly after readback
                        }
                    }
            }, continueRequested = { permitted })
            check(!permitted && out.toByteArray().contentEquals(bytes) &&
                receipt.proof == CopyProof.BYTES_VERIFIED &&
                receipt.reason == "READBACK_SHA256_MATCH" &&
                receipt.bytesWritten == bytes.size.toLong())
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
        scenario("permanent zero-byte source reads stop without success") {
            var reads = 0
            val out = ByteArrayOutputStream()
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = object : InputStream() {
                    override fun read(): Int { reads++; return 0 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads++; return 0
                    }
                }
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(reads in 1..32 && receipt.proof == CopyProof.UNCERTAIN &&
                receipt.bytesWritten == 0L && out.size() == 0)
        }
        scenario("permanent zero-byte readback is never marked verified") {
            var reads = 0
            val out = ByteArrayOutputStream()
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = ByteArrayInputStream(bytes)
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream = object : InputStream() {
                    override fun read(): Int { reads++; return 0 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads++; return 0
                    }
                }
            })
            check(reads in 1..32 && receipt.proof == CopyProof.UNCERTAIN &&
                receipt.reason == "VERIFY_READ_FAILED" &&
                out.toByteArray().contentEquals(bytes))
        }
        scenario("transient zero-byte reads do not corrupt data") {
            val out = ByteArrayOutputStream()
            fun oneZeroThenBytes() = object : ByteArrayInputStream(bytes) {
                private var first = true
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (first) { first = false; return 0 }
                    return super.read(b, off, len)
                }
            }
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource() = oneZeroThenBytes()
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack() = oneZeroThenBytes()
            })
            check(receipt.proof == CopyProof.BYTES_VERIFIED &&
                receipt.bytesWritten == bytes.size.toLong() &&
                out.toByteArray().contentEquals(bytes))
        }
        scenario("source changed mid-stream must never produce verified receipt") {
            val out = ByteArrayOutputStream()
            var current = true
            var checks = 0
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream =
                    SourceConsistencyStream(ByteArrayInputStream(bytes)) {
                        checks++
                        current
                    }
                override fun openNewDestination(): OutputStream = object : ByteArrayOutputStream() {
                    override fun close() {
                        current = false // concurrent mutation detected only at stream close
                        super.close()
                        out.write(toByteArray())
                    }
                }
                override fun openDestinationReadBack(): InputStream =
                    ByteArrayInputStream(out.toByteArray())
            })
            check(checks == 1 && receipt.proof == CopyProof.UNCERTAIN)
            check(out.toByteArray().contentEquals(bytes))
        }
        scenario("post-read inspection failure fails closed") {
            var checks = 0
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream =
                    SourceConsistencyStream(ByteArrayInputStream(bytes)) {
                        checks++
                        throw IOException("synthetic source provider offline")
                    }
                override fun openNewDestination(): OutputStream = ByteArrayOutputStream()
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(checks == 1 && receipt.proof == CopyProof.UNCERTAIN)
        }
        scenario("unchanged source retains independent digest proof") {
            var checks = 0
            val out = ByteArrayOutputStream()
            val stream = SourceConsistencyStream(ByteArrayInputStream(bytes)) {
                checks++
                true
            }
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream = stream
                override fun openNewDestination(): OutputStream = out
                override fun openDestinationReadBack(): InputStream =
                    ByteArrayInputStream(out.toByteArray())
            })
            stream.close() // repeated close cannot re-authorize another revision
            check(checks == 1 && receipt.proof == CopyProof.BYTES_VERIFIED)
        }
        scenario("source close IO error remains uncertain") {
            val receipt = ForegroundCopyEngine.copy(p, { p }, object : CopyStreams {
                override fun openSource(): InputStream =
                    SourceConsistencyStream(object : ByteArrayInputStream(bytes) {
                        override fun close(): Unit = throw IOException("synthetic close failure")
                    }) { true }
                override fun openNewDestination(): OutputStream = ByteArrayOutputStream()
                override fun openDestinationReadBack(): InputStream? = null
            })
            check(receipt.proof == CopyProof.UNCERTAIN)
        }
        println("PASS engine total=" + passed + " proof=synthetic-JVM-only")
    }
}
