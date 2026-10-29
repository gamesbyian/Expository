# Expository Transfer: open-source reuse and implementation research

**Research date:** 2026-10-29. **Scope:** native Android app under `android-transfer/` only. **Status:** engineering research and proposals, not accepted implementation or release evidence. Recheck licenses, upstream versions, source and tests before integrating. This note does not supersede [CURRENT-CONTRACT.md](CURRENT-CONTRACT.md), [ranked native queue](../../android-transfer/WORK-QUEUE.md), or [release ledger](RELEASE-READINESS.md). The separate Pages explorer remains read-only.

## Executive decision

**Build around the current Kotlin planner, journaled engines, SAF components, Apache MINA SFTP, and Apache Commons Net FTPS.** Do not restart from another file manager. Expository's distinguishing requirement is an immutable reviewed source/destination plan, independent byte/identity verification, and fail-closed uncertainty after an ambiguous operation. Most general-purpose managers do not document equally strict guarantees; transplanting their copy engines would risk undoing implemented safety work.

Instead, reuse **official Android/Google APIs and permissively licensed libraries** for integration and lifecycle, and mine mature GPL file managers for **architecture, UI research, interoperability failures and test scenarios**. Expository currently has no root LICENSE file; the absence of a license is **not** permission to incorporate GPL code. Before literal code reuse, establish the app's planned redistribution license and independently check the licensing of individual source files and dependencies. Architecture ideas, workflow observations and independently written implementations are the default.

**Current implementation checked on `main`:** `core/planner`, `core/engine`, `adapter/sftp`, `adapter/ftps`, `app`, `testing/fake-endpoints` exist. Gradle pins `org.apache.sshd:sshd-sftp:2.20.0`, `commons-net:commons-net:3.13.0`, Kotlin 2.2.20, Compose and target SDK 36. Single and Dual workflows, scoped SAF bytes, synthetic SFTP relay, recursive SAF copy and partial journaling already have tests. **They are not a complete six-way/folder/Drive/beta implementation.** Consult queue and ledger for exact proof.

## A. Whole-app references: study and compare, do not directly ingest GPL code

