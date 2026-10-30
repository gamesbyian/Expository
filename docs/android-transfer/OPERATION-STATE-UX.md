# Android operation lifecycle and recovery UX contract

**Design contract, not implemented UI acceptance.** Companion to [current product contract](CURRENT-CONTRACT.md), [workspace spec](WORKSPACE-AND-BULK-RENAME.md), [architecture ADR](ARCHITECTURE-DECISIONS.md) and [audit register](ARCHITECTURE-AUDIT-2026-10-29.md). Preserve the permanent full-featured Single location workspace and optional independently navigable Dual Local/Local, Local/Remote, Remote/Remote panes.

## One interaction model in both layouts

A selection yields a visible but non-mutating **propose** action. Review always identifies source endpoint/account/session (redacted appropriately), destination endpoint/folder, selected file and folder count, estimated known bytes, Copy vs explicit Move, destination conflict/replace policy, capability strength, possible relay cost and any missing permissions. Button, keyboard, TalkBack and future drag must generate **the same command and approval digest**. Drag defaults Copy and cannot execute before the same review. A confirmation freezes one attempt; secondary actions during execution cannot usurp it.

State machine (visual design and accessible language; preserve production enum until explicitly migrated):

```text
IDLE → OBSERVING → REVIEW_READY → USER_CONFIRMED → ATTEMPT_RECORDED
                      │                              │
                  BLOCKED/CANCELLED              WRITING → VERIFYING
                                                     │
                                VERIFIED / ACK_ONLY / STOPPED_PARTIAL / UNCERTAIN
                                                          │
                                                  REVIEW_REQUIRED
                                                          │
                                              REOBSERVE → RESOLVED or UNCERTAIN
```

An unstarted cancellation says **no Copy started**, but a system Save As picker may already have created an empty document. After a possible write, STOP means no *further approved mutation*, not a guarantee that destination is absent; show partial/uncertain counts. `WRITE_ACKNOWLEDGED` is never displayed as `verified`, `Saved to Drive` or `safe to delete source`. Distinguish server close ACK, destination identity confirmed, byte/hash verified and cloud synchronization unknown.

## Progress/receipts/accessibility

Transfer progress should show selected/attempted/verified counts separately, exact source and destination contextual labels, file bytes streamed as **in progress** and no bogus percent for unknown total. Once verified, per-item receipts show proof grade, destination identity/version, and recoverable redacted review reference. Warnings use text, icons and TalkBack semantic announcements, not color alone. Add explicit user-visible Cancel/Stop with predictable focus restoration; pending picker cancellation never changes the previously authorized pane. Keep minimum 44dp touch surfaces and inspect 360/393/412dp, landscape and 200% text.

Keep a separate **Interrupted transfers** panel even when the live operation card disappears. Each row must show redacted context sufficient for a user to locate the target after reauth, attempt stage, known verified items, unknown item and practical `Review destination` / `Reauthorize` actions. There is no automatic retry, cleanup, rollback or source deletion. If index corruption prevents location, show `Recovery record unreadable; destination may have changed` and explain manual inspection.

## Restoration and security states

Distinguish `Display restorable` (mode/filter/scroll) from `Authority expired` (SAF grant, remote login, biometric gate) and `Attempt durable` (journal/recovery). On rotation, replacement or process death, no old callback may enable a mutation or repaint a newer operation's state. Session credentials and provider grants never enter saved-state bundles; after process death request system picker/auth again. If an opted-in Local/Remote gate is locked, sensitive file previews and protected actions remain unavailable until true system biometric/credential success. A gate failure never counts as authorization for a pending Copy.

## Required UI verification

E1: same digest for Single/Dual/button/keyboard/drag and stale share/swaps. E2: real picker cancel and return, screen rotation, app disposal, revoked grant, progress after cancel, concurrent browse responses, TalkBack focus and 200% text. E3: server ACK lost/partial readback, reconnection. E4: third-party provider rename/collision and physical phone. No visual mock or screenshot is acceptance of transported bytes.
