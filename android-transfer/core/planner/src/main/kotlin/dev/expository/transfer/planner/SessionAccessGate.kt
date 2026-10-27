package dev.expository.transfer.planner

/**
 * Pure E1 policy for an OPTIONAL second gate around local or remote UI.
 *
 * This gate does not authenticate a user itself. Only Android's platform
 * BiometricPrompt / device credential success callback may call
 * [onPlatformAuthenticationSucceeded]. Never treat a button press, password
 * field content, SFTP connection or restored UI state as that callback.
 *
 * Credentials, biometric data and source URIs never enter this model. All
 * grants are RAM-only and scoped to a single process/app-session identity.
 */
enum class ProtectedScope { LOCAL, REMOTE }
enum class UnlockCadence {
    ONCE_PER_APP_SESSION,
    ON_RETURN_FROM_BACKGROUND,
    AFTER_IDLE,
    PER_PROTECTED_ACTION
}
data class ScopedUnlockPolicy(
    val enabled: Boolean = false,
    val cadence: UnlockCadence = UnlockCadence.ONCE_PER_APP_SESSION,
    val idleTimeoutMillis: Long = 5L * 60_000
) {
    init {
        require(idleTimeoutMillis in 60_000L..60L * 60_000) {
            "Idle timeout must be 1–60 minutes"
        }
    }
}
data class SessionUnlockPolicy(
    val local: ScopedUnlockPolicy = ScopedUnlockPolicy(),
    val remote: ScopedUnlockPolicy = ScopedUnlockPolicy()
) {
    fun forScope(scope: ProtectedScope): ScopedUnlockPolicy =
        if (scope == ProtectedScope.LOCAL) local else remote
}

/**
 * No persisted proof and no credential material. Once-per-session is the
 * default cadence IF the optional scope gate is enabled. Disabled scopes
 * remain optional for convenience, without weakening real SSH/TLS login.
 */
class SessionAccessGate(
    val sessionNonce: String,
    initialPolicy: SessionUnlockPolicy = SessionUnlockPolicy()
) {
    init { require(sessionNonce.isNotBlank()) { "Missing process session identity" } }

    private data class Grant(
        val authenticatedAt: Long,
        var lastInteractionAt: Long,
        val backgroundGeneration: Long,
        val operationId: String?,
        var used: Boolean = false
    )

    var policy: SessionUnlockPolicy = initialPolicy
        private set

    private val grants = mutableMapOf<ProtectedScope, Grant>()
    private var backgroundGeneration = 0L

    /** Changing security/convenience settings revokes all previously issued grants. */
    fun updatePolicy(next: SessionUnlockPolicy) {
        if (next != policy) {
            grants.clear()
            policy = next
        }
    }

    /**
     * Must be called when Android genuinely reports an authentication success,
     * and never from the settings UI itself. A PER_PROTECTED_ACTION result
     * must bind one unique, immutable approved action identifier.
     */
    fun onPlatformAuthenticationSucceeded(
        scope: ProtectedScope,
        nowMillis: Long,
        operationId: String? = null
    ) {
        require(nowMillis >= 0)
        val current = policy.forScope(scope)
        if (!current.enabled) return
        if (current.cadence == UnlockCadence.PER_PROTECTED_ACTION)
            require(!operationId.isNullOrBlank()) {
                "Action-scoped authentication requires an immutable action ID"
            }
        else require(operationId == null) {
            "Session unlock must not borrow a protected operation identifier"
        }
        grants[scope] = Grant(
            nowMillis, nowMillis, backgroundGeneration, operationId)
    }

    /** Background return is a separate event, never inferred from recomposition. */
    fun onAppBackgrounded() {
        if (backgroundGeneration < Long.MAX_VALUE) backgroundGeneration++
        grants.keys.toList().forEach { scope ->
            if (policy.forScope(scope).cadence ==
                UnlockCadence.ON_RETURN_FROM_BACKGROUND) grants.remove(scope)
        }
    }

    /** True for disabled gates or a current device-verified unlock only. */
    fun canView(scope: ProtectedScope, nowMillis: Long): Boolean {
        if (!policy.forScope(scope).enabled) return true
        val grant = grants[scope] ?: return false
        if (nowMillis < grant.authenticatedAt || nowMillis < grant.lastInteractionAt)
            return false
        return when (policy.forScope(scope).cadence) {
            UnlockCadence.ONCE_PER_APP_SESSION -> true
            UnlockCadence.ON_RETURN_FROM_BACKGROUND ->
                grant.backgroundGeneration == backgroundGeneration
            UnlockCadence.AFTER_IDLE ->
                nowMillis - grant.lastInteractionAt <=
                    policy.forScope(scope).idleTimeoutMillis
            UnlockCadence.PER_PROTECTED_ACTION -> true
        }
    }

    /**
     * UI should call this only for genuine user interactions, never render
     * frames or background polls. It can't resurrect an expired grant.
     */
    fun noteUserInteraction(scope: ProtectedScope, nowMillis: Long) {
        if (canView(scope, nowMillis) && nowMillis >= 0) {
            grants[scope]?.lastInteractionAt = nowMillis
        }
    }

    /**
     * Atomic multi-scope authorization for a reviewed transfer. Every
     * protected endpoint must be unlocked. PER_PROTECTED_ACTION tokens
     * are consumed as a group ONLY after all scope checks pass, preventing
     * half-authorized Local->Remote mutations or token replay.
     */
    fun authorizeAction(
        scopes: Set<ProtectedScope>,
        operationId: String,
        nowMillis: Long
    ): Boolean {
        if (scopes.isEmpty() || operationId.isBlank() || nowMillis < 0) return false
        val allowed = scopes.all { scope ->
            val cfg = policy.forScope(scope)
            if (!canView(scope, nowMillis)) false
            else if (!cfg.enabled ||
                cfg.cadence != UnlockCadence.PER_PROTECTED_ACTION) true
            else grants[scope]?.let {
                !it.used && it.operationId == operationId
            } == true
        }
        if (!allowed) return false
        scopes.forEach { scope ->
            val cfg = policy.forScope(scope)
            if (cfg.enabled && cfg.cadence ==
                UnlockCadence.PER_PROTECTED_ACTION) grants[scope]?.used = true
            noteUserInteraction(scope, nowMillis)
        }
        return true
    }

    /** Explicit user Lock or process-end clears all grants, not just UI flags. */
    fun lock() { grants.clear() }
}
