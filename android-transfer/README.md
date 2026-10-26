# Expository Transfer · native Android developer preview

The web Expository explorer remains **strictly read-only**. Expository Transfer is a separate Kotlin/Compose Android-native developer preview under `android-transfer/`, portable to a dedicated repo. The first real local SAF Copy came from [merged PR #33](https://github.com/gamesbyian/Expository/pull/33) and is now joined by secure SFTP and FTPS file operations. Actual API 35 emulator tests verify synthetic local MediaStore provider copying, cancellation and the system Open Document picker cancel path ([six-test run #40154825166](https://github.com/gamesbyian/Expository/actions/runs/40154825166)). **A complete user-picked Save As, real Google Drive provider, physical device and signed beta remain unverified.** The app is functional experimental code, not yet release-ready.

- `core/planner`: pure JVM module. Immutable transfer and rename proposals, explicit capability proofs, source/destination identity, SHA-256 digest, same-namespace Move fast path only, conservative case/Unicode collisions, temporary-hop rename graph, journal state guard. No I/O.
- `app`: native Kotlin/Compose developer preview with real Local SAF Copy, pinned SFTP browse + SAF Save As/exclusive Upload and now **FTPS explicit/implicit TLS browse → one user-created SAF file Save As**. The new FTPS path checks MLST metadata, journals attempts and verifies provider bytes when possible, compiled in [PR-head Android run #40139897851](https://github.com/gamesbyian/Expository/actions/runs/40139897851). INTERNET only; no broad storage, saved account or remote password; physical Android/provider proof remains pending. This developer UI also contains a **non-destructive SFTP bulk rename preview** (merged [#59](https://github.com/gamesbyian/Expository/pull/59)), with server-side Apply disabled until journaled rename execution is verified.
- `AGENTS.md`: module ownership and security constraints.
- `core/engine` also contains a versioned SHA-256-integrity-checked atomic attempt journal and 12 pure Kotlin crash/corruption scenarios. The app has a `noBackupFilesDir` Android AtomicFile adapter; all active one-file foreground Copy routes use it since PR #42. There is still no independently verified device restart/resume/remote reconciliation.
- `core/engine`: pure JVM bounded-memory stream copy, injected adapters, fail-closed drift and explicit byte-readback proof with fault-injection cases; foreground-only with no durable recovery.
- `app/.../sftp`: PR #37 foreground SFTP-to-new-SAF-file adapter reuses immutable planner, remote stat recheck and read-back digest; debug APK compile and pinned synthetic lab passed [run #40127485028](https://github.com/gamesbyian/Expository/actions/runs/40127485028), physical-device/provider validation still outstanding.
- `app/.../sftp/SftpSameServerMove`: merged PR #47 wires explicit one-file Move to direct SFTP rename, version/listing checked immutable plan, private atomic journal, and old/new metadata proof. Eight planner and controlled collision/refusal/bytes tests passed [run #40137145475](https://github.com/gamesbyian/Expository/actions/runs/40137145475), APK compiled [run #40137278521](https://github.com/gamesbyian/Expository/actions/runs/40137278521); no general server/device acceptance.
- `app/.../sftp/SftpNewFolder`: merged PR #46 adds a planner-gated, journaled direct SFTP MKDIR action with stat verification; synthetic live server acceptance and APK compile [run #40136358327](https://github.com/gamesbyian/Expository/actions/runs/40136358327). No device or Move acceptance.
- `adapter/sftp`: Apache MINA SSH/SFTP read/list module with mandatory explicit SHA-256 host-key pin; synthetic ephemeral-server correct-key and changed-key rejection plus file read passed [run #40117260270](https://github.com/gamesbyian/Expository/actions/runs/40117260270), reconfirmed on rebuilt main-backed [run #40126372217](https://github.com/gamesbyian/Expository/actions/runs/40126372217); Android integration still pending.
- `adapter/ftps`: verified TLS certificate/hostname and PROT P for explicit/implicit FTPS, CWD + MLSD listing, non-authoritative legacy LIST **preview only**, MLST individual-file observation and read with final FTP acknowledgement. Controlled encrypted MLST/list/read + bad-cert rejection passed [#40139120011](https://github.com/gamesbyian/Expository/actions/runs/40139120011). Live owner Seedhost explicit and independent Wing implicit protected MLSD listings passed; Rebex's known sample RETR ends in 425, tracked in #54. No plaintext downgrade, Android physical acceptance or broad server compatibility claim.
- `testing/fake-endpoints`: synthetic-only local SAF/remote/Drive capability fixtures; no network or user data.
- `app/.../saf`: Android ContentResolver implementation for user-selected `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` URIs; no broad filesystem permission.
- `.github/workflows/transfer-native.yml` (repository root): changed-path JVM scenario jobs and opt-in debug APK build with SHA256 artifact evidence.

## Build and test
From this folder with JDK 17 and Gradle 8.13:
```sh
gradle :core:planner:scenarios :core:planner:directoryScenarios :core:planner:moveScenarios :core:engine:scenarios :core:engine:journalScenarios :core:engine:journaledCopyScenarios :adapter:ftps:scenarios :adapter:sftp:scenarios :testing:fake-endpoints:scenarios --no-daemon
gradle -PwithAndroid=true :app:assembleDebug --no-daemon
```
The latter also requires Android SDK platform 36, Build Tools and the Google Maven artifacts. Android module is excluded from default JVM tests to avoid accidental SDK downloads. A repository relocation should preserve this Gradle root intact. Version baseline: AGP 8.11.1, Kotlin 2.2.20, Compose BOM 2025.12.00, minSdk 26/targetSdk 36. Revalidate Android 17 (API 37+) permission behavior before release.

A focused Android PR-head debug build of the rename UI passed
[run #40140507004](https://github.com/gamesbyian/Expository/actions/runs/40140507004).
An independent opt-in Android API 35 emulator Compose smoke was introduced by
[PR #61](https://github.com/gamesbyian/Expository/pull/61); a workflow
invocation is not a passing runtime/device verdict.

## Evidence honesty
Pure JVM scenarios support **E1**; the opt-in FTPS localhost server suite separately provides narrowly scoped **E3** proof. G0 native Android compile and debug signing are proven by [GitHub Actions run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212), source SHA `1329362c788eea9c028cebde95ea67f7d1f6e7a2` and APK SHA256 `d6301095b09f7b0f4a050ccb62e8e29c255883572db889fb56e1486a81e8528d`. This does **not** satisfy dedicated repository, device, or independent privacy proof. Android picker code exists but lacks E2/E4 device acceptance; no device screenshots, TalkBack review, physical protocol/Drive acceptance, persistent executor, production server test or installed beta is claimed. Do not mark G1 complete until performance/fuzz and crash-reconciliation coverage meet the release ledger.

## Android provider acceptance (synthetic, no personal files)

[Run #40153876798](https://github.com/gamesbyian/Expository/actions/runs/40153876798)
executed five API 35 instrumented tests, including two that copy and
interrupt actual app-owned Android MediaStore `content://` streams. They
verify destination byte readback, original preservation, and the persisted
`VERIFIED` / `UNCERTAIN` AtomicFile journal states. Fixture documents are
marked pending and cleaned up by their exact URIs. This is E2 real emulator
provider evidence **without launching either system document picker** and
without using private files, a cloud account, remote server or physical
device. The signed developer debug APK is not a production release.

## First actual Android system picker interaction (E2)

[PR #75](https://github.com/gamesbyian/Expository/pull/75) added a
nondestructive UI Automator check of the actual Android
`ACTION_OPEN_DOCUMENT` system picker. A checked-out immutable PR head was
compiled (run #40154800622), then [API 35 emulator run
#40154825166](https://github.com/gamesbyian/Expository/actions/runs/40154825166)
passed six tests. Its new scenario opens the system picker, presses Back,
observes the cancelled result in Expository, and verifies that Save copy
remains disabled without a selected source. The test opens no personal file
and performs no document write. PR #76 introduced the reusable trusted-main
owner-comment `/transfer-native emulator-pr` workflow for this premerge
evidence. Real source URI grants, save-picker completion, cloud providers
and physical Android remain unverified.

## Active implementation frontier (26 October 2026)
The existing Local/SFTP/FTPS navigation selects **cards**, not two independently navigable panes. Secure SFTP/FTPS protocol foundations and a write-ahead attempt journal **already exist**; do not rebuild them. Next work is enumerated in the [ranked native workboard](WORK-QUEUE.md) and specified by the [current contract](../docs/android-transfer/CURRENT-CONTRACT.md): advance actual independent Local/Local, Local/Remote and Remote/Remote pane state, SAF folder trees, journaled queue/recovery, executed (currently preview-only) bulk rename, and authentic Google Drive/Docs destinations. Preserve honest E1–E5 distinctions in the [release ledger](../docs/android-transfer/RELEASE-READINESS.md).
