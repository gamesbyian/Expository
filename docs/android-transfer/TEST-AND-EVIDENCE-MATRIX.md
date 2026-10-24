# Expository Transfer: implementation proof matrix, v2

**Status:** test design and evidence gates only, October 23, 2026. No Android Transfer APK exists yet. Read [second-pass decisions](SECOND-PASS-DECISIONS.md) and [hardened plan](HARDENED-IMPLEMENTATION-PLAN.md) first.

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

## Minimal journal acceptance

Every action persists its `planDigest`, source/destination identities, adapter capability fingerprint, conflict decision, write-ahead mutation state, attempted operation ID, observed result, verification proof/evidence, and any uncertainty explanation. No UI completion state can be greener than the journal's proof grade.

For folder operations, the journal stores a manifest of discovered children and records whether recursion is incomplete. For remote rename cycles it records temporary staging paths **before** issuing mutating commands. For Google resumable sessions, store only necessary protected session state, never a bearer-like upload URL in telemetry or public logs.

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

Start Drive provider capability investigation during G0/G1 so API constraints shape the architecture, even though full G8 delivery follows queue foundations.

**Stop ship:** silent overwrite/delete, any misreported Save/Drive confirmation, ambiguous operation auto-replayed, incorrect sort-based renumbering, unverified broad permission escalation, drag-only critical action, background transfer claimed durable when grant expired, or a screenshot/artifact containing credentials.
