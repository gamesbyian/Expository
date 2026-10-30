# Expository Transfer: implementation proof matrix, v2

**Status:** original October 23, 2026 scenario design (N01–N26), supplemented October 30 with audit scenarios N27–N34; these are specifications, **not run results**, and are not the ranked W-queue. Android Transfer code and developer debug APK now exist; exact scoped results are in the [release ledger](RELEASE-READINESS.md), and next runnable tasks in the [native workboard](../../android-transfer/WORK-QUEUE.md). Read [second-pass decisions](SECOND-PASS-DECISIONS.md) and [hardened plan](HARDENED-IMPLEMENTATION-PLAN.md) first.

| ID | Scenario | Required observed result | Evidence gate |
|---|---|---|---|
| N01 | Remote file → Android Save As; chooser canceled | No destination success, no remote mutation, clear cancellation | G2 fake endpoint + real DocumentsUI |
| N02 | Save As collides with existing Android filename | Actual returned document display name shown, never pretend overwrite | G2 device |
| N03 | Multi-file/folder download to a selected tree | Hierarchy and empty folders where provider supports them; per-item receipts and honest partial states | G2/G5 device |
| N04 | Android picker provides cloud-backed, nonseekable content URI | Upload streams rather than assuming filesystem path; resume downgraded | G3/G5 |
| N05 | Share-to-upload permission ends after process death | Reauthorization or consented capped encrypted staging; no dead-URI success | G5 device |
| N06 | Local/Local same vs different SAF providers | Provider operation only with flags/authority verified; returned replacement URI adopted | G1/G5 fake + device |
| N07 | Remote/Remote identical label but different authenticated servers | Copy relayed through device, clearly labeled network cost; no false same-server rename | G1/G4 fixture |
| N08 | Remote same-server **Copy** | Never uses FTP RNFR/RNTO; server-side copy only with proven capability or stream | G1/G4 |
| N09 | Remote same-server **Move to → New folder** | Actual server-side rename where safe; no payload transfer, with conflict preflight | G4 fixture |
| N10 | FTP rename command acknowledgment lost | UNCERTAIN, inspect both old and new before any replay | G1/G4 fault injection |
| N11 | Copy closes stream but destination readback differs | Never claim hash verified or delete source; receipt identifies proof shortfall | G1/G5 |
| N12 | Unselected rename target exists; two selected names swap | Collision blocked or staged safely with per-operation journal; no overwrite | G1/G7 |
| N13 | 40 TV episodes, numbered in user-visible sort, extension lock on | Preview exactly matches execution, no hidden mask syntax, full changed-name summary | G7 device |
| N14 | 10,000 rename preview rows at 200% text | Virtual scrolling preserves order/selection, 48dp controls and visible Apply/Cancel | G7 device/performance |
| N15 | Drag vs Copy/Move action at all pane pairings | Same immutable plan digest; drag never bypasses confirmation | G6 tests |
| N16 | Pane height shrinks due to keyboard or accessibility text | Auto one-pane tab fallback, locations and selection intact | G6 device |
| N17 | Drive app missing or Drive SAF not writable | Clearly offered scoped Drive API fallback, not false Saved result | G8 controlled account |
| N18 | Local and remote → Drive via scoped API | Created file ID, verified parent/MIME, clear proof grade and intact source | G8 real Drive |
| N19 | Drive resumable upload last-byte response lost | Query upload session; avoid duplicate file create; unknown state blocks blind replay | G8 fault injection |
| N20 | Drive upload session 404 or account revoked | Reauthorize/reconcile file existence; state remains blocked/uncertain | G8 |
| N21 | Share to Drive accepted by Android | Handoff-only receipt; no Saved claim | G8 device |
| N22 | DOCX → Google Docs conversion | Separate opt-in confirmation; importFormats and native MIME checked; original preserved | G8 real Drive |
| N23 | APK backgrounded, killed, metered transition mid-file | Queue resumes only when eligible; no fake 100%/hidden data loss | G5/G9 device |
| N24 | Source/target names and OAuth tokens in logs | Synthetic CI only; redaction checks fail when sensitive markers appear | G0/G9 |
| N25 | App protocol host key/certificate changed | Writes block pending explicit trust review; no insecure silent fallback | G3 fixture |
| N26 | Attempt Move folder into own descendant | Block in pure planner and immediately before mutation | G1/G4 |

## Audit-added negative/lifecycle scenarios (N27–N34, **not run**)

