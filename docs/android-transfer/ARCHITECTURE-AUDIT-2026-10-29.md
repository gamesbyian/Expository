# Expository Transfer: independent Android architecture audit and action register

**Observed baseline:** `14bbeecee63de3e03c59cc229f36184b7aee7c78` (`main`, 2026-10-30 UTC). **Status:** evidence-backed first-pass architecture audit, NOT exhaustive line-by-line review, dynamic security certification or a claim that recommendations have shipped. **Follow-up:** [native queue](../../android-transfer/WORK-QUEUE.md), [dependency plan](EXECUTION-DEPENDENCIES.md), [architecture decisions](ARCHITECTURE-DECISIONS.md), [interaction-state contract](OPERATION-STATE-UX.md), [release ledger](RELEASE-READINESS.md). No production source was changed by this audit.

## Executive assessment and scope

The Android-native application under `android-transfer/` has useful, genuinely fail-closed foundations: pure immutable planner with opaque identity and three-state capability proof; journaled foreground Copy and separate byte/hash verification; SFTP host-key pinning; FTPS certificate/hostname checking; narrow Android permissions; synthetic content-provider and controlled protocol tests. The source of greatest architectural leverage is **route-specific operation, session, approval, and progress orchestration residing in composables**, particularly `TransferActivity.kt` (~1,254 lines at baseline) and `SftpBrowseCard.kt` (~2,036). Protect functioning transfers with tests before migrating one seam at a time. Keep the Pages GitHub explorer read-only and separate.

Inventory: `:core:planner` (identity, capability, immutable transfer/rename/recursive planning, workspace state); `:core:engine` (streaming, durable attempt journal, recursive Copy, foreground synthetic batch); `:adapter:sftp` (Apache MINA SSHD pinned connection, exclusive write, relay); `:adapter:ftps` (Commons Net FTPS and separately consented plaintext FTP browse); `:app` (Compose Single/Dual, SAF, pickers, transfer coordination, encrypted SFTP connection bookmarks); `:testing:fake-endpoints` (isolated synthetic faults). Android minSdk 26, compile/target 36; Kotlin 2.2.20, AGP 8.11.1, Java 17. No C++/JNI sources appeared in the enumerated native tree.

**Representative observations:** Single folder Copy freezes a pre-picker source witness, requires real provider grants and different authorities, prepares a recursive manifest, journals before each mutation, and verifies bytes through source/destination readback (TransferActivity:378–548; SafRecursiveTreeCopy:18–69; RecursiveFolderCopyEngine:95–224). Dual multi-file Copy explicitly re-preflights each subsequent file after the prior destination write changes the parent listing (TransferActivity:796–860); this is a contract to preserve when integrating the batch queue. SFTP Compose separately coordinates live sessions, selection, relay and picker lifecycles (SftpBrowseCard:125–209,550–1105). The recovery index counts opaque attempt digests without identifying actionable destinations.

**Evidence classes:** E1 offline Kotlin planner/engine/fault suite; E2 real Android emulator/ContentResolver and, separately, genuine OS picker; E3 isolated authenticated server/real controlled bytes; E4 third-party provider and owner-authorized physical handset/live endpoints; E5 reviewed, independently signed release. Never promote one class into another.

## Findings register

**Priority interpretation:** P0 = release acceptance blocker, not an observed exploitable vulnerability; P1 foundational correctness/reliability/testability; P2 significant consistency/security/release hardening. High = structural fact observed in code; medium = credible failure mechanism not dynamically reproduced. Each finding includes observed evidence, potential consequence, recommended correction and expected proof.

