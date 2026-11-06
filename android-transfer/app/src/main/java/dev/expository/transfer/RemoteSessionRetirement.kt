package dev.expository.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Retire a previously owned network connection without running blocking
 * logout/socket shutdown on Compose's main-thread disposal callback.
 *
 * The caller must first remove the old session from mutable UI authority.
 * This one-way, best-effort cleanup does not reconnect, replay, authenticate,
 * or restore any grant. The independent IO scope outlives composition
 * cancellation long enough to release the retired socket.
 */
internal object RemoteSessionRetirement {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun retire(session: AutoCloseable?) {
        if (session == null) return
        cleanupScope.launch {
            runCatching { session.close() }
        }
    }
}
