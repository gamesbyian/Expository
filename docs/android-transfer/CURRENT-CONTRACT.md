# Expository Transfer: current product and execution contract

**Reconciled:** 26 October 2026. **Status:** binding product requirements, not evidence that the requirements have shipped. Read [native work queue](../../android-transfer/WORK-QUEUE.md) for *next tasks* and [release ledger](RELEASE-READINESS.md) for *verified gate evidence*. This document supersedes conflicting **future-intent/status summaries**, not the detailed protocol, security, rename, Drive and UX acceptance specifications linked below.

## Product identity and scope

Expository Transfer is a **native Android file-transfer application** using its own app identity and authorization. The existing Expository GitHub Pages repository browser is a different, strictly **read-only** application. Keep native code inside `android-transfer/` pending an explicitly approved repository migration. No relay service, shared web token, mandatory Expository account, global storage grant, telemetry containing filenames, or unrequested automatic sync.

**The product promise:** with authorized endpoints, make a truthful source → destination plan that a user can inspect and execute, then provide precise per-item outcomes without losing originals or silently repeating uncertain writes.

**P0 means required for the target beta, not currently implemented.** The narrow current developer preview has functional scoped local picker Copy; pinned SFTP browse, Save As, exclusive uploads, New Folder and one-file Move; FTPS encrypted browsing/Save As; local open/share and bounded incoming shares; private attempt journals and a preview-only SFTP renamer. These are separate route-specific cards, **not the required any-two-pane workspace**. No verified Android→production SFTP batch, real Drive/Docs destination, remote Rename Apply, process-death recovery, physical-device or signed beta acceptance. See ledger for exact runs; always reconfirm live `main`.

## Traceable beta requirements

| ID / gate | Required user-visible behavior | Minimum delivery proof |
|---|---|---|
| P01 · G0/G3 | Connect/browse FTP (explicitly insecure opt-in only), explicit/implicit FTPS with hostname/TLS trust and protected data, and pinned-host-key SFTP. Clear protocol/security identity and recoverable errors | Tested positive and rejection paths on controlled servers; actual Android connection and representative interop; no TLS/SSH bypass |
| P02 · G2/G4 | One-action **Save to device** / **Upload here** using Android system Open/Create pickers; multi-file selection; handle cancellations, revoked grants, and nonseekable cloud streams | Real Android content URI bytes/permission tests, exact-byte receipts; no user-path assumption |
| P03 · G4/G5 | Download/upload **whole folders** recursively via tree grants, including nested and empty dirs, conflict choice, bounded queue and per-item progress | Tree/provider E2/E4, size/child-count stress, restart/interruption, no implicit deletes |
| P04 · G6 | Independently configurable **Local/Local, Local/Remote, Remote/Remote** panes; same or different profile/authority, both directions, independent breadcrumbs/selection/sort/scroll; accessible Copy and Move actions | Six directions + same/different provider/server E2/E3 and E4; actual payload, not demo tabs |
| P05 · G6 | Optional drag-and-drop to a pane/folder and equivalent buttons; drag defaults **Copy**, never executes a write before identical preflight; clear destination and swap | Identical plan digest for drag/button, 360/393/412dp, landscape, high text scale, TalkBack |
| P06 · G6 | In remote browse, **New folder**, **Move to…**, navigate/create a destination folder inline, then move selected files; verified on-server Move if safe, otherwise truthful relay/unsupported result | Multi-item controlled server write, collision/permission/race tests, journal + per-item proof |
| P07 · G1/G7 | Compact renamer in both local and remote panes: Replace, Number (pinned order/start/step/width), Prefix/Suffix, Case, Clean up, extension lock by default, old→new virtual preview and clear errors | 40-name examples, 500 execution cases, 10,000-row virtualized E2, high text scale |
| P08 · G1/G5/G7 | **Apply** guarded by complete listing/capability and changed-sibling recheck; cross-case/Unicode collisions, swaps/cycles, temp staging, per-step journal, partial/uncertain result; safe best-effort undo only when provable | Fault injection at every phase and independent reconciliation; no destructive retry |
| P09 · G5 | Durable transfer queue, cancel/retry/reconcile, background/lifecycle permission constraints, per-item receipts: handoff vs acknowledged vs destination-confirmed vs byte/hash-verified | App death/reboot/revocation/interruption matrix; zero false success |
| P10 · G8 | **Copy original to Google Drive** from local or remote (single/multi/folders), with selected account and writable destination, using verified SAF or narrowly scoped Drive API + resumable upload | Real account/provider/API, parent/file identity, ambiguous create dedup, quota/auth tests; no manual Downloads detour |
| P11 · G8 | **Create Google Doc** only with explicit MIME-supported conversion, distinct receipt and preserved source. **Open with Docs** launches a compatible external editor with scoped URI. **Share to app** is handoff only | Real converted Workspace MIME/id, Android intent recipient and cancellation tests; no false Drive save |
| P12 · G0/G9 | Secure device-local credentials when profiles persist; privacy-safe diagnostics, accessible UI, physically installed and independently reviewed beta | Device and protocol validation, credential audit, SDK 37/LAN behavior as applicable, signed sideload provenance |