| Project / source | License | Highest-value reference for Expository | Practical recommendation |
|---|---|---|---|
| [Twig](https://github.com/dev2ex/twig), [core-fs](https://github.com/dev2ex/twig/tree/main/core-fs), [lessons](https://github.com/dev2ex/twig/tree/main/docs/lessons) | GPL-3.0-only | Pure-JVM `FileSystem`/`XFile`/`FsRegistry`/`CopyEngine`, endpoint-neutral destinations, dual panes, SAF, bookmarks, background operation, rename preview, explicit negative experiences | **Top architecture/defect reference**; compare with Expository `LocationAdapter` and journal. Do not port its full engine or security implementation. |
| [Voyager](https://github.com/AlanHuang99/Voyager), [F-Droid capabilities](https://f-droid.org/packages/com.voyagerfiles/) | GPL-3.0-only | Jetpack Compose Material 3, one-location browsing, sessions, picker uploads, FTP/SFTP, transfer notifications/progress, compact/list/grid | **Top mobile UX reference**; compare screen flows and 200% font behavior, not copy UI code. |
| [Material Files](https://github.com/zhanghai/MaterialFiles) | GPL-3.0-or-later | NIO-style backend abstraction, breadcrumbs, remote paths and Linux metadata/symlinks | Inspect backend capability separation and navigation affordances. |
| [Amaze File Manager](https://github.com/TeamAmaze/AmazeFileManager) | GPL-3.0-or-later | Multi-tab browsing, selections, archive and network workflows; Android regression/security issues | Secondary test/UX reference, not architectural wholesale replacement. |
| [rclone](https://github.com/rclone/rclone) and [backend overview](https://rclone.org/overview/) | MIT | Backend capability negotiation, cloud naming, resumability, per-endpoint hashing, retries, reconciliation, giant provider incompatibility matrix | **Strong concepts/reference implementation**, but embedding a Go runtime/native bridge into a small Kotlin Android app for Drive is high integration/size cost. Do not make it a P0 dependency without a benchmark. |
| [RSAF](https://github.com/chenxiaolong/RSAF), [F-Droid](https://f-droid.org/packages/com.chiller3.rsaf/) | GPL-3.0-only | Exposes rclone-backed cloud endpoints to other Android apps as SAF DocumentsProviders | Test as an **external installed third-party SAF provider**. Does not require copying GPL source into Expository or bundling rclone. Do not assume every provider offers writable trees or persistent grants. |
| [SFTP-SAF](https://github.com/AlainKnaff/Android-SFTP-Documents-Provider), [F-Droid](https://f-droid.org/packages/lu.knaff.alain.saf_sftp/) | GPL-3.0-or-later | SFTP as an Android DocumentsProvider, authority/grant behavior, remote network errors surfaced via SAF | Another excellent **external interoperability target**; not a substitute for the app's pinned native SFTP session or proof engine. |

**Special find: Twig's practical defect corpus.** Before W02/W03/W06, read:
- [tree-and-adapter.md](https://github.com/dev2ex/twig/blob/main/docs/lessons/tree-and-adapter.md): deleting/moving selected directory can leave an invisible stale destination; rename previews misalign under large text; swipes can accidentally fire underlying taps; refreshes cannot assume a cached remote directory is current.
- [saf.md](https://github.com/dev2ex/twig/blob/main/docs/lessons/saf.md): a SAF document URI is an opaque identity, not a slash-delimited path; display names and parent membership must be explicit. Synthetic providers must use realistic escaped IDs (`%2F` etc.). Actual `openFileDescriptor` access may differ by backend.
- [ssh-and-terminal.md](https://github.com/dev2ex/twig/blob/main/docs/lessons/ssh-and-terminal.md): lost SFTP mutation replies, network-on-main-thread protocol damage and unsafe retry behavior; failure may have landed on server.
- [security.md](https://github.com/dev2ex/twig/blob/main/docs/lessons/security.md): useful Keystore/biometric and backup threat-model examples **but some project-specific fallback choices are NOT acceptable for Expository**. In particular, never fall back to plaintext secret storage on encryption failure; fail closed and request reauthentication.

## B. Direct technical reuse by ranked Expository work item

### W02 / G2/G6: single-location parity and optional dual view

**Use:** official [Compose Lazy lists/grids](https://developer.android.com/develop/ui/compose/lists), [Compose drag and drop](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/drag-and-drop), and the existing Expository `WorkspaceState` / immutable plan. Stable keys and content types avoid selection, reorder and scroll corruption. A drag payload should carry an opaque in-app selection reference, not bearer credentials, private paths or file bytes. Dropping onto a pane/folder must invoke the exact same review and digest as the explicit Copy button.

**Borrow UX patterns:** Voyager's single browser and session switching; Twig's source-neutral destinations and folder pickers. Preserve Expository's distinct permanent default Single mode (do not collapse Single conceptually into Dual A/B tabs).

**Acceptance to add:** removed current directory while transfer sheet is open, pane swap during picker callback, drag to nested folder, orientation/font-scale switching, TalkBack non-drag route, canceled gesture not clicking a row, 10k-keyed virtual preview. Existing staged limits (2,000 visible children, 12 selections) are not full P0 acceptance.

### W03 / G2/G4/G6: recursive cross-provider copy

**Use:** platform [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract) and [Document capability flags](https://developer.android.com/reference/android/provider/DocumentsContract.Document), plus [Android StorageProvider sample](https://github.com/android/storage-samples/tree/main/StorageProvider) (Apache-2.0, *deprecated/unmaintained sample*, adapt tests, not production code). Explore `DocumentsContract.copyDocument` and `moveDocument` only if same-provider flags, exact scope and independent postconditions authorize them; never assume atomicity or complete listing.

**Borrow failure cases:** Twig's `saf.md` parent-URI bugs, provider-grant attrition, opaque IDs, nonseekable or cloud-delayed streams. Do not derive parent from path string or assume a provider's listing failure implies empty folder.

**Acceptance:** two truly distinct DocumentsProvider authorities selected through OS picker, nested+empty folder byte proofs, duplicate names, partial/incomplete list, >2k entries, loss of reply after create, cancellation between each child, absent/moved child after readback, process kill after mutation. Until proven, retain current same-authority overlap refusal.

### W04 / G0/G3/G4: protocol parity and safe upload

**Reuse current** [Apache MINA SSHD](https://github.com/apache/mina-sshd) and [Apache Commons Net FTPSClient](https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPSClient.html). **Important footgun:** Commons Net's FTPS client does not enable hostname verification by default; Expository's existing checks must remain independently tested. TLS trust, endpoint identification and protected data channel are separate properties. A valid TLS session alone does not authorize a `STOR` that might overwrite an existing remote object. Do not infer no-replace from FTP/SFTP rename naming or a prior directory listing.

**External controlled fixture options:** [pyftpdlib](https://github.com/giampaolo/pyftpdlib) (MIT; Python FTP/FTPS disposable servers), [Apache FtpServer](https://github.com/apache/ftpserver) (Apache-2.0, Java fixture), and [SFTPGo](https://github.com/drakkan/sftpgo) (AGPL-3.0 community server, useful *out-of-process fixture* with SFTP/FTPS endpoints). Containerized servers' license does not automatically license their code for embedding in the app. Keep server versions pinned to reproducible fixtures. Seed explicit hostile/odd `MLSD`, `LIST`, TLS1.2, symlink, Unicode and lost-ack scenarios.

**Acceptance:** disposable Android↔SFTP and Android↔FTPS payload proof, hostname mismatch and data-channel downgrade refusal, denied writes, nonexistent/mutating destination, source version drift, no blind retry of ambiguous create/rename, absent capability refusing unsafe copy.

### W05 / G1/G5: durable background queue

**High-value platform discovery:** [Android user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt) (Android 14/API 34+) are intended for visibly user-started long file transfers. [WorkManager long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) warn that on Android 16 such workers can exhaust JobScheduler quotas. Implement an **execution-host interface** over the existing immutable planner/journal:
1. Foreground manually approved short job.
2. User-initiated transfer JobScheduler host for eligible Android 14+ long jobs.
3. Carefully bounded foreground-service or WorkManager fallback for earlier devices/other work, respecting launch restrictions and the stated G5 recovery contract.

Neither the Android scheduler nor WorkManager may own mutation idempotency. Persist the exact operation identity, grant/session generation, verified items, unknown items and original approval before dispatch; OS retry is **reconcile only** after uncertain writes. Don't persist credentials or raw private filenames in public logs, backups or notification extras.

**Acceptance:** stop before/after write, process kill, job rescheduled without `onStopJob`, permission revocation, offline, device reboot, body fully sent but ack lost, duplicate OS dispatch, operation requires fresh human permission. A progress notification is not a proof receipt.

### W06 / G1/G7: guarded batch rename

**Reference:** Twig [tree-and-adapter lessons](https://github.com/dev2ex/twig/blob/main/docs/lessons/tree-and-adapter.md) and the project's GPL rename UX; **retain Expository's current rename DAG and journal**. Do not import another file manager's rename implementation. Borrow separately tested UI details: fixed controls with independently scrolling virtual preview, explicit Unicode code-point semantics, deterministic fixed order, `Locale.ROOT` case transforms, no silent extension removal and fresh membership on Apply.

**Acceptance:** 10k-row virtual preview under large text, 500 executable synthetic cases/50 fault transitions, name collision/cycle/case/Unicode/symlink/ambiguous remote status, verify non-overwrite before exposing Apply. A filesystem command called `rename` is not evidence of atomic no-replace.

### W07 / G6: all endpoint pairings

**Reference:** Twig `FileSystem` registry for architecture, rclone capability-aware copy and hash behavior. Keep a single provider-neutral `LocationRef / ItemRef / Capabilities / Adapter` plan and specific adapters for SAF, pinned SFTP, FTPS, Drive and read-only GitHub; never make Android URI, FTP path or Git SHA interchangeable. Prefer bounded handset relay across authorities and independently verify destination. Distinguish native same-authority Move from cross-authority verified Copy + separately reviewed delete. No FXP by default. The Pages browser trust boundary is unchanged.

### W08 / G8: Google Drive/Docs and GitHub

**Official sources:** [Drive `drive.file` authorization](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Android AuthorizationClient](https://developer.android.com/identity/authorization), [Drive resumable uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [create in parent](https://developers.google.com/workspace/drive/api/guides/folder), and [conversion/import formats](https://developers.google.com/workspace/drive/api/guides/manage-uploads). The Google APIs Java client is [Apache-2.0](https://github.com/googleapis/google-api-java-client); compare its size/integration with a small Kotlin HTTP client using well-tested existing APIs, rather than assuming a new SDK is needed.

**Specific reuse opportunity:** Drive resumable upload sessions provide queryable committed ranges after an interruption (`308` with `Range`) and documented 256-KiB chunk granularity. **For supported original binary uploads**, Drive also supports **pre-generated IDs** that can prevent duplicate objects on retry after certain ambiguous failures; verify the exact allowed create/upload variants before implementing. These IDs are **not supported for most Google Workspace conversions**. Journal session URI and intended file ID securely, reconcile parent/file identity and only then retry the confirmed missing bytes. Never treat a local `200` or provider write acknowledgement as proof of a synced cloud object without the stated independent checks.

**OAuth scope limitation:** `drive.file` gives per-file/app-created access, not arbitrary account-wide browsing. A Drive Picker folder-selection interface is not automatically permission to write to every parent the user can see. Prove authorization, selected parent visibility, `files.create(parents)`, account selection and post-create lookup on real account E4. Avoid silent escalation to broader restricted scopes. Product must still offer explicit Save Original, Create Google Doc (convert, inspect `about.importFormats`), Open in Docs and Share handoff as distinct outcomes. No Expository-hosted relay service or common user account is required for this Android-local Google authorization flow; an OAuth client registration/consent configuration may nevertheless be required.

**GitHub:** [REST Git Trees](https://docs.github.com/en/rest/git/trees) offers explicit nonrecursive traversal and `truncated` indicators for large trees; keep read-only native GitHub mode separate from explicitly authorized future writes. Avoid adding JGit to Android unless a proven requirement needs full Git transport; the GitHub HTTP APIs cover the narrow current browse/save case.

### W09 / G9: controls and evidence

**Use:** [AndroidX Biometric](https://developer.android.com/jetpack/androidx/releases/biometric) for OS authentication UX, retaining the native Keystore and app-level `SessionAccessGate` checks. AndroidX `EncryptedSharedPreferences` is [deprecated](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences); do not add it as a new dependency or assume an app unlock authenticates remote servers. Do not store tokens or credentials in work queues, app backups or test artifacts.

**UI evidence:** device/SDK coverage, TalkBack, 360/393/412dp, landscape and 200% font. Build a bounded golden fixture suite around genuine OS pickers and separate test providers, not only synthetic pure-Kotlin stubs.

## C. Test-fixture portfolio: reuse instead of writing every fake from scratch

| Fixture | Where it fits | Requirement to assert |
|---|---|---|
| [Android StorageProvider sample](https://github.com/android/storage-samples/tree/main/StorageProvider) (Apache-2.0, deprecated) | Instrumentation seed for two independent scoped provider authorities | Parent ID opacity, flags, revocation, returned URIs, nonseekable streams, delayed/ambiguous writes |
| [RSAF](https://github.com/chenxiaolong/RSAF) (separate GPL app) | Real third-party SAF-to-cloud interoperability on opted-in device | Provider coverage/capability variance, not a substitute for specifically verified Google Drive |
| [SFTP-SAF](https://github.com/AlainKnaff/Android-SFTP-Documents-Provider) (separate GPL app) | SAF documents whose bytes come from a remote SSH server | Double transport through provider, permission lifetime, opaque paths, interruptions |
| [pyftpdlib](https://github.com/giampaolo/pyftpdlib) (MIT) | Python FTP/FTPS localhost or CI service | MLSD/LIST, TLS, missing parent, duplicate names, silent close, upload ambiguity |
| [Apache FtpServer](https://github.com/apache/ftpserver) (Apache-2.0) | JVM FTPS compatibility fixture | Alternative server behavior for client interop |
| [Apache MINA SSHD](https://github.com/apache/mina-sshd) (already a dependency) | Existing in-process disposable pinned SFTP lab | Host key rotation, two sessions, no-overwrite, lost reply, symlinks |
| [SFTPGo](https://github.com/drakkan/sftpgo) (AGPL-3.0, **external service only**) | Cross-protocol multi-server integration | Different server semantics, SFTP/FTPS credentials and listings |
| [OkHttp MockWebServer](https://square.github.io/okhttp/#mockwebserver) (Apache-2.0) | Future Drive/GitHub HTTP tests | Forced 308/401/403/409/429/5xx, disconnect during request/response, stale pagination tokens |

Avoid routing user credentials through public CI. Disposable mutation fixtures only. Pin fixture versions and preserve deterministic fault scripts. Distinguish external-server E3 from physical-provider E4 and signed-release E5.

## D. Concrete next engineering tasks, preserving queue rank

1. **W02 first: unified destination navigation.** Compare current `TransferActivity.kt`, `DualWorkspaceCard.kt`, `WorkspaceState.kt`, SAF cards with Twig/Voyager's navigation flows. Introduce/reuse one `DestinationSelection` and pane identity model behind Single and Dual; button and drag issue identical immutable plan. Add stale-destination/gesture/font-scaled E1/E2 tests.
2. **W03 next: real two-authority recursive provider E2.** Base test providers on Android StorageProvider contract and realistic URI identities; complete current open PR #292 after exact-head validation if independently safe. Forbid unsafe same-authority ancestor guesses.
3. **W04 parallel isolated protocol lane.** Extend Apache Commons Net guarded FTPS write from the existing adapter and add pyftpdlib/Apache FtpServer negative lab. Build Android↔FTPS byte proof, not a listing-only acceptance.
4. **W05 before more transfer breadth.** Add durable execution-host design review (UIDT vs foreground service / WorkManager by API and operation), journaling recovery semantics, then fault-inject before Android runtime integration.
5. **W06 rename Apply only after a qualified adapter proves no-overwrite / stable identity**. Use existing plan/engine, not GPL copy implementation. Add virtual 10k preview and 500/50 fault gate.
6. **W07 complete the six routes using a provider-neutral executor**; ensure folder streams, Move consent and post-write verification. Keep FTP read-only unless explicitly expanded and separately accepted.
7. **W08 Drive API research spike now, implementation in parallel**: test real Drive SAF writable tree; if inadequate, make narrow `drive.file` AuthorizationClient + parent-write + resumable binary upload probe, including pre-generated ID behavior and accurate original/Docs conversion receipts. This needs authorized live E4 to close.
8. **W09 last:** standalone security and device acceptance including biometric gate and physical Pixel validation. No feature's synthetic tests may silently promote E4/E5.

## E. Rejected or deferred shortcuts

- **No GPL copy-paste into this repo without a deliberate license decision.** Cross-language reimplementation is also not automatically a license escape if it copies protectable expression; independently implement from behavior/specification.
- **No replacement transfer core.** Another app's general copy method cannot be presumed to provide Expository's durable uncertainty/consent guarantees.
- **No default FTP/FTPS STOR overwrite or implicit downgrade.** Preserve the security contract and test server-specific guarantees.
- **No automatic OS-job retry for ambiguous writes.** Restart means consult journal and reconcile.
- **No giant rclone-Go/JNI APK dependency as a shortcut to P0 Drive** until size, auth, platform lifecycle and operation semantics are proven.
- **No broad Google OAuth just to simplify browsing.** Treat `drive.file`, Picker and SAF as independently scoped grant models.
- **No direct import of obsolete Android storage samples as production code.** Use as fixture design references.
- **No carrying over permissive/unsafe failure policy from another project**, including storing plaintext when Keystore encryption fails.
- **No redundant web-app work:** ZIP viewer/publisher belongs to the separate Expository Pages surface.

## Validation of this research

Checked current Expository Gradle dependencies, ranked queue and product contract on 2026-10-29. Primary links above are official project repositories, official Android/Google API guidance, and upstream implementation notes. **No dependency was added, code imported, third-party APK installed, or live OAuth/endpoint transfer executed as part of this research.** Version, API and license checks must be repeated at time of integration. This research introduces no change to P01–P12, gates, evidence ledger or queue statuses.