| ID / priority / confidence | Evidence at audited SHA; consequence | Correction and acceptance | Tracking |
|---|---|---|---|
| **A-01 P1 High** | `app/.../TransferActivity.kt:167–217,485–548`, `SftpBrowseCard.kt:125–209,550–1105`: multiple Compose-owned operation, picker, permit and receipt paths may diverge as the route matrix grows | Shared typed immutable action/approval/outcome coordinator; migrate one proven SAF Copy then another route; E1 plan/digest parity and Android E2 negative stale-grant test | [#324](https://github.com/gamesbyian/Expository/issues/324) |
| **A-02 P1 Medium** | `TransferActivity.kt:508–524,746–758,835–851`: blocking Copy continuation checks stop flag only, unlike Job-aware `LocalCopyCard.kt:198–205`; may continue IO after composition lifetime ends | First reproduce with slow synthetic provider. Couple Job cancellation and stop permit; verify stream closure, interrupted journal, no post-cancel unauthorized mutation | [#322](https://github.com/gamesbyian/Expository/issues/322) |
| **A-03 P1 High** | `AttemptRecoveryIndex.kt:20–85`, `VerifiedCopyBatchQueue.kt:7–18`: index exposes digests only and queue is in-memory; operator cannot identify the actual uncertain target after process death | Keep minimal journal; separate bounded protected recovery locator; operator reconcile with fresh authorization, no blind retry; crash/fault matrix E1/E2 | [#325](https://github.com/gamesbyian/Expository/issues/325) |
| **A-04 P1 High** | `TransferActivity.kt:191–217`, `SftpBrowseCard.kt:139–179`: `remember` state not fully restored on recreation, while sensitive grants/sessions must remain temporary | Split display preferences, active authority and durable operation state. Restore only safe display state; require reauthorization after process death; rotation/picker/share test | [#324](https://github.com/gamesbyian/Expository/issues/324) |
| **A-05 P0 release gate High** | [#292](https://github.com/gamesbyian/Expository/pull/292), [failed E2 #41002248669](https://github.com/gamesbyian/Expository/actions/runs/41002248669): genuine system-picked two-provider end-to-end folder Copy not accepted; JVM checks do not prove it | Diagnose window/focus vs actual proposal failure; both grants via real DocumentsUI, nested and empty folder membership, independent exact-byte readback and intact source, exact-head E2 | [#292](https://github.com/gamesbyian/Expository/pull/292), [#222](https://github.com/gamesbyian/Expository/issues/222) |
| **A-06 P1 High** | `.github/workflows/transfer-native.yml:33–55`: routine PR native job tests JVM code but Android compilation is opt-in; green CI may miss Compose failures | Path-selective default Android app compile and lint gate; deliberate failing test PR must fail, cheap pure-JVM path preserved | [#323](https://github.com/gamesbyian/Expository/issues/323) |
| **A-07 P2 High** | `core/planner/build.gradle.kts:5–59`, `core/engine/build.gradle.kts:4–51`: JavaExec main-based scenario suites are real but not inherently part of standard Gradle `check` | Wire verified scenarios into `check` or selectively adopt standard discovery; deliberate failed assertion breaks normal `check` | [#323](https://github.com/gamesbyian/Expository/issues/323) |
| **A-08 P1 Medium** | `SftpBrowseCard.kt:806–845`: response checks `session === live` but resets shared `busy` regardless; possible stale completion if concurrent tasks overlap | Generate operation identity/token in common reducer; test deliberately reordered same-session browse, disconnect/reconnect, picker returns | [#322](https://github.com/gamesbyian/Expository/issues/322), [#324](https://github.com/gamesbyian/Expository/issues/324) |
| **A-09 P2 High** | No committed Gradle wrapper, lockfiles or artifact verification metadata at baseline; CI supplies Gradle 8.13; transitive graph/provenance not locked | Reviewed wrapper and artifact verification, version locks, advisory audit/SBOM, negative checksum test; avoid gratuitous modules | [#326](https://github.com/gamesbyian/Expository/issues/326) |
| **A-10 P2 required feature High** | `KeystoreProfileVault.kt:17–36,75–85`: connection-only bookmark encrypted, no biometric-bound credential vault or enforced UI session gate | Implement actual optional Local/Remote access gate at dispatcher, distinct from SSH/TLS/SAF, with biometric/device credential and cancel/relock tests | [#25](https://github.com/gamesbyian/Expository/issues/25) |
| **A-11 P2 High** | `TEST-AND-EVIDENCE-MATRIX.md:34–38` previously prescribed endpoint identities inside a minimal journal, contradicting `DurableAttemptJournal.kt:9–24` | Clarify separate neutral attempt journal and protected recovery/manifest index; no credentials in either; formal corruption/retention contract | [#325](https://github.com/gamesbyian/Expository/issues/325) |
| **A-12 P2 release gap High** | `app/build.gradle.kts:18–20` disabled release shrinking; `transfer-pr-apk.yml` produces developer APK, not signed E5 | Release-build reflection/R8 verification, clean-install/upgrade, signing chain and provenance; do not treat artifact checksum as signature | [#327](https://github.com/gamesbyian/Expository/issues/327) |
| **A-13 P2 required coverage High** | `CURRENT-CONTRACT.md` P01–P12 and native queue exceed actually accepted six-direction transfers, FTPS write, Drive, rename Apply and recovery | Fill route matrix via shared command + capability-scoped adapter proofs; no unsupported FTP mutations or claimed cloud Save on handoff | [#28](https://github.com/gamesbyian/Expository/issues/28), [#27](https://github.com/gamesbyian/Expository/issues/27), [#26](https://github.com/gamesbyian/Expository/issues/26), [#54](https://github.com/gamesbyian/Expository/issues/54) |
| **A-14 P2 platform risk High / likelihood unverified** | `SafRecursiveTreeCopy.kt:18–36`: generic `DocumentsProvider.createDocument` has no universal atomic conditional-create; current adapter already blocks/marks ambiguous identity UNCERTAIN | Preserve conservative fresh-parent checks; adversarial concurrent-create/rename/membership tests for every new provider; no speculative rollback | [#327](https://github.com/gamesbyian/Expository/issues/327), [#28](https://github.com/gamesbyian/Expository/issues/28) |
| **A-15 P2 evidence gap High** | 2k / 2048 bounded listings and 10k preview requirement exist but profiling was not conducted | Synthetic Macrobenchmark or equivalent startup/scroll/stream profiles; 200% font/TalkBack; optimize only reproducibly measured bottlenecks | [#327](https://github.com/gamesbyian/Expository/issues/327) |

No confirmed active P0 exploit, credential leak, insecure SSL fallback or silent overwrite was established. These are not automatically ruled out by static review.

## Dependency-ordered remediation backlog

Approximate **engineer-days, not promised elapsed dates**. Work IDs stay stable and map onto the native W-queue.

| Backlog | Priority / effort | Depends on | Required result / tracking |
|---|---|---|---|
| **B01** genuine real picker E2 | P0 acceptance / 1–3 | None | #292 exact-head two-grant bytes, nested+empty, negatives; #222 |
| **B02** cancellation and async race characterization | P1 / 1–3 | None | Job+permit, prompt stop, journal truth, reorder tests; #322 |
| **B03** default Android compile/lint gate | P1 / 0.5–1.5 | None | Broken Compose PR rejected, no full emulator for unrelated PR; #323 |
| **B04** frozen command + one SAF route | P1 / 3–6 | B02 characterization and E2 baseline | Same digest, stale invalidation, unchanged bytes; #324 |
| **B05** protected recovery index | P1 / 4–8 | Journal contract; B04 for new UI routes | Operator can safely find and re-observe uncertain target, no replay; #325 |
| **B06** Single/Dual unified UI migration | P1 / 5–10 incremental | B04 | Shared operation/approval/receipt across layout and action triggers; #324 |
| **B07** durable per-item batch integration | P1 / 3–7 | B04+B05 | Fresh preflight per item, stop on uncertainty, restart evidence; #325 |
| **B08** adapter/FTPS write proof | P1 / 3–7 | Existing trust; B04 for UI | No-replace/ACK/readback controlled E3, Android E2, unsupported otherwise; #54/#28 |
| **B09** wrapper/locks/dependency verification | P2 / 1–3 | None | Reproducible resolution, checksum mismatch fails; #326 |
| **B10** JVM `check` and test discovery | P2 / 1–3 | None | Standard `check` runs real suites, not green empty checks; #323 |
| **B11** optional authentication UI enforcement | P2 before beta / 3–6 | B04 command gate | Biometric/credential and per-scope cancel/expiry tests; #25 |
| **B12** real Drive originals and distinct Docs | P0 feature gate / 5–12 + E4 | B04+B05+B07, real account authority | Verified parent/content/file ID, lost create ACK dedup, no Sharesheet success; #27 |
| **B13** guarded Rename Apply/undo | P1 and feature gate / 5–10 | B04+B05+B08 capability | Real mutation E2/E3, cycles, fault and no-overwrite; #26 |
| **B14** physical/performance/accessibility/signed E5 | P1 release, scoped | Route+auth+recovery readiness | Measured device/size evidence, OWASP checklist, release signing; #327 |

**Partial order:** B01, B02, B03, B09 and B10 can run independently. B02→B04→B06; B04+B05→B07; B04+verified adapters→route matrix; B04+B05+verified adapter capabilities→B13; B04+B05+B07+real account→B12; all actual beta requirements plus E4→E5. Do not hold independent security/protocol/CI work behind a picker test. Do not let two agents concurrently own `TransferActivity.kt` or `SftpBrowseCard.kt`.

## Target architecture and deliberate simplification

```text
Single / Dual / Share / button / optional drag
              |
 frozen SelectedSource + DestinationChoice + ActionIntent
              |
     ApprovalSnapshot and operation token
              |
      existing pure OperationPlanner
              |
  shared state/dispatch/receipt coordinator
              |
 durable minimal attempt + protected recovery index
              |
 existing per-authority SAF / SFTP / FTPS adapters
```

Retain pure planner, capability proof, exclusive SFTP creation, strict TLS/SSH, byte readback, `AtomicFile`, no-replay uncertainty and platform-granted SAF authority. Add *one* coordinator in the existing `:app` module. Avoid a generic filesystem facade, Hilt deployment or module explosion unless a new actual requirement justifies it. Replace duplicate per-card status/permit logic only **after** parity tests show behavior preservation. See [architecture ADR](ARCHITECTURE-DECISIONS.md).

## Agent-ready packages / no-merge contracts

| Package | Exclusive edit surface | First verification / non-negotiable completion |
|---|---|---|
| **WP-01** #292 fixture + `app/src/androidTest` + scoped E2 workflow | Preserve two genuine system-picked grants and independent readback; document exact-head failure/success |
| **WP-02** #322 engine + one SAF adapter/Activity call site | Characterize slow cancellation first, then ensure job+permit and honest UNCERTAIN state; no source delete |
| **WP-03** #324 small app coordinator + one SAF Copy card | Migrate just one proven route; same digests, UI/authority freshness and E2 bytes before expanding |
| **WP-04** #325 `journal/*` + recovery UI | Separate private recovery metadata from neutral journal, account for corruption and no replay |
| **WP-05** #323/#326 Gradle/workflows | Path-scoped compile/lint and `check`; reviewed dependency verification; no production creds |
| **WP-06** #54/#28 one SFTP, FTPS or SAF adapter per PR | Full E3 no-replace/readback and Android E2; unsupported if server capability absent |

Every PR: list SHA, source scope, behavior preserved, exact verification commands and counts, negative tests and **unverified** cases. A passing test invocation is not a verified Copy; a debug APK is not signed E5. Recheck current issue and PR ownership before editing.

## Verification commands and limitations

Audit-side only: read GitHub tree/files and CI runs; **none of these commands was executed** because the audit container had no checkout/Gradle and could not resolve github.com via git. Commands for subsequent isolated checkout (no production endpoints):

```bash
cd android-transfer
gradle :core:planner:scenarios :core:planner:workspaceScenarios \
  :core:engine:scenarios :core:engine:journalScenarios \
  :core:engine:journaledCopyScenarios :core:engine:recursiveCopyScenarios \
  :adapter:sftp:scenarios :adapter:ftps:scenarios --no-daemon
gradle -PwithAndroid=true :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
gradle -PwithAndroid=true :app:lintDebug --no-daemon
# Only on trusted synthetic API35 emulator with reviewed, allowed tests:
gradle -PwithAndroid=true :app:connectedDebugAndroidTest --no-daemon
```

Full future review still needs every production file, manifest/intent negative-case coverage, complete transitive dependency graph/security scan, process-death/Doze execution, all Android API/device variants, actual third-party DocumentsProviders/Drive, actual protocol E3 variants, profiler traces, signing provenance and OWASP MASVS/MASTG mapping. Mark NOT RUN / UNVERIFIED, never PASS, until each is independently observed.

## Five improvements / three risks / three simplifications / first task

**Highest-value:** E2 picker proof, lifecycle cancellation, common command/approval coordinator, actionable recovery and automatic Android build gates. **Greatest risks:** divergent UI-originated mutation paths; non-actionable digest-only recovery after death; blocking IO lifetime inadequately tied to operation authority. **Simplifications:** one operation model, reuse pure planner/engine, consolidate receipts/status and reduce duplicate per-card mutation coordination. **First independent engineering task:** B02/#322 cancellation characterization while #292 independently investigates real picker E2.

References: Android recommended architecture, coroutines lifecycle and cancellation, WorkManager foreground constraints, Gradle dependency locking/verification, OWASP MASVS/MASTG and Google's Now in Android for comparison. Use source-specific evidence and actual dependency/SDK compatibility before changing implementation.