P01 FTP plaintext availability is **opt-in**, visibly insecure and cannot be activated by failure/downgrade. For cross-server Remote→Remote, data ordinarily flows through the handset, with honest metered-network/storage implications; **no FXP** default. Same-server **Copy** is not implemented with rename. The current developer preview limits directory listing at 200 and upload picker batches at 12; these are **temporary implementation ceilings**, not P0 folder, browsing or workspace acceptance.

## Required operation matrix

| From → to | Copy | Explicit Move | Important distinction |
|---|---|---|---|
| Local SAF → Local SAF | Stream or verified provider-native copy | Prefer authorized provider-native move; else Copy→verify→user-confirmed delete | Different providers/grants possible; SAF rename/move may replace the URI |
| Local SAF → Remote | Phone-mediated scoped upload | Copy→verify→user-confirmed source delete when separately supported | Never infer source path from display name |
| Remote → Local SAF | Android Save As/tree creation and streaming | Copy→verify→user-confirmed remote delete only with explicit capability | Provider acknowledgement ≠ cloud synchronization |
| Remote → same verified server/namespace | Copy via supported safe server-copy or device relay | Verified SFTP rename / FTP RNFR–RNTO if capable, no overwrite | Rename is never the Copy optimization; no assumed atomicity |
| Remote A → Remote B (different account/server) | Explicit handset relay using both authenticated sessions | Copy→verify→user-confirmed source delete; may remain unsupported | Distinct authentication, session, path roots, metered costs |
| Local or Remote → Google Drive | Verified writable SAF provider or narrow `drive.file` API with identity/receipt | **Not in v1** | Share/open/Docs conversion are *different* actions |
| Local or Remote → Google Docs | Explicit supported-file conversion, or external Open | **Not in v1** | A Google Doc is not byte-identical to source |

**Every write:** immutable selected-item and target authority/version snapshot → show operation and conflict policy → require consent → revalidate just before mutation → journal before side effects → stream/commit → inspect destination → receipt. Unknown permission, path identity, symlink boundary, listing completeness or collision policy **blocks destructive paths**. Copy preserves source. Source deletion needs explicit separate consent and destination proof, not an unverified handoff, FTP acknowledgement, metadata coincidence or cloud icon.

**Crash/ambiguity:** `UNCERTAIN` is neither failure nor success. Never replay ambiguous FTP RNFR/RNTO, SFTP rename, staged commit, create/overwrite, Drive create or source delete until the actual endpoint has been reconciled. If identity cannot be established, present manual inspection and retain originals. No automatic cleanup of unknown remote temporary files. Journal and previews must not leak profile addresses, tokens, filenames or private contents to CI/telemetry.

## UX contract

