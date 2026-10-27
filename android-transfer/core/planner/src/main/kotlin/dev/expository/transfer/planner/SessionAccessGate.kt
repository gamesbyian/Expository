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

/**
 * Opaque, RAM-only session binding. LOCAL may use one explicit device-access
 * identity; REMOTE must use each authenticated connection's own identity.
 * Never use a URL, URI, username, hostname or filename as the identifier.
 */
data class ProtectedSubject(val scope: ProtectedScope, val sessionId: String) {
    init { require(sessionId.isNotBlank()) { "Missing scoped session binding" } }
}

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

    private val grants = mutableMapOf<ProtectedSubject, Grant>()
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
        subject: ProtectedSubject,
        nowMillis: Long,
        operationId: String? = null
    ) {
        require(nowMillis >= 0)
        val current = policy.forScope(subject.scope)
        if (!current.enabled) return
        if (current.cadence == UnlockCadence.PER_PROTECTED_ACTION)
            require(!operationId.isNullOrBlank()) {
                "Action-scoped authentication requires an immutable action ID"
            }
        else require(operationId == null) {
            "Session unlock must not borrow a protected operation identifier"
        }
        grants[subject] = Grant(
            nowMillis, nowMillis, backgroundGeneration, operationId)
    }

    /** Background return is a separate event, never inferred from recomposition. */
    fun onAppBackgrounded() {
        if (backgroundGeneration < Long.MAX_VALUE) backgroundGeneration++
        grants.keys.toList().forEach { subject ->
            if (policy.forScope(subject.scope).cadence in setOf(
                UnlockCadence.ON_RETURN_FROM_BACKGROUND,
                UnlockCadence.PER_PROTECTED_ACTION
            )) grants.remove(subject)
        }
    }

    /** True for disabled gates or a current device-verified unlock only. */
    fun canView(subject: ProtectedSubject, nowMillis: Long): Boolean {
        if (!policy.forScope(subject.scope).enabled) return true
        val grant = grants[subject] ?: return false
        if (nowMillis < grant.authenticatedAt || nowMillis < grant.lastInteractionAt)
            return false
        return when (policy.forScope(subject.scope).cadence) {
            UnlockCadence.ONCE_PER_APP_SESSION -> true
            UnlockCadence.ON_RETURN_FROM_BACKGROUND ->
                grant.backgroundGeneration == backgroundGeneration
            UnlockCadence.AFTER_IDLE ->
                nowMillis - grant.lastInteractionAt <=
                    policy.forScope(subject.scope).idleTimeoutMillis
            UnlockCadence.PER_PROTECTED_ACTION -> true
        }
    }

    /**
     * UI should call this only for genuine user interactions, never render
     * frames or background polls. It can't resurrect an expired grant.
     */
    fun noteUserInteraction(subject: ProtectedSubject, nowMillis: Long) {
        if (canView(subject, nowMillis) && nowMillis >= 0) {
            grants[subject]?.lastInteractionAt = nowMillis
        }
    }

    /**
     * Atomic multi-scope authorization for a reviewed transfer. Every
     * protected endpoint must be unlocked. PER_PROTECTED_ACTION tokens
     * are consumed as a group ONLY after all scope checks pass, preventing
     * half-authorized Local->Remote mutations or token replay.
     */
    fun authorizeAction(
        subjects: Set<ProtectedSubject>,
        operationId: String,
        nowMillis: Long
    ): Boolean {
        if (subjects.isEmpty() || operationId.isBlank() || nowMillis < 0) return false
        val allowed = subjects.all { subject ->
            val cfg = policy.forScope(subject.scope)
            if (!canView(subject, nowMillis)) false
            else if (!cfg.enabled ||
                cfg.cadence != UnlockCadence.PER_PROTECTED_ACTION) true
            else grants[subject]?.let {
                !it.used && it.operationId == operationId
            } == true
        }
        if (!allowed) return false
        subjects.forEach { subject ->
            val cfg = policy.forScope(subject.scope)
            if (cfg.enabled && cfg.cadence ==
                UnlockCadence.PER_PROTECTED_ACTION) grants[subject]?.used = true
            noteUserInteraction(subject, nowMillis)
        }
        return true
    }

    /** Endpoint disconnect/swap/reauth never conveys its old access grant. */
    fun lock(subject: ProtectedSubject) { grants.remove(subject) }

    /** Explicit user Lock or process-end clears all grants, not just UI flags. */
    fun lock() { grants.clear() }
}
