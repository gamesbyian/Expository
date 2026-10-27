package dev.expository.transfer.planner

/** E1 offline authentication policy only. No biometric prompts or real secrets. */
object SessionAccessGateSuite {
    private val local = ProtectedSubject(ProtectedScope.LOCAL, "ephemeral-local")
    private val sftpA = ProtectedSubject(ProtectedScope.REMOTE, "opaque-sftp-A")
    private val sftpB = ProtectedSubject(ProtectedScope.REMOTE, "opaque-sftp-B")

    private fun locked(cadence: UnlockCadence = UnlockCadence.ONCE_PER_APP_SESSION,
        idleMillis: Long = 60_000L) =
        ScopedUnlockPolicy(enabled = true, cadence = cadence,
            idleTimeoutMillis = idleMillis)

    @JvmStatic fun main(args: Array<String>) {
        val default = SessionAccessGate("session-1")
        check(default.canView(local, 0L)) // opt-in, disabled by default
        check(default.canView(sftpA, 0L))
        check(default.authorizeAction(setOf(local, sftpA), "copy-A", 1L))
        check(runCatching { SessionAccessGate("") }.isFailure)
        check(runCatching { ScopedUnlockPolicy(idleTimeoutMillis = 0L) }.isFailure)

        // Local unlock never unlocks a separately authenticated remote profile.
        val session = SessionAccessGate("session-2",
            SessionUnlockPolicy(local = locked(), remote = locked()))
        check(!session.canView(local, 100L))
        session.onPlatformAuthenticationSucceeded(local, 100L)
        check(session.canView(local, 101L))
        check(!session.canView(sftpA, 101L))
        session.onPlatformAuthenticationSucceeded(sftpA, 105L)
        check(session.canView(sftpA, 110L))
        check(!session.canView(sftpB, 110L))
        check(session.authorizeAction(setOf(local, sftpA), "reviewed-copy", 120L))
        check(!session.authorizeAction(setOf(local, sftpB), "reviewed-copy", 121L))
        session.onAppBackgrounded()
        check(session.canView(local, 150L)) // session default, no refocus prompt
        check(session.canView(sftpA, 150L))
        session.lock(sftpA)
        check(!session.canView(sftpA, 150L))
        check(session.canView(local, 150L))
        session.lock()
        check(!session.canView(local, 150L))
        check(!SessionAccessGate("new-session",
            SessionUnlockPolicy(local = locked())).canView(local, 150L))

        // Screen-lock/return policy is stronger than session convenience.
        val resume = SessionAccessGate("session-3",
            SessionUnlockPolicy(local = locked(
                UnlockCadence.ON_RETURN_FROM_BACKGROUND)))
        resume.onPlatformAuthenticationSucceeded(local, 1000L)
        check(resume.canView(local, 1001L))
        resume.onAppBackgrounded()
        check(!resume.canView(local, 1002L))
        resume.onPlatformAuthenticationSucceeded(local, 1010L)
        check(resume.canView(local, 1020L))

        // Idle timeout counts actual interaction, never Compose recomposition.
        val idle = SessionAccessGate("session-4",
            SessionUnlockPolicy(local = locked(UnlockCadence.AFTER_IDLE)))
        idle.onPlatformAuthenticationSucceeded(local, 2000L)
        check(idle.canView(local, 62_000L))
        check(!idle.canView(local, 62_001L))
        idle.noteUserInteraction(local, 62_010L) // can't revive stale grant
        check(!idle.canView(local, 62_011L))
        idle.onPlatformAuthenticationSucceeded(local, 70_000L)
        idle.noteUserInteraction(local, 100_000L)
        check(idle.canView(local, 160_000L))
        check(!idle.canView(local, 160_001L))
        check(!idle.canView(local, 1L)) // monotonic time rollback blocks

        // Every-action requires a unique exact approved action ID and burns
        // its proof only after *both* endpoints have been authenticated.
        val perAction = SessionAccessGate("session-5",
            SessionUnlockPolicy(local = locked(UnlockCadence.PER_PROTECTED_ACTION),
                remote = locked(UnlockCadence.PER_PROTECTED_ACTION)))
        check(runCatching {
            perAction.onPlatformAuthenticationSucceeded(local, 1L)
        }.isFailure)
        check(runCatching {
            perAction.onPlatformAuthenticationSucceeded(local, 1L, "")
        }.isFailure)
        perAction.onPlatformAuthenticationSucceeded(local, 10L, "digest-1")
        check(perAction.canView(local, 12L))
        check(!perAction.authorizeAction(setOf(local, sftpA), "digest-1", 12L))
        check(!perAction.authorizeAction(setOf(local), "wrong-digest", 13L))
        perAction.onPlatformAuthenticationSucceeded(sftpA, 14L, "digest-1")
        check(perAction.authorizeAction(setOf(local, sftpA), "digest-1", 15L))
        check(!perAction.authorizeAction(setOf(local, sftpA), "digest-1", 16L))
        perAction.onPlatformAuthenticationSucceeded(local, 20L, "digest-2")
        perAction.onPlatformAuthenticationSucceeded(sftpA, 20L, "digest-2")
        perAction.onAppBackgrounded()
        check(!perAction.canView(local, 21L))
        check(!perAction.authorizeAction(setOf(local, sftpA), "digest-2", 22L))
        check(runCatching {
            perAction.onPlatformAuthenticationSucceeded(local, 30L,
                "session-only-operation")
        }.isSuccess)

        // Policy edits invalidate previous proof and never silently inherit.
        val changed = SessionAccessGate("session-6",
            SessionUnlockPolicy(local = locked()))
        changed.onPlatformAuthenticationSucceeded(local, 100L)
        changed.updatePolicy(SessionUnlockPolicy(local = locked(
            UnlockCadence.ON_RETURN_FROM_BACKGROUND)))
        check(!changed.canView(local, 101L))
        changed.onPlatformAuthenticationSucceeded(local, 110L)
        changed.updatePolicy(SessionUnlockPolicy(local = ScopedUnlockPolicy()))
        check(changed.canView(local, 111L)) // explicit opt-out
        changed.updatePolicy(SessionUnlockPolicy(local = locked()))
        check(!changed.canView(local, 112L))

        println("SessionAccessGateSuite: policy, multi-endpoint, replay and idle checks passed")
    }
}