- Start in **one usable location**: primary actions Save, Upload, New Folder, Move to, Copy to and Rename are discoverable without entering split mode. The system picker handles ordinary Android file selection/destination.
- Workspace is **optional**, with separate independently configurable A/B locations, two breadcrumbs and clear **Copy A→B / B→A** actions. Device↔Device, Device↔Server and Server↔Server presets are shortcuts, not restrictions. No fake split constructed from Local/SFTP/FTPS tabs.
- At 360/393/412dp portrait, support accessible 48dp targets, insets, scrollable sheets and keyboard/IME. When 200% font or small height makes split panes unusable, collapse to **A/B tabs with persistent destination summary**, preserving both navigation and selection states. Test landscape and foldable/tablet arrangements too.
- Dragging onto an existing folder or blank pane surface opens the **same** Copy preflight as the button; explicit Move remains separate. No drag-only commands or unexpected source change when swapping panels.
- Rename compact sheet keeps fixed counts/error summary, operation chips, sort/number preview, extension lock and always-accessible Apply/Cancel; virtualize without changing numerical order. Apply disabled until native path is fully proven; never label preview-only implementation beta-complete.
- Receipts name *what was verified*: `HANDED_OFF_UNVERIFIED`, `WRITE_ACKNOWLEDGED`, `DESTINATION_CONFIRMED`, `CONTENT_HASH_VERIFIED` (only with actual byte-equivalent readback), `GOOGLE_DOC_CREATED`, `UNCERTAIN`. “100% sent” never implies remote persistence/Drive cloud commit.

## Quality, consent and privacy

Use synthetic E1 and API 35 E2 frequently, controlled protocol E3 intentionally, physical device + actual authorized provider E4 for P0 claims, and independent E5 for release. CI passes are scoped to **exact SHA, test suite, platform, run, expected assertions and inspected result**. A run request or compiled APK does not prove execution. Do not place personal servers, credentials, private paths or real-account screenshots in public logs/artifacts. Live mutation requires explicit authorized disposable fixtures; do not probe or alter third-party demo data. Prefer JVM checks and focused PR-head Android tests to broad costly reruns. Separate defects in disposable DocumentsUI fixtures from app behavioral failures.

Performance/stress gates include >2GiB streams, 2,000-child/large directories, 10,000 preview rows, network stalls, nonseekable SAF content, low storage, resume token/session expiry, account switching and malformed/out-of-order Android Intents. Never infer provider support from an action name alone; test per-item capability and grant lifetime.

**Release is blocked** by silent overwrite/deletion; untrusted TLS or changed SSH key accepted; automatic unsafe downgrade/retry; wrong-source picker race; indefinite stalled I/O falsely marked complete; false Drive/Docs receipts; nonreconcilable staging; drag-only operations; off-screen controls at 200% font; incomplete six-direction/folder flows; missing E4/E5.

## Where details live; how to prevent drift

- [Product proposal](PRODUCT-PROPOSAL.md): complete feature scope and user journeys.
- [Workspace / rename deep dive](WORKSPACE-AND-BULK-RENAME.md): pane interactions, naming DAG, A21–A32.
- [Drive / Docs contract](GOOGLE-DRIVE-INTEGRATION.md): P0 destination routing and D01–D20.
- [Hardened implementation plan](HARDENED-IMPLEMENTATION-PLAN.md), [security](../../android-transfer/SECURITY.md): architecture, trust, SAF, queue and proof design.
- [Critical review](CRITICAL-REVIEW.md), [second pass](SECOND-PASS-DECISIONS.md), [test matrix](TEST-AND-EVIDENCE-MATRIX.md): adverse cases and existing design decisions.
- [Release evidence ledger](RELEASE-READINESS.md): authoritative *verified* gate state.
- [Live native queue](../../android-transfer/WORK-QUEUE.md): authoritative ranked *next jobs*.

For conflicting statements use: **observed exact-head source/tests/evidence for implementation truth; this contract plus detailed linked specs for requirements; ledger for gate claims; queue for immediate execution order.** Historical October 23 design sections remain useful requirements but do not override later merged code. Changes to beta scope require edits to this contract, corresponding detailed spec/acceptance row, queue and ledger if evidence genuinely changes. Avoid repeating PR histories in the queue.
