# Expository Transfer native engineering

This self-contained `android-transfer/` Gradle root is a temporary isolated source area pending a dedicated native repo. Do not import app writers into the public Expository Pages `index.html`, and do not change that site's read-only permissions. This source tree must remain portable.

## Start
Read `../AGENTS.md`, `../docs/android-transfer/HARDENED-IMPLEMENTATION-PLAN.md`, `../docs/android-transfer/RELEASE-READINESS.md`, `../docs/android-transfer/GOOGLE-DRIVE-INTEGRATION.md`, and issues #25–#28. Inspect main/PRs before editing. G0 and G1 have independent acceptance; do not mark either complete merely from scaffolding.

## Invariants
- No real server credentials, account IDs, auth tokens, user filenames, or private URLs in source, synthetic tests, CI logs or APK.
- Read-only Pages app keeps its separate trust model. App must never import web tokens.
- Pure planner contains no Android types or I/O. UI/drag/button only generate a reviewable immutable plan. Executor, durable queue, live adapters and receipt grades belong to later gates.
- Unknown/stale capabilities block unsafe native moves and deletes. Copy cannot call rename. Reconciliation is mandatory for uncertain writes.
- Google Drive Save, Docs conversion, external Open and Share handoff have different destinations, confirmation and receipt meanings.
- The historical G0 shell was synthetic-only. Current G2/G4 code performs foreground SAF Copy and pinned SFTP Save As/Upload, with app-private journaled pre-write attempts merged in PR #42. Scoped local Android Open/Save, outbound Open/Share and incoming one-file Share are present; upload-to-SFTP requires explicit user action and a pinned authenticated session. Device installation, process-death recovery and provider-specific acceptance remain unproven. Do not confuse compiled APKs or synthetic server labs with real device acceptance.

## Workflow autonomy and real-server evidence
Agents may trigger the repository's opt-in workflows after reviewing their
security and scope. For a bounded read-only FTPS interoperability check, add
one exact `/transfer-smoke <profile>` comment to a PR using the connected
repository-owner GitHub identity, then inspect the resulting Actions run and
record its outcome. Public profiles and owner FTPS profile allowlists, safety
requirements, and manual SFTP fingerprint procedure are in
[`EXTERNAL-SMOKE-OPERATIONS.md`](../docs/android-transfer/EXTERNAL-SMOKE-OPERATIONS.md).
Normal PR CI stays synthetic and cheap. For local controlled protocol
proof and a developer debug APK, post one exact PR comment as repository owner:
`/transfer-native ftps-lab`, `/transfer-native sftp-lab`, or
`/transfer-native apk`. These invoke `transfer-native.yml` using reviewed
`main` only; they cannot load proposed PR code or production credentials.
For opt-in E2 Android emulator Compose interaction evidence (no remote
credentials), add the exact owner-authored PR comment `/transfer-native emulator`.
This executes the current synthetic native suite on a disposable API 35
emulator against trusted `main`: FTPS/SFTP Compose controls plus real Android
MediaStore provider copy/interruption tests. [Run #40153876798](https://github.com/gamesbyian/Expository/actions/runs/40153876798)
passed five provider/UI cases; [premerge emulator run #40154825166](https://github.com/gamesbyian/Expository/actions/runs/40154825166)
then passed six tests including actual DocumentsUI ACTION_OPEN_DOCUMENT launch
and safe cancellation. Later scoped API 35 acceptance passed a positive
Open→Create provider-copy journey [#40160714516](https://github.com/gamesbyian/Expository/actions/runs/40160714516)
and selected-document Sharesheet launch/cancel [#40246907565](https://github.com/gamesbyian/Expository/actions/runs/40246907565).
**Physical devices, real Google Drive/Docs recipients, external provider grants
and full emulator-suite regression remain unaccepted.** Runs upload sanitized reports.
It requires Android SDK/network access during runner setup but makes no FTP
connections itself. Never call this physical Pixel, TalkBack, Google Drive/Docs provider, authenticated Android SFTP upload, or release acceptance. The specific emulator tests do exercise the real Android DocumentsUI picker and disposable local MediaStore content.

For a **real API 35 emulator test of an unmerged same-repository PR**, use
the exact owner-authored comment `/transfer-native emulator-pr` on that PR.
The separately reviewed `.github/workflows/transfer-pr-emulator.yml`
resolves a fixed PR SHA, rejects forks, checks out with no persisted token,
and executes all Android instrumentation tests without server secrets.
Review exact SHA and report artifact before merging. This remains E2,
not physical device E4 or Drive acceptance.

For faster, independently scoped **same-repository PR-head Android runtime**
proof, comment `/transfer-native emulator-pr <ClassName>` where `ClassName`
is one of the explicitly allowlisted instrumentation suites in
`.github/workflows/transfer-pr-emulator.yml` (for example,
`RenamePreviewUiSmokeTest` or `AndroidContentProviderCopyTest`).
Without a class suffix, `/transfer-native emulator-pr` continues to run
the **full** suite. Exact immutable PR SHA is checked out without persisted
GitHub credentials or owner server secrets. Record which scope ran and
never cite a scoped pass as a green full regression.

For a **focused compile of the actual code on an unmerged PR**, comment
`/transfer-native apk-pr` on that PR. The separate
`.github/workflows/transfer-pr-apk.yml` resolves and verifies an immutable
same-repository head SHA, checks it out without persisted Git credentials,
compiles an opt-in developer-only APK and records its SHA. Never mistake an
APK compilation for a device install or a signed production release.
Never use these workflows for owner
media mutation, public demo writes, load tests, or claims of Android-device
acceptance. A triggered run is not a passed test; investigate failures, update
source and rerun with evidence where warranted.

## Commands
Use Gradle 8.13 / JDK 17:
`gradle :core:planner:scenarios --no-daemon` from this directory, no Android SDK necessary.
`gradle -PwithAndroid=true :app:assembleDebug --no-daemon` with Android SDK 36 installed.
A debug signing key must be ephemeral/local and never checked in. Record APK SHA256 and CI run; normal PR tests only exercise synthetic Kotlin.
