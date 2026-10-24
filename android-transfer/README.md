# Expository Transfer · first native implementation

The web Expository explorer remains **strictly read-only**. This is a separate native Kotlin/Compose Gradle project under `android-transfer/`, portable to a dedicated Android repository. It is the first **actual source code** beyond the G0–G9 product plan, and is deliberately not a working file-transfer app yet.

- `core/planner`: pure JVM module. Immutable transfer and rename proposals, explicit capability proofs, source/destination identity, SHA-256 digest, same-namespace Move fast path only, conservative case/Unicode collisions, temporary-hop rename graph, journal state guard. No I/O.
- `app`: native Android/Compose developer-preview shell showing synthetic items and a nonexecuting Copy preflight. No internet, file-grant, OAuth, storage or server permission.
- `AGENTS.md`: module ownership and security constraints.
- `.github/workflows/transfer-native.yml` (repository root): changed-path JVM scenario job and opt-in debug APK build with SHA256 artifact evidence.

## Build and test
From this folder with JDK 17 and Gradle 8.13:
```sh
gradle :core:planner:scenarios --no-daemon
gradle -PwithAndroid=true :app:assembleDebug --no-daemon
```
The latter also requires Android SDK platform 36, Build Tools and the Google Maven artifacts. Android module is excluded from default JVM tests to avoid accidental SDK downloads. A repository relocation should preserve this Gradle root intact. Version baseline: AGP 8.11.1, Kotlin 2.2.20, Compose BOM 2025.12.00, minSdk 26/targetSdk 36. Revalidate Android 17 (API 37+) permission behavior before release.

## Evidence honesty
Passing scenarios supports **E1** only. Source and build scripts support G0 engineering startup but **do not** satisfy signed debug provenance without a completed APK run, privacy review and recorded SHA. The Android shell is not G2; no real native picker, device screenshots, TalkBack review, protocol/Drive acceptance, persistent executor, production server test or installed beta is claimed. Do not mark G1 complete until performance/fuzz and crash-reconciliation coverage meet the release ledger.

## Next functional slice
Wire both simple browse actions and split workspace to the same immutable plan; implement real SAF file picker/tree behavior with capability probes and typed non-success receipts; then secure SFTP/FTPS connectors and durable journal. Real Google Drive/Docs P0 remains independently gated by actual account/provider behavior.
