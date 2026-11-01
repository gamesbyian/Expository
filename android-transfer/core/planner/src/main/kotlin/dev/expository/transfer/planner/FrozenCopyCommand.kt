package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A short-lived authority witness shared by Single picker and Dual SAF Copy.
 *
 * This is strictly a non-executable, Android-free command identity. It cannot
 * confer a SAF grant, observe a provider, replace an OperationPlanner plan,
 * authorize a deletion, or reopen a journal after process death.
 *
 * Real adapters must still independently validate source and destination,
 * consume an explicit human confirmation and journal before any mutation.
 */
data class CopyCommandScope private constructor(
    val endpoint: BrowseEndpoint,
    val key: LocationKey,
    val listingRevision: String,
    val generation: Long,
    private val selectionFingerprint: String
) {
    companion object {
        /**
         * Freeze a canonical selection without keeping mutable UI collections.
         * The digest is local to this in-memory approval, never a transfer
         * receipt, persistence key or security token on its own.
         */
        fun freeze(
            endpoint: BrowseEndpoint,
            key: LocationKey,
            listingRevision: String,
            generation: Long,
            selectedIds: Collection<String>
        ): CopyCommandScope? {
            if (!endpoint.accepts(key.kind) || key.authorityId.isBlank() ||
                key.rootId.isBlank() || key.containerId.isBlank() ||
                listingRevision.isBlank() || generation < 0L ||
                selectedIds.any { it.isBlank() } ||
                selectedIds.size != selectedIds.toSet().size) return null
            val ids = selectedIds.sorted()
            val md = MessageDigest.getInstance("SHA-256")
            fun update(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                md.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
                md.update(':'.code.toByte())
                md.update(bytes)
            }
            update("EXPOSITORY_COPY_SELECTION_V1")
            update(ids.size.toString())
            ids.forEach(::update)
            val fingerprint = md.digest().joinToString("") { "%02x".format(it) }
            return CopyCommandScope(endpoint, key, listingRevision, generation, fingerprint)
        }
    }
}

/**
 * Nonpersistent COPY-only approval ownership shared by layout routes. The
 * destination can be absent only while a Single ACTION_CREATE_DOCUMENT picker
 * is pending; the returned URI must be separately granted and reobserved.
 *
 * A changed source, destination, generation, selection or incoming Share
 * revokes this witness, even if the display name or route button is unchanged.
 */
class FrozenCopyCommand private constructor(
    val source: CopyCommandScope,
    val destination: CopyCommandScope?,
    val shareEpoch: Long,
    val intent: Operation = Operation.COPY
) {
    private val claimed = AtomicBoolean(false)

    companion object {
        fun capture(
            source: CopyCommandScope?,
            destination: CopyCommandScope?,
            shareEpoch: Long
        ): FrozenCopyCommand? =
            if (source == null || shareEpoch < 0L) null
            else FrozenCopyCommand(source, destination, shareEpoch)
    }

    fun stillMatches(
        currentSource: CopyCommandScope?,
        currentDestination: CopyCommandScope?,
        currentShareEpoch: Long
    ): Boolean =
        currentShareEpoch >= 0L && currentShareEpoch == shareEpoch &&
            currentSource != null && currentSource == source &&
            currentDestination == destination &&
            intent == Operation.COPY

    /**
     * Consume exactly once at an explicit user confirmation boundary.
     * A stale caller cannot consume the token. This in-memory claim is not
     * provider authority, a durable write attempt, or evidence of success.
     * A blocked/uncertain execution must require a NEW review, never replay
     * the previously claimed command.
     */
    fun claimOnce(
        currentSource: CopyCommandScope?,
        currentDestination: CopyCommandScope?,
        currentShareEpoch: Long
    ): Boolean =
        stillMatches(currentSource, currentDestination, currentShareEpoch) &&
            claimed.compareAndSet(false, true)
}
