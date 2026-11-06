package dev.expository.transfer

/**
 * One-shot ownership handoff for a blocking network connection created in IO
 * while its Compose coroutine can be canceled.
 *
 * Cancellation of withContext(IO) may discard a successfully connected
 * session BEFORE the caller receives its result. The worker registers the
 * session immediately; UI adopts it only after resuming in its still-active
 * scope. If the owning coroutine exits early, the registered session is
 * retired off-main. A late worker registration after abandonment is retired
 * too, without reactivating a canceled UI.
 *
 * Exactly one candidate can be offered; no reconnection, retry, permission
 * persistence, file operation or background ownership is introduced.
 */
internal class RemoteSessionAcquisition<T : AutoCloseable> {
    private val lock = Any()
    private var abandoned = false
    private var adopted = false
    private var pending: T? = null

    /** Called immediately after the worker has fully prepared the session. */
    fun offer(candidate: T) {
        val reject = synchronized(lock) {
            if (abandoned || adopted || pending != null) true
            else {
                pending = candidate
                false
            }
        }
        if (reject) RemoteSessionRetirement.retire(candidate)
    }

    /** Called by the still-active UI coroutine before exposing session state. */
    fun adopt(candidate: T): Boolean = synchronized(lock) {
        if (abandoned || adopted || pending !== candidate) false
        else {
            pending = null
            adopted = true
            true
        }
    }

    /** Idempotent coroutine-finally cleanup, including cancellation races. */
    fun abandon() {
        val orphan = synchronized(lock) {
            abandoned = true
            pending.also { pending = null }
        }
        RemoteSessionRetirement.retire(orphan)
    }
}
