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

## Interrupted-target correspondence versus actual reconciliation

A private record suffix is a **display label**, never operation identity. The accepted #440 recovery picker flow binds a new selection to the full private journal key while the UI shows only a short suffix. The accepted #441 view distinguishes `ATTEMPT_RECORDED`, `WRITE_ACKNOWLEDGED` and `UNCERTAIN` without changing any journal state or inventing a successful receipt.

Provider authority alone is too weak to distinguish different folders in a single Android DocumentsProvider. A separately authenticated/encrypted, versioned fingerprint of the **original user-picked tree root** may, with a NEW system picker READ grant and successful provider query, support a narrow *same-recorded-root-identifier / different-root / unavailable* diagnostic. Never save a URI grant or raw document ID for this purpose, and preserve older hints that have no such witness. The [merged #442](https://github.com/gamesbyian/Expository/pull/442) implementation passed [24/24 API35 scoped recovery + recursive Copy instrumentation](https://github.com/gamesbyian/Expository/actions/runs/41785520927) after a separate initial emulator boot failure, and [2/2 recovery UI tests](https://github.com/gamesbyian/Expository/actions/runs/41785523338). This is scoped synthetic E2 proof of the captured root witness and read-only comparison, not live/third-party provider E4 or full created-child reconciliation.

Even a matching root token does **not** prove the identity/version of a created child, bytes committed, complete folder contents, cloud sync, or the result of an interrupted operation. For actual per-item reconciliation, independently reobserve the exact created target under fresh authority, require sufficient provider identity/version/parent evidence and independent content proof, and keep ambiguous results REVIEW_REQUIRED. No match may authorize automatic replay, cleanup, source deletion or silent journal promotion. Preserve the distinction between read-only operator inspection and a later explicitly approved operation.

**Accepted narrow created-child milestone (#444, 2026-11-04):** After a provider returns a new document ID and before any file output stream can open, recursive/Dual SAF Copy records a *separate* encrypted, write-once fingerprint of the exact direct parent, created child ID, name and item kind. A new user-picked READ grant to the **same direct parent** permits a bounded complete listing and a read-only identity comparison: matching child ID, replaced ID, wrong parent, ambiguous/missing child, truncated listing or absent/corrupt witness. A created child can exist with **no witness** if the provider loses its create reply. The exact-head [API35 recovery/recursive 25/25](https://github.com/gamesbyian/Expository/actions/runs/41827001016) plus [UI 2/2](https://github.com/gamesbyian/Expository/actions/runs/41827006714) support this bounded result. **Do not** infer byte content, current source revision, synchronization or durable queue progress from this match. Any future operator resolution requires separately authorized source/target reobservation, byte/version proof and its own safe state transition; current UI has no Resolve or Retry button.

## Required UI verification

E1: same digest for Single/Dual/button/keyboard/drag and stale share/swaps. E2: real picker cancel and return, screen rotation, app disposal, revoked grant, progress after cancel, concurrent browse responses, TalkBack focus and 200% text. E3: server ACK lost/partial readback, reconnection. E4: third-party provider rename/collision and physical phone. No visual mock or screenshot is acceptance of transported bytes.
