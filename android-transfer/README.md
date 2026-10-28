# Expository Transfer · Android developer preview

Expository Transfer is the **native Android** app in this repository's `android-transfer/` directory. The repository's GitHub Pages app is a separate **read-only** repository explorer. Native Transfer currently has real scoped Android file operations and authenticated SFTP/FTPS foundations, but **is not a signed beta or a complete FTP file manager**.

**Authoritative status:** [ranked work queue](WORK-QUEUE.md), [release-evidence ledger](../docs/android-transfer/RELEASE-READINESS.md), [product contract](../docs/android-transfer/CURRENT-CONTRACT.md), and [agent instructions](AGENTS.md). Check live `main` and CI rather than inferring delivery from dated milestones.

## What is working now

- **Single view (default):** its own navigable Android Storage Access Framework (SAF) folder browser, system Open/Create pickers for reviewed file Copy, local Open and Android Sharesheet, and separate authenticated SFTP/FTPS browser cards.
- **Optional Dual view:** independently configured Local/Local, Local/Remote and Remote/Remote panes, A/B swap, active-pane tabs on phones, preserved authenticated remote sessions and a near-top **Choose Pane A/B folder** shortcut. Select 1–12 files in either Local pane for reviewed, sequential, independently journaled **Local → Local Copy** into the opposite picked directory. The first ambiguity stops the batch, and no source is silently moved or deleted.
- **SFTP:** Apache MINA is bootstrapped with app-private Android user-home/working-directory resolvers before connections; real two-session Android emulator loopback byte relay passed [#202 E2](https://github.com/gamesbyian/Expository/actions/runs/40604003394). This does not prove physical-device or third-party server behavior. SSH host-key SHA-256 pinning, browsable 200→2000 listings, foreground Save As, bounded 1–12-file local→SFTP reviewed uploads, reviewed 1–12-file SFTP→opposite Local folder Copy (per-file fresh preflight, journal and scoped byte readback), **one-file SFTP→SFTP Copy between separately authenticated dual panes** (device-relayed, journaled, exclusive create, SHA-256 readback), guarded single-file on-server Move and New Folder. Source bytes are preserved for Copy; remote destination writes use no-overwrite semantics and durable attempt records.
- **FTPS:** explicit and implicit TLS-validated browsing/Save As, metadata checks, progressive encrypted listings and protected data connections. Plain FTP remains intentionally disabled pending secure opt-in UX. No automatic plaintext fallback.
- **New Folder (local):** in a chosen writable SAF folder, review the name and collision-free complete parent listing, then create a **new** direct-child directory with write-ahead journal and exact post-create metadata verification.
- **Safety and preview tools:** app-private AtomicFile recovery index, cancellation/uncertain receipts, honest read-back verification, preview-only SFTP bulk renamer and offline immutable planner/replay-policy scenarios. A pure JVM per-step journaled bulk-rename executor now stops on uncertain replies and parent drift, but it is **not connected to a real SFTP/SAF rename adapter or Apply UI** until stable remote child identity and true atomic no-replace semantics are demonstrated.

The optional **Local/Remote app-session access gate** has only a pure policy foundation ([#155](https://github.com/gamesbyian/Expository/pull/155)). The native BiometricPrompt/device credential callback, UI privacy gate and consented saved-profile feature are **not yet implemented**. A separate on-device AndroidKeyStore AES-GCM record-storage primitive exists for future integration, without saved logins or biometric enforcement. Existing connection passwords stay in memory rather than being saved between launches. See [session authentication design](../docs/android-transfer/SESSION-AUTHENTICATION.md).

## Download an experimental APK

For a reproducible CI-built developer APK, open the [latest successful owner-triggered Android debug build](https://github.com/gamesbyian/Expository/actions/runs/40463621576) and download the `expository-ftp-developer-debug-apk` artifact. New builds contain `expository-ftp-developer-debug.apk` and `SHA256SUMS.txt`. GitHub retains this artifact for **five days**, so a newer owner-triggered `/transfer-native apk` run is necessary after expiry. This is debug-signed, not release-signed; it is not backed up and is not available as a permanent store listing.

When testing, use **test-owned Android folders and separately authorized disposable SFTP/FTPS accounts**. Do not enter sensitive credentials or edit important remote data in this developer preview. Local tree grants are transient; the app does not request `MANAGE_EXTERNAL_STORAGE` or a global filesystem grant. Granting a folder in Android Files is a distinct action from selecting a source file and confirming a mutation in Expository.

APK naming policy: distributable Android artifacts must include the literal `expository-ftp` in the **APK filename itself**, not just in the GitHub Actions artifact/ZIP title. Existing archived builds retain their historical names. The local Gradle output remains `app-debug.apk` until CI packages the distributable file.

## Build and verify

From `android-transfer/`, using JDK 17 and Gradle 8.13:

```sh
gradle :core:planner:scenarios :core:planner:locationScenarios :core:planner:directoryScenarios :core:planner:moveScenarios :core:planner:workspaceScenarios :core:planner:sessionAccessScenarios :core:engine:scenarios :core:engine:journalScenarios :core:engine:journaledCopyScenarios :core:engine:renameScenarios :adapter:ftps:scenarios :adapter:sftp:scenarios :testing:fake-endpoints:scenarios --no-daemon
gradle -PwithAndroid=true :app:assembleDebug --no-daemon
```

Android compilation additionally requires Android SDK platform 36, build tools 36.0.0 and Google Maven dependencies. Current baseline: AGP 8.11.1, Kotlin 2.2.20, Compose BOM 2025.12.00, minimum Android API 26, target 36. Don't infer target-37 Android 17 LAN permission acceptance from target 36.

**CI routes:** the changed-path JVM pipeline runs E1 scenarios on every native PR. Owner-authored `/transfer-native apk-pr` compiles an immutable same-repo PR head. `/transfer-native emulator-pr WorkspaceNavigationUiTest` runs the targeted Android UI suite; exact allowlisted method selectors support cost-conscious positive picker/provider tests. Owner `/transfer-native apk` on a PR comment builds `main`, publishes SHA256 and an expiring debug APK. See [agent guidance](AGENTS.md) and [CI workflows](../.github/workflows/transfer-native.yml) for authorization and provenance.

## Evidence and limitations

**Actually accepted on an Android 15/API 35 emulator:**

- A complete system Open→Create Document Copy with byte-for-byte source preservation and provider-readback proof, plus inbound Share race safety ([#103](https://github.com/gamesbyian/Expository/pull/103)).
- **Two independently real OS-picked SAF folder grants and a Local→Local document Copy**, with a verified journal receipt and preserved 131,099-byte source ([#151](https://github.com/gamesbyian/Expository/pull/151), [exact test run #40462489116](https://github.com/gamesbyian/Expository/actions/runs/40462489116), 1/1). The test uses disposable MediaStore Downloads fixtures, not fabricated picker grants.
- 20/20 Dual workspace and folder-picker shortcut UI assertions [#40458822027](https://github.com/gamesbyian/Expository/actions/runs/40458822027) and 19/19 separate New Folder UI/policy tests [#40457974299](https://github.com/gamesbyian/Expository/actions/runs/40457974299). **Real system-picked Android New Folder creation** later passed [1/1 #40583517342](https://github.com/gamesbyian/Expository/actions/runs/40583517342) on merged [#157](https://github.com/gamesbyian/Expository/pull/157), including exact provider child identity, durable verification journal and unchanged test-owned marker bytes.
- **Reviewed 1–12-file Local↔Local Copy**, with first-of-batch fresh source preflight, each operation journaled separately, actual streamed content SHA-256 provider readback and stop-on-uncertainty. Merged [#170](https://github.com/gamesbyian/Expository/pull/170) passed [1/1 positive API35 two-system-picked-tree, two-file run #40584985952](https://github.com/gamesbyian/Expository/actions/runs/40584985952) at exact source `878dc5bbe65c8e3beb4e196c4abb464c0bcb74d5`; 3–12-file and external provider E2/E4 remain untested.
- Local→SFTP batch review 14/14 UI assertions ([#139](https://github.com/gamesbyian/Expository/pull/139)) and SFTP→Local review 15/15 ([#140](https://github.com/gamesbyian/Expository/pull/140)) originally established only review behavior. Since then, [#205 E2](https://github.com/gamesbyian/Expository/actions/runs/40609736695) verified 131,109 real bytes from a test-owned Android MediaStore document into an authenticated localhost SFTP server; [#204 E2](https://github.com/gamesbyian/Expository/actions/runs/40609635841) passed 23/23 batch selection/guard regressions. Neither proves physical hardware or third-party SFTP interoperability.
- Synthetic localhost **SFTP host-key/collision** and **FTPS TLS/data-read** labs, separate from the Android UI/providing actual network from the handset.

**Still required:** real-device and third-party-provider acceptance for New Folder and multi-file SAF Copy, live Android SFTP/FTPS byte transfers, true recursive folders, remaining cross-pane Copy/Move directions and multi-file remote relay, executed/undo-safe bulk rename, durable transfer queue/recovery after app death, protected remembered credentials, actual Drive/Docs destination delivery, accessibility/responsiveness matrix, physical Android and independently signed beta. The exact evidence and gate state live in the [release ledger](../docs/android-transfer/RELEASE-READINESS.md); no release should bypass G0–G9.

The app and its Android Gradle root are intentionally relocatable; do not migrate or alter the public Pages explorer without separate approval.
