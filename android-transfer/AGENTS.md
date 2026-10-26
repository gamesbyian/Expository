# Expository Transfer: native-agent operating instructions

Expository Transfer is the **Android-native** writing app under `android-transfer/`. Root Pages `index.html` stays a **separate read-only GitHub browser**. The app is a developer preview, not beta-ready. This guide is a stable router, not a diary of completed PRs.

## Read and reconcile in this order

1. Inspect live `main`, open PRs/issues, relevant CI failures and the exact implementation/tests. Do not rely on dated checkpoints.
2. [Ranked native workboard](WORK-QUEUE.md): immediate jobs and known blockers. Highest-ranked runnable **unowned** slice first.
3. [Current product contract](../docs/android-transfer/CURRENT-CONTRACT.md): beta P01–P12 commitments and operation matrix.
4. [Release-readiness ledger](../docs/android-transfer/RELEASE-READINESS.md): only authoritative G0–G9 state/evidence. [Hardened architecture](../docs/android-transfer/HARDENED-IMPLEMENTATION-PLAN.md), [workspace and rename](../docs/android-transfer/WORKSPACE-AND-BULK-RENAME.md), [Drive/Docs](../docs/android-transfer/GOOGLE-DRIVE-INTEGRATION.md) and [security](SECURITY.md) define detailed contracts.
5. Read [matrix](../docs/android-transfer/TEST-AND-EVIDENCE-MATRIX.md) and [external operations](../docs/android-transfer/EXTERNAL-SMOKE-OPERATIONS.md) only when applicable.

## Immutable safety rules

- Native credentials, server names, private filenames, OAuth/session tokens and user bytes never appear in source, public CI logs/artifacts, screenshots or shared web app. No shared web GitHub tokens. Device-local protected saved profiles require a real Keystore-based design before implementation. **No current profile vault is claimed.**
- No `MANAGE_EXTERNAL_STORAGE`, guessed SAF filesystem paths, assumed persistent URI grants, trust-all certificates, ignored SFTP host key, automatic plaintext FTP fallback or hidden Google OAuth scope expansion. Android target currently 36; test Android 17 LAN `ACCESS_LOCAL_NETWORK` before upgrading target to 37.
- UI, picker, drag and accessibility actions produce the same immutable, reviewed operation plan. Revalidate right before mutation; journal before changes. Copy preserves originals. **Never optimize Copy into remote rename.** Same-server Move requires verified authority/capability; cross-authority Move needs verified Copy then separately approved source deletion.
- On ambiguous write/rename/commit/Drive create **stop and reconcile**, never auto-retry or delete unknown staged data. Receipts may claim `CONTENT_HASH_VERIFIED` only on real comparable content proof; Share/External Open are handoffs, not Drive/Docs saves.
- The existing `TransferActivity` switches Local/SFTP/FTPS *cards*. It is **not** the promised independent Local/Local, Local/Remote, Remote/Remote pane workspace. SFTP rename currently previews only. Do not relabel either as delivered.

## Implement, verify, integrate

- Pick one independently testable vertical slice. Reuse pure Kotlin planner under `core/planner`, write-ahead attempt journal in `core/engine`, scoped Android SAF and trusted `adapter/sftp`/`adapter/ftps`. Source `android-transfer/` must remain relocatable to a native repo; do not mutate web explorer to ship transfer features.
- Default **cheap**: from `android-transfer/`, JDK 17 / Gradle 8.13, run `gradle :core:planner:scenarios :core:planner:directoryScenarios :core:planner:moveScenarios :core:engine:scenarios :core:engine:journalScenarios :core:engine:journaledCopyScenarios :adapter:ftps:scenarios :adapter:sftp:scenarios :testing:fake-endpoints:scenarios --no-daemon` as relevant; Android code also needs `gradle -PwithAndroid=true :app:assembleDebug --no-daemon` with SDK 36. Prefer only affected tasks locally; the full command is for broader regression.
- Opt-in workflows require review. Owner-authored PR comment `/transfer-native apk-pr` compiles immutable same-repo PR head; `/transfer-native emulator-pr <ClassName>` runs the allowlisted targeted API 35 suite, or omit class for full expensive suite. These **do not** use production server credentials. Trusted-main-only `/transfer-native ftps-lab`, `sftp-lab`, `apk`, and `emulator` serve controlled/provenance jobs. Consult workflow YAML/operations doc for exact authorization before running; no manual live mutations against public demos. One relevant scoped test is better than repeated broad retries.
- Treat **E1** pure/JVM, **E2** actual Android emulator (including disposable picker/providers), **E3** controlled protocol/approved external *read-only* compatibility, **E4** real authorized provider/physical device and **E5** independent signed beta as separate. Every acceptance note needs tested code SHA, workflow ID, test scope, inspected assertion/result, limitations. A successful APK build is compile evidence only. A `skipped` unrelated issue-comment workflow is not a failing test.
- Current open [PR #103](https://github.com/gamesbyian/Expository/pull/103) has an **unaccepted real DocumentsUI race test** due to fixture setup; a merged synchronous share-epoch guard has independent targeted E2. Repair test prerequisites or close/supersede responsibly; don't claim full race proof.
- Finish each PR: review concurrent changes, test exact head, merge only qualified changes, update ranked queue/ledger *without duplicating history*, link evidence, hand off next runnable step. Do not open routine planning-only PRs when a functional slice can be delivered.

**Do not claim:** physical Pixel, TalkBack, live Android SFTP upload, real Google Drive write, native Docs conversion, complete dual panes, executed remote bulk rename or process-death recovery without observed proof.
