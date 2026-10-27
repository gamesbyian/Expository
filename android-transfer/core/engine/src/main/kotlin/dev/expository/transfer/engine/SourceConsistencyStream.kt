package dev.expository.transfer.engine

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Observe the selected source again after its transfer stream closes.
 *
 * An immutable Copy plan normally revalidates before touching bytes, but a
 * cloud DocumentsProvider or local/remote writer may change the source while
 * it is being read. This guard turns detected post-read drift into an I/O
 * failure, preventing a successful destination hash from claiming that the
 * originally approved source version was preserved.
 *
 * The callback must perform fresh provider/remote observations; a cached
 * revision is not a witness. A false/exceptional callback fails closed.
 * Metadata can be weak; this detects observable drift, not every possible
 * concurrent content mutation. The enclosing Copy engine controls journaling,
 * destination uncertainty and cancellation. No automatic source deletion.
 */
class SourceConsistencyStream(
    delegate: InputStream,
    private val stillCurrent: () -> Boolean
) : FilterInputStream(delegate) {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        var closeFailure: Throwable? = null
        try {
            super.close()
        } catch (failure: Throwable) {
            closeFailure = failure
        }
        val current = try { stillCurrent() } catch (_: Exception) { false }
        if (!current) {
            val drift = IOException("SOURCE_REVISION_UNVERIFIED_AFTER_READ")
            if (closeFailure != null) drift.addSuppressed(closeFailure)
            throw drift
        }
        if (closeFailure != null) {
            if (closeFailure is IOException) throw closeFailure
            throw IOException("SOURCE_STREAM_CLOSE_FAILED", closeFailure)
        }
    }
}
