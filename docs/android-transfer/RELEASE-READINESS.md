# Expository Transfer: acceptance, evidence and release ledger

**Current stage:** Native Kotlin/Compose now supports local SAF Copy, pinned SFTP and strict FTPS sessions, durable attempt journals, preview-only bulk rename, scoped external Open/Share and single-document Share ingestion with explicit SFTP upload. API 35 emulator evidence includes positive ACTION_OPEN_DOCUMENT → ACTION_CREATE_DOCUMENT copy with byte-verified provider readback (#40160714516) and selected-file Sharesheet launch/cancel with unchanged source bytes (#40246907565). Source/readback zero-byte stall guards passed synthetic JVM fault cases (merged #96). These are scoped proofs, not physical-device E4, Google Drive/Docs account, live Android remote-network or E5 release acceptance. The public Pages explorer remains read-only.

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
| G0 · separate Android source, guide, lean CI | IN PROGRESS · merged [PR #32](https://github.com/gamesbyian/Expository/pull/32); [run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212) signed debug build witnessed, separate-repo/physical proof outstanding | Native repo, no credentials, signed debug-build reproducibility plan |
| G1 · pure operation plan/rename graph | IN PROGRESS · [run #40154374351](https://github.com/gamesbyian/Expository/actions/runs/40154374351) completed **26 E1 synthetic Kotlin scenarios**, including 10,000-item renames in original and reverse-visible numbering order, stepwise no-overwrite simulation, stale source-version invalidation and occupied unselected-name refusal (merged [PR #74](https://github.com/gamesbyian/Expository/pull/74)). Merged [#98](https://github.com/gamesbyian/Expository/pull/98) added 320 deterministic seeded batches verifying collision blocking, order and stale source drift in [E1 #40248194756](https://github.com/gamesbyian/Expository/actions/runs/40248194756). No server mutation or process-death acceptance | further randomized Unicode/collision fault injection, staged cycle failure/reconciliation and real-adapter proof |
| G2 · fake device and Compose journeys | IN PROGRESS · Positive API 35 Open→Create provider Copy [#40160714516](https://github.com/gamesbyian/Expository/actions/runs/40160714516), merged #89; selected-file Sharesheet launch, cancel and exact-byte source preservation [#40246907565](https://github.com/gamesbyian/Expository/actions/runs/40246907565), merged #93 at tested PR SHA `32d71307`. PR #103 concurrent incoming-Share Save As E2 remains unaccepted: exact-head [#40372779304](https://github.com/gamesbyian/Expository/actions/runs/40372779304) failed DocumentsUI synthetic fixture/navigation before asserting source-swap behavior, not from demonstrated wrong-byte output. A synchronous Activity-level picker epoch guard is under review in PR #108. Scoped E2 only: no recipient delivery, cloud verification, physical E4 or full device matrix. | Physical Android/provider E4, Docs/Drive, 320/393/412dp responsive/a11y and process death |
| G3 · trusted FTP/FTPS/SFTP protocols | IN PROGRESS · real [Seedhost explicit FTPS listing](https://github.com/gamesbyian/Expository/actions/runs/40138103066) (13 entries) and independent [Wing implicit FTPS](https://github.com/gamesbyian/Expository/actions/runs/40137448557) (2 entries) succeeded. Controlled [FTPS untrusted certificate reject + trusted encrypted MLST/list/file read](https://github.com/gamesbyian/Expository/actions/runs/40139120011) and [SFTP verified-key rejection/read](https://github.com/gamesbyian/Expository/actions/runs/40138224419) succeeded. Rebex explicit FTPS returned empty MLSD/LIST and a subsequent known-sample RETR final reply 425 [#40139577482](https://github.com/gamesbyian/Expository/actions/runs/40139577482), tracked in #54; strict certificate validation, PROT P and no insecure fallback remain mandatory. Native FTPS browsing compiled #40139897851; no physical-device protocol acceptance | Android test/device evidence, wide server matrix, source-verified external download and recovery |
| G4 · scoped Android SAF file/folder I/O | IN PROGRESS · API 35 synthetic MediaStore source/destination copy and interruption [#40153876798](https://github.com/gamesbyian/Expository/actions/runs/40153876798), positive real Open→Create picker bytes [#40160714516](https://github.com/gamesbyian/Expository/actions/runs/40160714516); SFTP/FTPS single-file Save As/upload built, FTPS #55 APK [#40139897851](https://github.com/gamesbyian/Expository/actions/runs/40139897851). Bounded **up-to-12-file** SFTP upload picker merged [#104](https://github.com/gamesbyian/Expository/pull/104), with final-head API 35 E2 parser/navigation [#40256345983](https://github.com/gamesbyian/Expository/actions/runs/40256345983) (artifact #12623101245) and native JVM green; its sequential per-file guarded upload has **not** run on a real server from Android. No Google Drive sync, folders, physical device or live Android remote batch proof. | real picker/provider permissions, revocation, provider change, large folders and cross-protocol device proof |
| G5 · durable queue/receipt proof | IN PROGRESS · merged #38/#42 prewrite journal and fault scenarios; Android MediaStore E2 copy and interruption verified app-private AtomicFile records as VERIFIED and UNCERTAIN [#40153876798](https://github.com/gamesbyian/Expository/actions/runs/40153876798). Scoped actual Android PR-head run [#40235019816](https://github.com/gamesbyian/Expository/actions/runs/40235019816) passed new merged [PR #80](https://github.com/gamesbyian/Expository/pull/80) assertions: startup recovery index counted a new verified receipt and separately required review for a newly interrupted attempt. No process kill/reboot, automatic resumptions, destination replay or cross-provider recovery verified | Kill at every state, reboot/provider revocation and explicit reconciliation, zero false-success |
| G6 · six directional pane cases + new-folder move | IN PROGRESS · [merged #46](https://github.com/gamesbyian/Expository/pull/46) delivers journaled native SFTP New Folder with [E1/E3 server proof #40136358327](https://github.com/gamesbyian/Expository/actions/runs/40136358327). [Merged #47](https://github.com/gamesbyian/Expository/pull/47) delivers bounded same-session, one-file SFTP Move, with [E1/E3 no-overwrite proof #40137145475](https://github.com/gamesbyian/Expository/actions/runs/40137145475) and [Android APK compile #40137278521](https://github.com/gamesbyian/Expository/actions/runs/40137278521). Merged [#106](https://github.com/gamesbyian/Expository/pull/106) adds one-shot SFTP picker launch witnesses for session/folder/source, with exact-head `67d30f7` targeted API 35 [#40373251572](https://github.com/gamesbyian/Expository/actions/runs/40373251572) and APK [#40373269147](https://github.com/gamesbyian/Expository/actions/runs/40373269147). These tests do not transfer real SFTP bytes. No physical-device or split-pane six-direction acceptance | other remote/local Move routes, split panes/drag targets, actual E2/E4 OS, interruptions, reconciliation and device evidence |
| G7 · compact bulk renamer and recovery | IN PROGRESS · [merged PR #59](https://github.com/gamesbyian/Expository/pull/59) SFTP bulk rename **preview-only** UI, and [PR #74](https://github.com/gamesbyian/Expository/pull/74) validated 10k proposal/revalidation safety in pure Kotlin E1 [#40154374351](https://github.com/gamesbyian/Expository/actions/runs/40154374351). Actual PR-head Android APK [#40140507004](https://github.com/gamesbyian/Expository/actions/runs/40140507004) compiled. UI shows at most 200 current directory items and Apply stays disabled. Seeded 320-batch E1 property-style planner coverage merged in #98. No real remote rename, physical device, high-volume UI, undo or crash acceptance | 10k virtualized/paged Compose preview, real journaled staged Apply, swaps, crash/undo, case/Unicode and a11y acceptance |
| G8 · P0 Drive/Docs destination and handoff | IN PROGRESS · Local ACTION_SEND Sharesheet [#91/#93](https://github.com/gamesbyian/Expository/pull/93) verified launch/cancel E2. Scoped ACTION_VIEW Open with [#99](https://github.com/gamesbyian/Expository/pull/99), incoming single ACTION_SEND [#100](https://github.com/gamesbyian/Expository/pull/100), explicit incoming-shared-file→pinned SFTP [#101](https://github.com/gamesbyian/Expository/pull/101) compiled [#40252360123](https://github.com/gamesbyian/Expository/actions/runs/40252360123), with E2 no-silent-upload navigation [#40252363611](https://github.com/gamesbyian/Expository/actions/runs/40252363611). #102 freezes local Save As source against Share/picker changes; normal positive-copy E2 [#40252251360](https://github.com/gamesbyian/Expository/actions/runs/40252251360). **No recipient delivery, Google Drive file write, native Docs creation, actual Android→SFTP data upload or E4 evidence**. | Verified local/remote→Drive, narrowly authorized parent, separate Docs conversion, real accounts/provider, revoked grants; D01–D20 |
| G9 · independently reviewed Android beta | NOT STARTED | signed APK, A01–A32/D01–D20/T01–T12, 200% text, TalkBack |

Do not mark a gate complete unless the precise PR/commit, test run IDs, actual build/device identity (no serials/PII), screenshot/protocol artifacts and known limits are attached. The integration owner must verify the evidence exists.

## G8 local Sharesheet handoff E2 and streaming fault E1, 25 October 2026

Selected-document ACTION_SEND from merged #91 was exercised on an API 35 emulator using a random disposable synthetic Downloads file. [Run #40246907565](https://github.com/gamesbyian/Expository/actions/runs/40246907565) passed the scoped `LocalSharesheetSmokeTest` at exact PR-head SHA `32d71307c7d24cdf354fe5c32851e42d39eff535` and retained sanitized artifact #12618958951. The earlier failing [run #40238625793](https://github.com/gamesbyian/Expository/actions/runs/40238625793) exposed an overly narrow chooser-label assertion, corrected to detect the Android resolver. Merged [PR #93](https://github.com/gamesbyian/Expository/pull/93). No recipient was chosen; this is E2 handoff proof only, not successful Drive delivery or native Docs conversion.

Independent synthetic E1 [run #40247209342](https://github.com/gamesbyian/Expository/actions/runs/40247209342) passed the bounded non-progressing source/readback stream guards and three fault cases in merged [PR #96](https://github.com/gamesbyian/Expository/pull/96). Neither a stalled copy nor a stalled verification may report byte-verified completion. No physical-provider proof is implied.

## G6 same-session remote Move evidence, 24 October 2026

[PR #47](https://github.com/gamesbyian/Expository/pull/47) adds an explicitly selected, one-file Move into a different folder on the **same authenticated SFTP session**, using `VERIFIED_REMOTE_RENAME`, snapshot/capability revalidation, a private atomic attempt journal before the SFTP rename, and post-operation observation of absence from the old parent and matching metadata at the new destination. No client-side payload relay, separate delete or overwrite flags. [Run #40137145475](https://github.com/gamesbyian/Expository/actions/runs/40137145475) passed 8 E1 planner cases and a controlled E3 synthetic SFTP server lab verifying existing destination refusal, both files preserved on failure, successful relocation and equal bytes. **Important limit:** metadata/stat proof is not byte-for-byte rehash in the actual Move executor, and arbitrary SFTP servers may have different rename semantics. Unknown/ambiguous results remain UNCERTAIN. Android debug APK compiled in [run #40137278521](https://github.com/gamesbyian/Expository/actions/runs/40137278521), artifact #12577146593; physical Android/device, real-server compatibility and split workspace have not passed acceptance. G6 remains IN PROGRESS.

## G6 direct server New Folder evidence, 24 October 2026

[PR #46](https://github.com/gamesbyian/Expository/pull/46) adds a first live remote directory mutation to the pinned-SFTP Compose browser. The pure immutable DirectoryCreatePlanner requires a verified authenticated namespace, a separate CREATE_CHILD capability, complete sibling listing, collision-free Unicode name and unchanged plan digest. The executor commits a private attempt journal before issuing server-side MKDIR, then probes remote stat. A lost reply or failed post-stat remains uncertain, no blind retries or deletion. [Run #40136358327](https://github.com/gamesbyian/Expository/actions/runs/40136358327) passed 9 E1 scenarios, disposable live SFTP MKDIR/post-stat/duplicate refusal, and Android developer debug APK compilation; artifact #12577700165. **No physical-device or real-server mutation acceptance, server-side Move, pane UX or crash-proof reconciliation. G6 remains IN PROGRESS.**

## G5 runtime write-ahead journal evidence, 24 October 2026

[PR #42](https://github.com/gamesbyian/Expository/pull/42) wires app-private Android AtomicFile journals into local SAF Copy, pinned SFTP Save As and exclusive SFTP Upload. Each successful immutable-plan preflight records CONFIRMED and ATTEMPT_RECORDED before opening any stream. Lost journal acknowledgements, interrupted bytes, stale plans and duplicate attempts cannot silently produce VERIFIED. An app startup status card classifies private records as uncertain, locally verified, not-started or damaged without retrying or deleting sources. Twelve journal and eight journaled-stream fault scenarios passed alongside debug APK compilation in [run #40135499036](https://github.com/gamesbyian/Expository/actions/runs/40135499036), source `dcc87187e515960032ac9c11b5f29b0d6c0f87f6`, artifact #12576413760. **Scope: E1 tests + APK compilation only.** There is no physical Android kill/reboot or real provider evidence, and records contain no endpoint identities necessary for automatic reconciliation; G5 remains open.

## G5 atomic attempt journal foundation, 24 October 2026

[PR #38](https://github.com/gamesbyian/Expository/pull/38) adds a versioned SHA-256-integrity-checked single-attempt journal, with no filename/credential/path persistence, strict plan digest and monotonic state. Android `AtomicFile` stores records in `noBackupFilesDir`, keyed by checked digest. Twelve JVM crash/integrity synthetic cases plus APK compilation passed [run #40128058545](https://github.com/gamesbyian/Expository/actions/runs/40128058545). Interrupted ATTEMPT_RECORDED or WRITE_ACKNOWLEDGED recover UNCERTAIN and disallow blind retries. **Not G5 accepted:** PR #42 wires Copy operations, but no process-kill/device reboot or provider-side reconciliation proof exists.

## G4 native remote Save As implementation checkpoint, 24 October 2026

Draft [PR #37](https://github.com/gamesbyian/Expository/pull/37) wires one real pinned SFTP session to an Android Compose remote browser and ACTION_CREATE_DOCUMENT, using the immutable planner, foreground stream and content-provider read-back digest. It adds standard INTERNET only; user enters a trusted fingerprint and ephemeral password. **APK compiled in [run #40127485028](https://github.com/gamesbyian/Expository/actions/runs/40127485028) at commit d723ec3, artifact #12573314373; JVM planner + live synthetic SFTP lab also passed. No emulator/physical-device or independent native flow proof.** Upload was added in merged PR #39; there remains no durable resumable queue, automatic source deletion or process restart guarantee, and the provider may leave an empty/partial document after an interrupted copy. E2/E4 not claimed.

## G3 SFTP host-key evidence, 24 October 2026

[PR #35](https://github.com/gamesbyian/Expository/pull/35) was reconstructed as one clean commit [6666f54](https://github.com/gamesbyian/Expository/commit/6666f540e0e3e550b4c59d21d2011697cb7c0ef4) atop the merged FTPS security fix to eliminate duplicate FTPS history. Apache MINA pinned SSH public key verification requires an explicit SHA-256 pin; an unrecognized key never auto-accepts. Original [run #40117260270](https://github.com/gamesbyian/Expository/actions/runs/40117260270) and rebased [run #40126372217](https://github.com/gamesbyian/Expository/actions/runs/40126372217) passed the ephemeral localhost SFTP correct-key authentication/directory/file-read and changed-key rejection. This is bounded **E3 for that SFTP fixture**, not Android/device acceptance, TOFU UI, recovery or G3 completion.

## G3 explicit FTPS evidence, 24 October 2026

[PR #34](https://github.com/gamesbyian/Expository/pull/34) restored the Python read-only loopback pyftpdlib server and a Kotlin integration harness. The initial live [run #40125781598](https://github.com/gamesbyian/Expository/actions/runs/40125781598) exposed a critical Commons Net default trust-manager defect: a self-signed certificate was accepted without CA chain verification. Fixed with JVM trust anchors alongside strict hostname verification in commit [02f97f9](https://github.com/gamesbyian/Expository/commit/02f97f9508a3fe3343b76f182f2f0c80df56f45b). Re-run [#40125931787](https://github.com/gamesbyian/Expository/actions/runs/40125931787) passed both untrusted certificate rejection and explicitly trusted authenticated TLS directory listing/read, with mandatory private data protection. This is **E3 for the bounded FTPS fixture only**, not G3 completion or Android/network/device acceptance; implicit FTPS, fallback behavior, interrupted transfers, SFTP and real server acceptance require further proof.

## G2 implementation checkpoint, 24 October 2026

Draft [PR #33](https://github.com/gamesbyian/Expository/pull/33) is stacked on G0/G1 [PR #32](https://github.com/gamesbyian/Expository/pull/32). It adds a real user-triggered `ACTION_OPEN_DOCUMENT` → `ACTION_CREATE_DOCUMENT` one-file **Copy** path, foreground ContentResolver streaming, pure JVM stream fault injection and post-write read-back SHA-256 check. The OS picker may return a different filename; the UI queries it. This is an isolated Local→Local G2 slice, **not** acceptance of split panes, remote Save/Upload, Drive destination, native document provider compatibility, lifecycle-recovery, or a durable queue. No process-death recovery, no source deletion, and never equate provider write acknowledgement with verified cloud upload. **Debug APK compiled and checksum-verified**, [run #40114978482](https://github.com/gamesbyian/Expository/actions/runs/40114978482), source tree `b3f465eececb8e6c861ce6827e3d2ae2d95ad81c`, artifact #12567594951 and APK SHA-256 `50bfdf1f65b22f3b372ccacc9bb6b9d4911a234b7eb7229610d9dbb5d9a951df`. The JVM suite passed 25 planner + 9 streaming fault scenarios + synthetic adapters in [run #40114906305](https://github.com/gamesbyian/Expository/actions/runs/40114906305). No device installation or live-provider test; APK is **not a verified working workflow**.

## Native bootstrap evidence, 24 October 2026

- Draft source: [PR #32](https://github.com/gamesbyian/Expository/pull/32), Android debug-build source SHA `1329362c788eea9c028cebde95ea67f7d1f6e7a2`.
- **Built, signed debug APK only:** [run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212), job `android-debug`, artifact `expository-transfer-developer-debug-apk` (artifact #12552050075); `app-debug.apk` SHA-256 `d6301095b09f7b0f4a050ccb62e8e29c255883572db889fb56e1486a81e8528d`. SHA was independently recalculated against downloaded ZIP bytes.
- **Pure JVM tests:** [run #40072228508](https://github.com/gamesbyian/Expository/actions/runs/40072228508) at source SHA `099e09f9054ca5a22c57a6f55b0bee4ec0e30cda`: **24 planner scenarios** and synthetic local/remote/Drive fixture cases passed, including 40-episode numbering, 500 collision cases, 10k changed-item preview, NFC descendant and snapshot/drift guards. Existing Expository checks also passed [run #40072228482](https://github.com/gamesbyian/Expository/actions/runs/40072228482).
- **Evidence ceiling:** E1 for planners, plus a reproducible *compilation* artifact. E2 emulator UX, E4 real Drive/device, E5 independent beta are **not obtained**; the subsequently added FTPS-only localhost E3 result is scoped below. Debug keystore is ephemeral GitHub runner state, unsuitable for production upgrade identity. Existing Android application remains synthetic and cannot transfer real user files.
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

## 24 October: external FTPS interoperability and PR-head Android proof

The first real external secure browsing acceptance passed on both the owner's
Seedhost account and an independent Wing demo server. Credentials remained in
Actions secrets and owner identifiers were masked. These tests enumerated
remote directory entries only; they did not download user files or mutate
the S60 media tree. Rebex's public demo presented different behavior:
MLSD and the conservative LIST preview returned empty, MLST found the known
sample, but the protected RETR final reply was 425. This remains open in
[issue #54](https://github.com/gamesbyian/Expository/issues/54), potentially
related to TLS session resumption, without claiming that cause is proved.

Owner-comment GitHub Actions now supports controlled FTPS and SFTP labs,
mainline developer debug APKs and a separate PR-head build without checkout
credentials or private server secrets. The first FTPS Android PR-head build
failed with a missing private journal provider; commit `5e0dc7f` corrected
it, and [run #40139897851](https://github.com/gamesbyian/Expository/actions/runs/40139897851)
then compiled the actual PR code and published an APK. This is build
provenance, **not** E2 emulator, E4 physical device or production release
acceptance. G3 and G4 remain **IN PROGRESS**.

## 24 October: first real Android emulator UI execution

[Workflow run #40141816359](https://github.com/gamesbyian/Expository/actions/runs/40141816359)
booted a disposable Google APIs Android 15 / API 35 emulator and executed
**three** native Compose tests successfully (log: 3 started, 3 finished;
Gradle BUILD SUCCESSFUL). Its artifact #12578544833 contains synthetic
Android test reports. The tests exercised FTPS protocol selection without
network access; SFTP rename selection and preview on a two-item synthetic
snapshot; and refusal of a truncated remote listing. This is specifically
**E2 bounded UI runtime evidence**. There was no login, socket transfer,
Android SAF picker/provider, TalkBack, IME, forced 200% text, physical Pixel
or Android 17/37 acceptance. The earlier first emulator attempt
[#40140863642](https://github.com/gamesbyian/Expository/actions/runs/40140863642)
failed before test execution due a Compose test BOM omission; PR #63
corrected it, and the PR-head dual-APK compiler in PR #64 now detects
instrumentation compile errors before emulator runs. Do not erase either
the failed precursor or the successful scoped test from the evidence record.


## 24 October: actual Android MediaStore provider-copy E2

[PR #69](https://github.com/gamesbyian/Expository/pull/69), merged as `cb8330e`, adds two instrumented cases against the **real Android MediaStore `ContentResolver`**, using disposable synthetic test-owned `IS_PENDING` files. [API 35 run #40153876798](https://github.com/gamesbyian/Expository/actions/runs/40153876798) started and completed **five** tests and reported BUILD SUCCESSFUL, artifact #12583622678. The first new case copied more than 128 KiB with independent byte-for-byte destination readback, source preservation and refusal to write a source URI onto itself. It reopened the app-private AtomicFile record as VERIFIED. The second interrupted a larger copy after at least one chunk, confirmed the source was intact and the destination partial, and reopened the journal as UNCERTAIN. Tests deleted only their synthetic fixture URIs. These are real Android/provider I/O results, not mocked JVM fakes, but the `content://` URIs were programmatically inserted; ACTION_CREATE_DOCUMENT and ACTION_OPEN_DOCUMENT were **not** executed. There was no Drive/cloud provider, revoked external grant, remote FTPS/SFTP from device or physical handset. G2/G4/G5 remain IN PROGRESS.