| ID | Scenario | Required observed result | Evidence gate |
|---|---|---|---|
| N27 | Cancel blocking recursive Copy during source read, destination create/write and readback; rotate/dispose Activity | Job+permit stop promptly, unknown mutation stays UNCERTAIN, no post-cancel unapproved write | E1 slow streams + E2 provider |
| N28 | Same SFTP session receives out-of-order two listings and stale picker; disconnect/reconnect | Old response never clears newer busy/status/authority; no overlap | E1 reducer + E2 UI |
| N29 | Genuine two separately OS-picked DocumentsProvider trees, nested+empty folder Copy | Both real OS grants, confirmed source hierarchy, preserved originals, independent SHA-256 destination readback | E2 exact head #292, later E4 |
| N30 | Kill/restart at journal states and corrupt protected recovery index | Failed index is REVIEW_REQUIRED, not empty; user can locate target after fresh auth; no duplicate mutation | E1 faults + E2 process kill |
| N31 | Multi-item batch where item N verified and N+1 loses create ACK | Only verified N counts advance; no auto replay; fresh listing before each subsequent item | E1 queue + E2 provider |
| N32 | Deliberately broken Compose compile, failing JavaExec assertion and artifact checksum | Default scoped CI/Gradle check rejects failures; unrelated core-only lane remains lean | PR/CI and clean toolchain |
| N33 | UI mode/picker/grant/auth on rotation, share and process death, large type/TalkBack | Display state may restore; authorization never resurrected; same reviewed plan in Single/Dual/keyboard/drag | E1/E2, device E4 |
| N34 | Real provider create race, 2k listings, 10k rename rows, signed production beta | No false no-replace/verification; measured performance only; E4 third-party and E5 independent signing | E2/E3/E4/E5 |

See [audit](ARCHITECTURE-AUDIT-2026-10-29.md) and [operation UX](OPERATION-STATE-UX.md). Each scenario requires exact code SHA, fixture, failure phase, expected/actual outcome, and PASS/FAIL/NOT RUN. No acceptance is implied by this specification.

## Minimal journal acceptance

**Two distinct records are required, not one overstuffed journal.** The existing minimal app-private `DurableAttemptJournal` persists the immutable `planDigest`, attempt ID and monotonic mutation/proof state atomically. It deliberately contains **no filename, path, account, token, URI grant or endpoint**. Keep this neutral no-secret format until a separately tested migration is approved.

A **separate, encrypted/authenticated, bounded, versioned recovery index** associates opaque attempt IDs with the minimal protected source/destination locator, adapter capability fingerprint, conflict choice, necessary folder manifest/index, step-level evidence and user-readable reconciliation context. It must be independently access-controlled, have an explicit retention/corruption policy, and never retain passwords or uncontrolled bearer URLs. For rename cycles, record needed temporary staging identities **before** mutation in this protected recovery context; for Drive resumable sessions, store only carefully scoped, protected session state. No secrets, raw names or tokens in public logs/CI/artifacts. A recovery index read failure means REVIEW_REQUIRED, not empty/success.

No UI completion state can exceed the durable verified evidence. Ambiguous mutations remain UNCERTAIN; never silently retry, delete source or call a Sharesheet a Drive Save. See [architecture ADR](ARCHITECTURE-DECISIONS.md).

## Evidence hierarchy and execution contract

**Pure Kotlin tests:** G1 plan equality, conflicts, cycle ordering, proof-state semantics and property/fault-based checks. **Native UI tests:** G2/G6 Android pickers, keyboard, high text scale and drag/button equivalence using fake adapters. **Protocol integration:** G3/G4 secure test servers, FTPS variants, SFTP host-key change and malformed server behavior. **Real provider/device:** G5/G8/G9 actual SAF providers, controlled Drive account, process death, notifications and Android versions.

Keep a machine-readable test-case identifier for each N01–N26 in the future Android project; attach commit SHA, environment, adapter/provider version and proof type. Mark `not-run` rather than pass whenever a backend is absent. Green mocked fixtures do not establish physical Pixel or real Drive acceptance.

## Build order

1. **G0** separate native repo and debug build, native AGENTS, test receipt skeleton. Initial app may have zero protocol features; say so.
2. **G1** pure planner/journal skeleton with source identities, same-server guard, explicit Copy/Move/rename rules.
3. **G2** system Save/Upload picker journeys with fake endpoints, including cancel/permission/incomplete proof.
4. **G3/G4/G5** real secure protocol transfer, remote New folder/Move, folder queue and interrupted recovery.
5. **G6/G7** adaptive any-two-pane UX and compact renamer with executable immutable previews.
6. **G8** Drive API/Docs controlled-account acceptance as a first-class destination, never handoff theater.
7. **G9** device/accessibility/privacy acceptance and signed distributable APK.

Begin/continue Drive provider capability investigation in parallel with the current workspace and recovery work; the G0/G1 initial scaffolding already exists. Real Google account/Drive provider/Docs conversion acceptance is still outstanding.

**Stop ship:** silent overwrite/delete, any misreported Save/Drive confirmation, ambiguous operation auto-replayed, incorrect sort-based renumbering, unverified broad permission escalation, drag-only critical action, background transfer claimed durable when grant expired, or a screenshot/artifact containing credentials.
