# Expository Transfer: acceptance, evidence and release ledger

**Current stage:** G0/G1 engineering started on [draft PR #32](https://github.com/gamesbyian/Expository/pull/32); a Kotlin/Compose native shell and pure JVM planner exist, and a **debug-only APK compile with SHA-256 provenance is verified**, but **no physical device installation or live protocol/SAF/Drive validation**. This ledger intentionally distinguishes in-progress code from acceptance. This ledger is the completion authority for Android Transfer, separate from the web Expository queue. Review [critical findings](CRITICAL-REVIEW.md), [technical plan](HARDENED-IMPLEMENTATION-PLAN.md), and [original acceptance A01–A32](PRODUCT-PROPOSAL.md).

## Evidence classes (do not confuse them)

| Level | Permitted claim | Not a substitute for |
|---|---|---|
| E0 · Proposed | requirement, diagram, reference design, technical decision | Any code |
| E1 · Pure planner exercised | deterministic Kotlin/JVM tests against fake location capabilities and in-memory names | Android framework/provider behavior |
| E2 · Android emulator exercised | Compose screen, OS picker and fake adapter integration on specified API/viewport | Real providers/physical gestures |
| E3 · Controlled protocol lab | actual FTP/FTPS/SFTP server responses, TLS/SSH and fault injection | User's production server, hostile unknown servers |
| E4 · Real provider/device | physical device + real scoped SAF/Drive account/file, observed permission/state | Broad compatibility across all devices |
| E5 · Independent beta acceptance | signed sideload APK build provenance, cross-protocol release matrix, a11y and crash recovery proof | Guarantee against every future bug |

A **green browser/PWA test for the read-only Expository site does not count** for any Android Transfer gate.

## Gate board

| Gate | State | Evidence required |
|---|---|---|
| G0 · separate Android source, guide, lean CI | IN PROGRESS · draft PR #32; [run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212) signed debug build witnessed, separate-repo/physical proof outstanding | Native repo, no credentials, signed debug-build reproducibility plan |
| G1 · pure operation plan/rename graph | IN PROGRESS · E1 18 planner scenarios + 1 synthetic fixture suite green in [run #40071877631](https://github.com/gamesbyian/Expository/actions/runs/40071877631); G1 fuzz/fault proof pending | Property/fuzz collision cases; plan-version identity and staged cycles |
| G2 · fake device and Compose journeys | NOT STARTED | Owned emulator recordings/screens, a11y labels, native picker contracts |
| G3 · trusted FTP/FTPS/SFTP protocols | NOT STARTED | real test server fixtures, host-key/cert rejection and retry logs |
| G4 · scoped Android SAF file/folder I/O | NOT STARTED | permission revocation, provider URI remap, nonseekable streams |
| G5 · durable queue/receipt proof | NOT STARTED | process killed at every journal phase, zero false-success |
| G6 · six directional pane cases + new-folder move | NOT STARTED | drag/action parity, same vs different server relay/move |
| G7 · compact bulk renamer and recovery | NOT STARTED | 10k virtualized preview, swaps, crash/undo and case/Unicode |
| G8 · P0 Drive/Docs destination and handoff | NOT STARTED | real local/remote → Drive SAF/API proof, supported-file native Google Doc creation, Docs external open, copy vs conversion vs handoff receipts; D01–D20 |
| G9 · independently reviewed Android beta | NOT STARTED | signed APK, A01–A32/D01–D20/T01–T12, 200% text, TalkBack |

Do not mark a gate complete unless the precise PR/commit, test run IDs, actual build/device identity (no serials/PII), screenshot/protocol artifacts and known limits are attached. The integration owner must verify the evidence exists.

## Native bootstrap evidence, 24 October 2026

- Draft source: [PR #32](https://github.com/gamesbyian/Expository/pull/32), Android debug-build source SHA `1329362c788eea9c028cebde95ea67f7d1f6e7a2`.
- **Built, signed debug APK only:** [run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212), job `android-debug`, artifact `expository-transfer-developer-debug-apk` (artifact #12552050075); `app-debug.apk` SHA-256 `d6301095b09f7b0f4a050ccb62e8e29c255883572db889fb56e1486a81e8528d`. SHA was independently recalculated against downloaded ZIP bytes.
- **Pure JVM tests:** [run #40071877631](https://github.com/gamesbyian/Expository/actions/runs/40071877631) at source SHA `613657a38b37038a06721d4c31ed7e55fd5be0fe`: 18 planner cases and synthetic local/remote/Drive fixture cases succeeded. Existing Expository checks also passed [run #40071877604](https://github.com/gamesbyian/Expository/actions/runs/40071877604).
- **Evidence ceiling:** E1 for planners, plus a reproducible *compilation* artifact. E2 emulator UX, E3 protocol lab, E4 real Drive/device, E5 independent beta are **not obtained**. Debug keystore is ephemeral GitHub runner state, unsuitable for production upgrade identity. Existing Android application remains synthetic and cannot transfer real user files.
- **Unresolved G0:** dedicated Android repository, independent privacy/security review, target-SDK/API-37 LAN entitlement checks, and installed-device exercise. This gate is deliberately **not marked complete**.

## Release-stopper sampling matrix

| Scenario / contract | Gate | Must fail closed as |
|---|---|---|
| RNFR accepted and RNTO response lost | G3/G5/G7 | `UNCERTAIN`, actual old/new remote state reconciliation |
| Same-server Copy invokes Rename | G1/G6 | Planner refuses destructive optimization |
| Cross-server Move loses network after destination bytes | G5/G6 | Source retained, proof incomplete, explicit retry/review |
| USB/local SAF provider returns new URI on rename | G4/G7 | Map new URI, halt stale operations |
| User revokes folder grant mid-job | G4/G5 | `NEEDS_PERMISSION`, no broad storage escalation |
| SFTP host key changes | G3 | Trust-blocked; no unchecked connection |
| FTPS data channel lacks required TLS protection | G3 | Fail protocol handshake, no insecure fallback |
| 40 episode rename numbers from hidden sort | G7 | Frozen selection order visible in preview, same at apply |
| A↔B rename cycle receives partial failure | G7 | Temp and per-item phase journal, uncertain/partial recovery |
| Provider lacks Drive writable tree | G8 | Verified Drive API choice or blocked; Share labeled handoff only |
| Drive files.create result lost after upload | G8 | Reconcile file ID/upload session before creating another |
| Create native Google Doc from unsupported MIME | G8 | Refuse or Save original; no automatic conversion |
| `ACTION_SEND` / `ACTION_VIEW` activity opened and immediately returned | G8 | `HANDED_OFF_UNVERIFIED` / `OPENED_EXTERNALLY`; never `DESTINATION_CONFIRMED` |
| Google Doc native conversion returned ID but no equal byte checksum | G8 | `GOOGLE_DOC_CREATED` with explicit conversion disclaimer; do not claim byte equivalence |
| Local or FTP remote selection lacks visible Google options | G2/G8 | Fail release journey gate; both sources need first-class actions and 48dp keyboard/TalkBack alternatives |
| Pair on 393dp with 200% text | G2/G6 | Single-pane accessible fallback, not clipped Apply |
| Large-file transfer reports 100% on socket writes alone | G3/G5 | Transfer still VERIFYING; no Completed without allowed proof |
| Device process killed after successful rename before journal ack | G5/G7 | Uncertain until stat/reconciliation; no blind replay |
| User uses plain FTP without checking warning | G3 | Security disclosure and explicit opt-in required |

**Google destination acceptance:** [D13–D20](GOOGLE-DRIVE-INTEGRATION.md#p0-acceptance-extension-d13d20) additionally require validated local/remote → native Docs conversion, external editor vs Sharesheet behavior, account/provider variation, and truthful receipt semantics. All are **NOT STARTED** until native Android and provider-device evidence exists.

## Live work entrypoints

- [#25 · G0 native source and privacy/CI foundation](https://github.com/gamesbyian/Expository/issues/25)
- [#26 · G1 deterministic planner and rename graph](https://github.com/gamesbyian/Expository/issues/26)
- [#27 · G8 real Drive/Docs provider and OAuth proof](https://github.com/gamesbyian/Expository/issues/27)
- [#28 · G2/G6 Compose picker-first adaptive workspace](https://github.com/gamesbyian/Expository/issues/28)

Implementation began in draft PR #32. The issue descriptions remain acceptance contracts, **not proof that either G0 or G1 has passed**. A separate native repository remains the target: the temporary self-contained `android-transfer/` Gradle root can be relocated without importing writer functionality into the read-only web application.

## Immediate next agent tasks (ordered)

1. Establish **separate native app source** and a concise Android-specific `AGENTS.md`, security ownership and CI, with a fake server and fake SAF provider for safe automation.
2. Implement **pure Kotlin operation planner first**: immutable references/plan digest, capability unknown/unsupported logic, collision DAG and temp names, version/fingerprint revalidation. Do not let Compose code directly issue destructive mutations.
3. Create device and server fixture harnesses while the first screens are prototyped. For all selected-item actions, compare touch drag, long press and button: they must create identical preflight.
4. Validate **Google Drive provider capability on physical device** before designing the destination picker as if Drive were necessarily present; prove OAuth `drive.file` parent write via native Drive API fallback.
5. Only after G1/G2 proof, add live SFTP/FTPS sessions and a journalled persistent queue. Gate the signed beta on G9.

## Release rule

The badge `Beta ready` is prohibited while any P0 gate is NOT STARTED, UNSUPPORTED or UNCERTAIN. No feature is accepted because it is attractive in a mock. The strong product promise is a **correct and inspectable change to user-authorized files**.
