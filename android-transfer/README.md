# Expository Transfer · first native implementation

The web Expository explorer remains **strictly read-only**. G2's first *live file* capability in stacked PR #33 is **one device document → another user-created SAF document** using the system picker, no existing-file overwrite or source deletion. Android debug APK compilation passed [run #40114978482](https://github.com/gamesbyian/Expository/actions/runs/40114978482), SHA-256 `50bfdf1f65b22f3b372ccacc9bb6b9d4911a234b7eb7229610d9dbb5d9a951df`. Physical-device and provider testing remain pending. This is a separate native Kotlin/Compose Gradle project under `android-transfer/`, portable to a dedicated Android repository. It is the first **actual source code** beyond the G0–G9 product plan, and is deliberately not a working file-transfer app yet.

- `core/planner`: pure JVM module. Immutable transfer and rename proposals, explicit capability proofs, source/destination identity, SHA-256 digest, same-namespace Move fast path only, conservative case/Unicode collisions, temporary-hop rename graph, journal state guard. No I/O.
- `app`: native Android/Compose developer-preview shell showing synthetic items and a nonexecuting Copy preflight. No internet, file-grant, OAuth, storage or server permission.
- `AGENTS.md`: module ownership and security constraints.
- `core/engine`: pure JVM bounded-memory stream copy, injected adapters, fail-closed drift and explicit byte-readback proof with fault-injection cases; foreground-only with no durable recovery.
- `adapter/sftp`: Apache MINA SSH/SFTP read/list module with mandatory explicit SHA-256 host-key pin; synthetic ephemeral-server correct-key and changed-key rejection plus file read passed [run #40117260270](https://github.com/gamesbyian/Expository/actions/runs/40117260270), pending rebased verification and Android integration.
- `adapter/ftps`: explicit FTPS-only `SecureFtps` control connection, hostname/certificate checks, private TLS data protection, narrow read/list interface and conservative path checks. Controlled E3 localhost TLS lab passed [run #40125931787](https://github.com/gamesbyian/Expository/actions/runs/40125931787): untrusted certificate rejected, independently trusted certificate allowed encrypted listing and file read. No Android login UI, device transport or wider FTP interoperability claim.
- `testing/fake-endpoints`: synthetic-only local SAF/remote/Drive capability fixtures; no network or user data.
- `app/.../saf`: Android ContentResolver implementation for user-selected `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` URIs; no broad filesystem permission.
- `.github/workflows/transfer-native.yml` (repository root): changed-path JVM scenario jobs and opt-in debug APK build with SHA256 artifact evidence.

## Build and test
From this folder with JDK 17 and Gradle 8.13:
```sh
gradle :core:planner:scenarios :core:engine:scenarios :adapter:ftps:scenarios :adapter:sftp:scenarios :testing:fake-endpoints:scenarios --no-daemon
gradle -PwithAndroid=true :app:assembleDebug --no-daemon
```
The latter also requires Android SDK platform 36, Build Tools and the Google Maven artifacts. Android module is excluded from default JVM tests to avoid accidental SDK downloads. A repository relocation should preserve this Gradle root intact. Version baseline: AGP 8.11.1, Kotlin 2.2.20, Compose BOM 2025.12.00, minSdk 26/targetSdk 36. Revalidate Android 17 (API 37+) permission behavior before release.

## Evidence honesty
Pure JVM scenarios support **E1**; the opt-in FTPS localhost server suite separately provides narrowly scoped **E3** proof. G0 native Android compile and debug signing are proven by [GitHub Actions run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212), source SHA `1329362c788eea9c028cebde95ea67f7d1f6e7a2` and APK SHA256 `d6301095b09f7b0f4a050ccb62e8e29c255883572db889fb56e1486a81e8528d`. This does **not** satisfy dedicated repository, device, or independent privacy proof. Android picker code exists but lacks E2/E4 device acceptance; no device screenshots, TalkBack review, physical protocol/Drive acceptance, persistent executor, production server test or installed beta is claimed. Do not mark G1 complete until performance/fuzz and crash-reconciliation coverage meet the release ledger.

## Next functional slice
Wire both simple browse actions and split workspace to the same immutable plan; implement real SAF file picker/tree behavior with capability probes and typed non-success receipts; then secure SFTP/FTPS connectors and durable journal. Real Google Drive/Docs P0 remains independently gated by actual account/provider behavior.
