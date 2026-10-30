# Architecture decisions: Android operation ownership, durability and verification

**Decision status:** accepted *target engineering contracts*, NOT claims of delivered implementation. Derived from [independent audit](ARCHITECTURE-AUDIT-2026-10-29.md) of `14bbeecee63de3e03c59cc229f36184b7aee7c78`. Apply incrementally and preserve current behavior until exact-head tests prove a replacement. Supersedes conflicting recovery-journal *design wording* in older documents, not evidence in the release ledger.

## ADR-AT-01: One command/approval state owner

Presentation (Single, Dual, Android picker, Share, keyboard and future drag) emits a typed `ActionIntent` with frozen `SelectedSource`, `DestinationChoice`, source/destination authority/profile/session identity, tree grant scope and generation, opaque child identity/revision, capability proof, UI selection generation, and expiry. A small app-layer coordinator creates an `ApprovalSnapshot` and an immutable `OperationPlanner` plan and binds an active attempt token. It invalidates on picker cancellation/return mismatch, navigation, swap, grant changes, disconnect/reconnect, auth relock, incoming share or any newer incompatible request. Confirm uses the same approval revalidation as all presentation modes.

The planner remains Android-free. The coordinator does not own protocol serialization or substitute display names for authority. Existing per-route implementation may temporarily coexist, with a compatibility wrapper and explicit migration inventory. Start from already accepted SAF one-file Copy. **Do not rewrite all Compose cards or invent a DI framework.**

## ADR-AT-02: One truthful lifecycle and receipt hierarchy

A UI "busy" flag or cancellation button is not authority; cancellation of a blocking adapter must check both explicit stop permit and the owning coroutine/operation Job at bounded I/O boundaries. Handle late progress and stale completion by matching the exact operation identity/token, not just session pointer. A write attempt is journaled *before* opening any potentially mutating destination. States distinguish DRAFT/REVIEWED, ATTEMPT_RECORDED, WRITE_ACKNOWLEDGED, DESTINATION_CONFIRMED when proven, BYTES/HASH_VERIFIED, BLOCKED, STOPPED_PARTIAL and UNCERTAIN. **UNCERTAIN never auto-transitions into retry or green success.** Cloud sync acknowledgement and Sharesheet/external Open are distinct unverified handoffs.

The canonical journal's current `JournalState` must not be silently renamed for UI needs. Define a mapping/versioned migration plus actual test evidence before modifying its binary format. Progress is informational while bytes stream; verified counts rise only for independently committed proof.

## ADR-AT-03: Neutral atomic attempt record plus protected recovery index

Current `DurableAttemptJournal` is a minimal `planDigest/state/attemptId` no-path/no-secret durable record. **Retain that design.** Source/destination identities, protected user-readable display context, per-item manifests, adapter capability snapshot and reconciliation locator belong in a **separate bounded authenticated/encrypted app-private recovery index** keyed to opaque attempt, versioned for schema migration. Preserve minimal information needed to re-identify an operation under explicit user authorization. No passwords, SSH private keys, OAuth access tokens, unscoped URIs, private bytes, plaintext full paths in logs, public CI or telemetry.

Index failure is UNREADABLE or REVIEW_REQUIRED, never an empty successful history. Specify retention by sensitivity/utility and explicit user deletion with no hidden source deletion. Failed copy must not trigger automatic deletion of staged unknown data. Reopened endpoints must obtain fresh grant/auth/identity; absent proof means user review and UNCERTAIN. This resolves old `TEST-AND-EVIDENCE-MATRIX.md` language that accidentally assigned identities to the neutral journal.

## ADR-AT-04: Small capability-specific adapters, not a fake universal filesystem

Use a bounded List/Stat/Read and explicit conditional CreateChildNoReplace, readback, Rename/Move/Delete when *proved* by each authority. Unknown/unsupported stops. SFTP exclusive create and SSH host pin, FTPS TLS/control/data checks, SAF per-tree grant and identity/membership drift checks are preserved. Plain FTP remains separately consented **read-only**. Generic SAF `createDocument` is not atomic no-replace; detect competing creation/rename and return UNCERTAIN. Never convert Copy into rename; cross-authority Move requires independently verified Copy and separately authorized source deletion.

## ADR-AT-05: Safe lifecycle restoration and background policy

Restore only display-safe workspace mode, filters, non-sensitive location breadcrumbs and scroll anchors where appropriate. Never serialize temporary remote sessions, passwords, URI grants, pending mutation authorization or biometric proofs through `rememberSaveable`/`SavedStateHandle`/intents. Rotation must either preserve an explicitly lifecycle-owned operation or leave a truthful journal-driven interrupted/review state; process death requires reauthorization and independent reconciliation. Do not introduce WorkManager, FGS or auto-resume before grant durability and Android background/notification limits are verified against actual target SDK. Native session access gates are additional UI protection, not SSH/TLS/SAF trust.

## ADR-AT-06: Evidence, CI and irreversible changes

One source SHA, fixture, Android API/provider/server version, operation, proof grade and exact assertion per acceptance. E1 synthetic JVM, E2 Android IPC/system-picker, E3 controlled server, E4 third-party/handset, E5 signed release are separate statuses. Default PR gate must compile impacted Android code and run actual canonical JVM tests; E2/E3 opt in by affected risky change; E4/E5 never inferred. Keep source lanes exclusive where `TransferActivity.kt`, `SftpBrowseCard.kt`, core planner or journal schema overlap. An ADR amendment requires explicit compatibility analysis, regression tests and reviewer approval.

## Alternatives rejected for now

- Broad Compose/ViewModel rewrite, Hilt installation or per-protocol Gradle modules without proven need.
- A generic API that falsely promises atomic creates, renames or durable capabilities across FTP/SFTP/SAF/Drive.
- Storing rich, sensitive recovery metadata inside the neutral journal, or discarding recovery identity entirely.
- Automatic replay of UNCERTAIN on reboot/network return.
- Conflating APK build/checksum with real-device and signed-release proof.
