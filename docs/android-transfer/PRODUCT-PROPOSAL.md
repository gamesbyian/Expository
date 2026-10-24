# Expository Transfer · Android-native product proposal

**Status: exploratory proposal, not approved implementation**  
**Date: 23 October 2026**  
**Companion evidence:** [competitive and engineering research](EXPLORATORY-REPORT.md)

## 1. Product definition

**Working name:** **Expository Transfer** (final app label, icon and package name subject to design review).  
**One-line promise:** **Your files, wherever they are. Save and send without the ceremony.**

A distinct, native Android file-transfer companion within the Expository brand. Unlike the existing Expository GitHub browser, which is deliberately read-only and served as a static web app, **Expository Transfer is explicitly a user-authorized writer** to chosen remote FTP/FTPS/SFTP servers and chosen Android document destinations. It must have a separate application, credential store, permissions boundary, user experience and QA contract. Shared brand and visual language do **not** imply shared authentication or data access. No Expository cloud is needed.

**P0 product rule:** Uploading or saving one item is trivially discoverable. Uploading or saving a folder is just as clear. The optional **Workspace must pair any two locations: Local/Local, Local/Remote or Remote/Remote**, including two independent folders on the same server. Drag-and-drop has an explicit action alternative. **An excellent, compact, preview-first batch renamer is launch scope**, not a future “expert tool”; regex/chained recipes can arrive later. Full execution and safety specification: [workspace and renamer deep dive](WORKSPACE-AND-BULK-RENAME.md).

### Product positioning

This is *not* a replacement for Solid Explorer's system-file tools, MiXplorer's deep automation, or FolderSync's mature scheduled backup. The target is a precise, delightful task: *pick a remote server; grab or send the files you want; know exactly where they went and whether it worked.*

**Four design values:** native, reassuring, fast, and powerful-on-demand. No ads, mandatory account, telemetry, speculative cloud storage or permanent broad filesystem permission.

## 2. Primary user journeys and contract

### J1: Save one remote file to the device (the hero flow)

1. Open a saved server or paste server details and connect securely.
2. Browse remote folder. Tap a file, then **Save to device**. Long press/select can also show the action.
3. Android's **Create document** system sheet opens with original name and MIME guess; user chooses a destination, optionally renames.
4. User confirms the save in Android's picker. App streams the remote bytes to that returned document URI, shows a clear progress task and notification when appropriate.
5. A completion receipt says **Saved** + file name + destination provider where available + Open / Share / View transfers. Never claim the path is a traditional filesystem path if the provider returned only a URI.

**Acceptance:** 1 remote file can be saved without granting Expository access to the rest of the device or learning app-specific storage concepts. If the picker is canceled, there is no remote change or misleading success. No incomplete destination is reported as complete.

### J2: Save a remote folder or multi-selection

1. Select folder(s)/file(s) in Remote; tap **Save folder** or **Save selected**.
2. Native **Choose folder** via `ACTION_OPEN_DOCUMENT_TREE` selects an authorized destination parent, not a newly created file.
3. Preflight shows source folders, destination provider, number/size if known, collision policy and explicit folder nesting. The queue creates directories beneath the chosen root via `DocumentsContract`.
4. Transfer with per-item state, resumability only where truthful, and a final count of complete / skipped / failed.
5. Android policy limitations are explained inline: a user may not be able to grant the **Downloads root** as a tree on Android 11+, even though individual files can be saved there through the normal picker.

**Acceptance:** Nested paths and empty subfolders preserved if destination provider permits; bounded scan; 100+ folder test; one bad file does not silently discard others. Show **partial completion** where appropriate. Optional future **Save as ZIP** is a separate user action.

### J3: Upload selected Android files

1. Browse a remote destination folder and tap bottom-positioned **Upload**.
2. Native system **Choose files** opens using `ACTION_OPEN_DOCUMENT` (multi-select enabled); accepts local content and user-authorized document providers. Also support Android **Share → Expository Transfer** for inbound files.
3. Preflight: **Upload N files to /remote/folder**. Display duplicates/overwrites and network policy, then start.
4. Per-file and whole-job progress; completion with server destination link/path and any conflicts.

**Acceptance:** no internal local-file manager knowledge required; can upload two files from different Android provider locations when chosen via supported picker interactions; no `file://` assumption.

### J4: Upload an entire Android folder

1. In Remote, tap **Upload ▾ → Folder**.
2. Android `ACTION_OPEN_DOCUMENT_TREE` grants a selected source directory and its children.
3. Show source/destination mapping and whether a new enclosing folder is created. Preserve hierarchy. Start a bounded recursive queue.
4. Revoke/cancel mid-transfer handled as an explicit permission-blocked state, with completed files preserved and resumable reauthorization where possible.

**Acceptance:** handles permitted SAF document trees, including nonlocal providers where supported; correctly explains Android's restricted folder roots. Never secretly swap in `MANAGE_EXTERNAL_STORAGE` to make a failed picker appear successful.

### J5: Optional any-two-location transfer workspace (Local/Local, Local/Remote, Remote/Remote)

- Workspace opens with **two independently navigable locations**, initially Remote + a selected user-authorized Local place if relevant. Top-level presets **Device ↔ Server**, **Device ↔ Device**, **Server ↔ Server** establish the desired pair; each pane can change location independently. Both panes may show different directories on **the same remote server** or **two distinct servers**, or two SAF document providers. The server browser remains single-pane by default.
- **Portrait phone:** stacked **Local** above **Remote** with 45–55% adjustable pane division, collapsible pane header, independent breadcrumbs and list scroll. When it becomes too cramped due to IME/text scale, automatically switch to one active pane with Local/Remote tabs; user can always make this choice manually.
- **Landscape/foldable/tablet:** panels side by side, resizable and independently navigable, with active-pane outline and a visible Copy direction.
- Select items by tap/long-press and drag onto other panel. During drag, highlight **Copy N to Remote** / **Copy N to Device** target. Drop launches **preflight**, never destructive unconfirmed move.
- **Non-drag alternative:** prominent **Copy to → other pane** / **Move to → other pane** and **Save / Upload** actions. Hardware keyboard: Tab focus, Shift-select/range, copy via toolbar, Escape cancels drag. TalkBack can announce origin, target and count.
- A separate **Move** option must explicitly describe deletion from source **after** confirmed destination completion, and require confirmation for cross-location moves.

- **Remote organization must feel instantaneous when genuinely supported:** Select items → **Move to…** → browse destination on the same server → **New folder** (inline) → confirm. Attempt FTP RNFR/RNTO or SFTP rename on-server when available; do not download/re-upload just to reorganize one server. Refusals, cross-filesystem paths and conflicts get an explicit safe fallback, never hidden byte-relay.
- **Cross-server remote/remote** uses two authenticated sessions and a bounded phone-mediated stream with transparent bandwidth/battery implications; no automatic FTP FXP. **Local/local** uses SAF provider-supported move only when allowed; otherwise copy/verify/delete with separate confirmation for Move.

**Acceptance:** All six directional pairing cases across Local/Local, Local/Remote and Remote/Remote; 393px portrait and 360px compact layout without horizontal scroll; landscape/large screens show both panes; no hidden-only gesture; data loss never caused by an ambiguous drag. Verify same-server rename avoids transferring file payloads.

### J5b: Compact bulk rename (available in both panes)

Select multiple files/folders → **Rename (N)** → a one-screen sheet with operation chips **Replace**, **Number**, **Prefix/Suffix**, **Case** and **Clean up**, a clear sorting/counter control, extension-preserving default and **live Current → Proposed** row-by-row preview. Apply is blocked for duplicate proposals, conflicts with unselected siblings, invalid/reserved names and unsafe case/Unicode collisions. The same previewed plan drives execution; each rename has an inspectable receipt. The app must stage/sequence cycles such as A↔B safely or refuse and explain. **Undo is best-effort**, never advertised as a global transaction. Regex, saved recipes and multi-step chains are an advanced second increment, not prerequisites to the compact first-run experience. See [the detailed rename contract](WORKSPACE-AND-BULK-RENAME.md#4-compact-bulk-renamer-specification-worthy-of-the-brand).

**Acceptance:** A user can rename 40 episode files with fixed-width numbers without entering a symbolic mask, preview changes before committing, and recover/inspect failures without losing originals. Both local SAF and remote providers are tested for capability-denied rename and changing document identity.

### J6: Receive via Android share sheet

An external app shares image/video/document(s); Expository presents saved server profiles and last explicit remote destination, previews transfer count, then uploads. It accepts temporary grants for `content://` URIs. If the grant cannot last until delayed execution, either persist a permitted SAF grant or safely stage the file after explicit approval, subject to storage limits. If neither is possible, declare **needs source permission** before queueing.

### J7: Continue an interrupted transfer

A network disconnect, process kill or Android time-limit does not leave a fake “success.” Reopening Transfer shows the durable queue with exact last-known bytes/state, retry/resume eligibility and a privacy-redacted reason. Unsupported resume restarts only that file, after user-controlled collision policy. A cross-location **Move** never deletes the source unless each destination item is verified.

## 3. Navigation and information architecture

**Start screen:** prominent **Connect**, recently used *device-local* server profiles, **Upload**, **Browse server**, and a compact **Transfers** status. No full phone scan and no remote-content analytics.

**After connecting:** a single Remote browse screen:
- Connection pill: name, **FTPS/SFTP secure** or **FTP unencrypted**, open path.
- Sort/filter/search, refresh, list/grid toggle, multi-select.
- Sticky bottom action bar: **Upload**, **Save to device** (selection required), **Workspace**.
- Overflow for **Bulk rename**, Create folder, Properties, Copy remote path, Refresh, Delete with confirmation and advanced server controls; **Move to…** and **New folder** must also be discoverable without the overflow menu.
- Persistent small transfer progress pill, expandable queue drawer.

**Main destinations:** Servers, Browse, Transfers, Settings (4 max; bottom navigation on compact screens, rail or app bar on larger screens). Workspace is a browse mode, not a fourth protocol-specific tab. Avoid tabs within tabs.

**Connection wizard:** Server address/host, protocol (SFTP/FTPS explicit/FTPS implicit/Plain FTP), username, credential method, port auto-filled by protocol, optional starting directory. **Test connection** produces readable outcome: Connected securely / Certificate needs review / Host key changed / Permission denied / Server unavailable / Wrong port or protocol / Passive data channel blocked. Advanced options folded away.

**Transfer detail:** item list with clear source → destination, overall percentage *only if byte totals known*, speed (rolling estimate), elapsed, pause/retry/cancel and an expandable safe diagnostic. Unknown-size files show indeterminate progress, not a fabricated ETA.

## 4. Feature priorities

| Priority | Feature | Why it belongs there |
|---|---|---|
| P0 | Saved server profiles, Test connection, directory browsing, accurate protocol identity | Every user journey depends on a usable connection |
| P0 | FTP, FTPS explicit/implicit, SFTP with trusted certs/SSH host keys | Real protocol diversity, no unsafe default |
| P0 | Native single/multi-file **Save** using Create Document / chosen folder | First half of core promise |
| P0 | Native single/multi-file **Upload** using Open Document | Second half of core promise |
| P0 | Whole folder upload and download via separate tree pickers and recursive queue | User explicitly demands folders |
| P0 | Optional local/remote split workspace; drag/drop + accessible action alternative | User explicitly demands it |
| P0 | Durable queue, conflict policies, cancel/retry, verified completion receipts | User trust, integrity |
| P0 | Secure saved credentials with explicit opt-in, LAN permission, Android lifecycle compliance | Correctness/security |
| P0 | Share-to-upload via Android share sheet | Delivers natural Android integration |
| **P0** | All three two-pane pairings: Local/Local, Local/Remote, Remote/Remote, including same-server fast moves and cross-server relay | Explicit first-request requirement; no fake remote-to-remote copy |
| **P0** | Remote New folder + Move to… inside destination picker, same-server FTP/SFTP rename when feasible | Fast remote organization without phone-mediated retransfer |
| **P0** | Compact bulk rename with literal replace, numbering, prefix/suffix, case, locked extensions, live preview, duplicate/conflict blocking, per-item journal | Key product differentiator and initial beta requirement |
| P1 | Advanced regex/chained rename, recipes, undo where safe; path bookmarking, favorite destinations, multiple server profiles, quick-connect, richer search | Advanced efficiency after safe core renamer |
| P1 | SFTP key auth/key import, granular TLS trust review, advanced FTP passive/network troubleshooting | More demanding remote servers |
| P1 | Read-only system `DocumentsProvider`: remote files accessible in Android Files and other apps | Highest-value post-launch integration if secure |
| P1 | Preview images/text/video via scoped streams, share selected remote file | Distinct Expository brand feature without wholesale file-manager bloat |
| P2 | Simple one-way backup/sync with dry run + conflict preview, scheduled jobs | Requires mature transfer engine |
| P2 | WebDAV, SMB/S3 as additional `RemoteStorage` adapters | Expand beyond FTP once interfaces proven |
| P2 | Optional gallery/contact-sheet and visual compare for image asset folders | Brand continuity with Expository web, not FTP v1 dependency |
| Not now | Root filesystem browsing, shell, torrent tools, unrestricted script execution, live telemetry, Expository cloud account, bidirectional unattended sync | Security/complexity cost disproportionate to initial promise |

**Explicit exclusions for v1:** remote server hosting on phone, public links that embed credentials, FTP bounce features, automatic recursive remote deletes, clipboard with stored passwords, forced all-files access.

## 5. Technical architecture

Recommended native foundation: **Kotlin + Jetpack Compose + Material 3 adaptive**, min SDK determined by empirical device support and dependencies (candidate API 26+, verify), target current Play-required SDK. This is a distinct Android application in its own module/repository and must not inherit the static web repo's read-only contract or share its credentials.

```text
UI: Connect / RemoteBrowser / LocalBrowser / Workspace / TransferQueue
    │ user actions, permission decisions, state flows
Application: ConnectionUseCases, CopyPlanner, ConflictResolver, TransferCoordinator
    │ immutable source/destination references, lifecycle-safe API
Storage adapters:
    ├── LocalDocumentStorage (ContentResolver + SAF tree/document URIs)
    └── RemoteStorage
         ├── FTP / FTPS via tested Apache Commons Net adapter
         └── SFTP via vetted Android-compatible SSH library
Persistence:
    ├── Room: queue intents, item states, nonsecret safe metadata
    ├── Keystore-backed profile vault: opt-in secrets
    └── User-authorized persisted URI permissions (only when necessary)
Execution:
    ├── Kotlin coroutines + bounded connection pool / streaming
    ├── Android 14+ UIDT for user-initiated lengthy transfers as eligible
    └── WorkManager for deferrable retries; lifecycle policies and notifications
```

**Transfer domain:** `LocationRef = Remote(profileId, path, capabilitySnapshot) | AndroidDocument(uri, grantState) | AndroidTree(treeUri, relativePath)`. `TransferIntent = sourceRefs + destinationRef + mode(copy|move) + conflictPolicy + requiredNetwork`. `TransferItem` journaling includes preflight metadata, byte progress, retry count, temporary name, resume support, verification proof and safe result. Compose observers collect state; rendering never executes protocol calls.

**Adapter contract:** `list`, `stat`, `openRead(offset?)`, `openWrite(temp?,offset?)`, `mkdir`, `rename`, `remove`, `features`. Capabilities include atomic rename, readahead/range, directory recursion, symbolic links, file timestamps, checksum, case sensitivity, remote free space where possible. Avoid forcing a single filesystem metaphor onto SAF providers that reject rename, random access or directory writes.

### Protocol validation

- FTP/FTPS: **binary transfers**, passive/EPSV first, IPv4/IPv6 and NAT cases, MLSD/MLST preferred with LIST fallback, feature tests for SIZE/MDTM/REST and server clock/UTF-8 variants. FTPS channel protection and TLS certificate validation on by default.
- SFTP: verify host key; first-trust screen shows fingerprint + server address; changed key is a blocking error until deliberate user review. Password or secure key methods. Do not install unchecked algorithm/crypto fallbacks.
- No automatic TLS downgrade, no default “ignore cert,” and no implicit reuse of SFTP authentication for FTP.

### File safety and resumability

- Copy default. Move = copy, verify, then delete with explicit destructive confirmation and per-item receipt.
- Temporary filenames (e.g. unique `.expository-part-<opaque>`) where the server supports them; atomic rename where supported. Never assume atomic behavior on all servers/SAF providers.
- Classify errors: login/auth, TLS/SSH trust, LAN permission, network timeout, passive data channel, source read, destination write, no space, conflict, unsupported operation, changed source, checksum/verification.
- Streams with fixed buffers, no full-file RAM loads. Queue defaults: 2 concurrent files (configurable after benchmarking), metadata reads separated from transfer streams, bounded folder discovery and explicit estimated/unknown totals.
- No unsafe resume on a source whose metadata/identity changed. On non-seekable Android streams, safely retry from zero or stage securely if user consents.
- Upload/download only succeeds after close, server final reply and destination checks succeed. With weak server checksum support, label verification level (size-only vs hash, where supported).
- Handle Android process termination, device sleep, metered-network transitions, Android 15/17 restrictions; never claim forever-running background transfers.

### Android integration specifics

- Activity Result APIs around `ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE`.
- `DocumentsContract` and `ContentResolver` rather than guessed filesystem paths. `EXTRA_INITIAL_URI` may suggest a location, not override chooser.
- Only persist URI grants if needed to finish the transfer and remove unused grants later. Respect providers that do not support operations or revoke access.
- Android 17 / target SDK 37+: `ACCESS_LOCAL_NETWORK` requested for direct LAN connections when required, just in time with a clear explanation; refusal must not prevent Internet-host use.
- Native drag/drop: Compose `dragAndDropSource`/`dragAndDropTarget`; user gestures produce *app-internal location references*, **not** token-bearing URLs. If using Android cross-app `ClipData`, grant only scoped URIs and respect lifetime/revocation.
- Android 14+ user-initiated transfer job vs WorkManager selected by actual task lifecycle. Current foreground-service quotas and notifications explicitly accounted for.

## 6. Sample interaction flows

**A. One-file download**  
Remote file row → **Save to device** → Android “Save as” system dialog → **Saving …** → **Saved** receipt / Open.

**B. Folder download**  
Remote folder → **Save folder** → Android “Choose destination folder” → preflight `Pictures/2026` → “Create folder `2026`?” → Queue → **12 saved, 1 skipped**.

**C. One-file upload**  
Remote `/incoming` → **Upload** → Android “Choose file(s)” → `report.pdf` → **Upload to /incoming** → Done.

**D. Folder upload**  
Remote `/incoming` → **Upload folder** → Android “Allow access to chosen folder” → review include-parent-folder toggle → Queue → Done.

**E. Split-pane drag copy**  
Workspace Local top/Remote bottom (portrait) → long press select three local photos → drag to Remote → drop target says **Copy 3 photos → /gallery** → preflight → Queue. Same outcome via selected-items toolbar **Copy to remote**. Reverse drag gives **Copy to device**.

**F. Overwrite danger**  
When `report.pdf` already exists: show exact source/destination and “Keep both,” “Skip,” “Replace,” optionally “Apply to remaining.” Default is **Keep both or ask**, never silent overwrite. “Move” demands explicit additional approval.

## 7. Visual system recommendation

A recognizable Expository brand, but Android-native rather than an embedded web page. Use soft dark graphite surfaces, subtle colorful location accents and clear typography. The colors represent **location/direction** rather than arbitrary file types: Local and Remote get distinct labels/colors with accessible text equivalents. Brief, tasteful transitions on drag targets and queue completion; reduced-motion users get instantaneous state changes.

The hero is a remote folder list and two conspicuous actions, not a diagram of sockets. The optional workspace feels like a pocket-sized desktop transfer client. Preserve logical hierarchy when switched between orientation/window sizes; do not insist on a fixed portrait layout.

Design deliverables before code: 393px portrait home/browse, 360px compact with accessibility text, portrait stacked workspace and keyboard/gesture alternative, 16:9 landscape side-by-side, first-run connection wizard, conflict decision sheet, transfer queue/partial failure, Android picker handoff diagrams, TalkBack traversal map.

## 8. Acceptance ledger for initial beta

| ID | Acceptance test | Required result |
|---|---|---|
| A01 | First server connection using FTPS and SFTP | Clear success + trusted identity; no insecure fallback |
| A02 | Plain FTP requested | Strong nonencrypted warning, explicit opt-in, no silently saving password |
| A03 | Save one remote file through Create Document | URI-based stream succeeds; original name/MIME; no broad storage permission |
| A04 | Save remote nested folder through tree picker | Hierarchy and bytes preserved; partial failure shown; restricted root explained |
| A05 | Upload 1/20 Android files via Open Document multi-select | Exact destination confirmation, all provider streams handled, good receipts |
| A06 | Upload permitted Android folder via tree picker | Recursive staged plan, no path traversal, no silent child omissions |
| A07 | Android share sheet single/multi-item upload | Temporary URI grants handled; no leaked content or credentials |
| A08 | Portrait split 393px | Two distinct scrollable panes or intentional auto-collapse; 48dp controls; no horizontal overflow |
| A09 | Landscape split/tablet | Side-by-side independent paths, divider and drag/drop both directions |
| A10 | TalkBack/keyboard copy alternative | Complete each core transfer without dragging; focus and status meaningful |
| A11 | Drag local→remote and remote→local | Preflight says Copy with count and destination; no deletion |
| A12 | Conflict cases: same name, case difference, changed size | Ask/keep/skip/replace; per-file resolution and receipt |
| A13 | Wi-Fi drop, slow server, timeout, process kill | Durable queue with correct restart/retry or explicit unsupported resume |
| A14 | Android 17 LAN permission denied/revoked | Explain why LAN connection failed; no repeated nag on unrelated Internet host |
| A15 | FTPS bad certificate / SFTP changed host key | Block safely and allow reviewed trust workflow; never auto-disable validation |
| A16 | 2 GiB file, 2,000 files, unicode, whitespace and weird filenames | No full-memory loads, reliable size accounting, no corruption or escaping |
| A17 | Storage full / provider revoked mid-download | Per-item failure, partial file policy, clear remediation, no false success |
| A18 | Copy then move | Only move after individually validated destination; source retained on failures |
| A19 | Privacy regression | No secret logs, analytics, service, clipboard leak, unsafe backup or manifest overreach |
| A20 | Pixel phone, 200% text, TalkBack and reduced motion | Independently inspected real-device proof, not just emulator screenshots |

**Reliability release gate:** full test-matrix zero silent corruption; 100% of interrupted jobs have an inspectable final/recoverable state. Transfer throughput is measured rather than promised. Accessibility and core native chooser flows are beta blockers, not optional polish.

## 9. Delivery roadmap and independent work lanes

Additional required beta acceptance cases **A21–A32** for all location combinations, remote New folder+Move, bulk rename, collision cycles, staging/undo truthfulness and 10,000-row preview are specified in [Workspace and bulk rename](WORKSPACE-AND-BULK-RENAME.md#5-implementation-contracts-and-acceptance-gates). The initial A01–A20 tests alone are **not** a complete beta gate for this updated first ask.

**Phase 0: protocol/storage feasibility and UI contract.** Implement a fake RemoteStorage and fake document provider with test fixtures, secure SFTP/FTPS handshake proof on local test server, Android 17 LAN permission prototype, and exact picker/drag sketches on 393px + landscape. Validate protocol library licenses, Android runtime compatibility and API provider behavior.

**Phase 1: native MVP end-to-end.** Secure saved server connection + remote browser, Save single and multi/folder, Upload single/multi/folder through Android pickers, share-in upload, honest durable transfer queue and conflicts. Also deliver the basic **Move to… → New folder** remote workflow and local/remote direction logic. Preserve a one-pane, one-handed default. No production secrets in CI.

**Phase 2: beta-complete three-mode workspace and renamer.** All Local/Local, Local/Remote and Remote/Remote pairings with adaptive panes, long-press selection, drag/drop in either direction, precise server-side move vs streamed-copy UX, keyboard/TalkBack alternatives and a compact live-preview **P0 bulk renamer**. Complete A01–A32 before beta. Advanced regex/presets can follow P0 with separate acceptance.

**Phase 3: remote-native ecosystem.** DocumentsProvider, previews/streaming where safe, one-way backup with dry-run, Wi-Fi/metered policies, WebDAV/SMB capability adapters. Optional Expository-brand image gallery extras last.

Suggested clean ownership:
- **Agent A, protocol reliability:** FTP/FTPS/SFTP drivers, test servers, trust, capability probing, fault injection.
- **Agent B, Android integration:** SAF, shared content URIs, Android 17 LAN and background execution, secure profile storage.
- **Agent C, UX and acceptance:** Compose screens/drag-drop, transfer queue, a11y, Pixel/emulator screenshot atlas and independent cross-lane integration.

Integration owner merges contracts first; each agent uses isolated packages to avoid editing common central screens. Synthetic local fixtures and low-runner CI. The existing Expository web repo remains untouched for functionality, aside from this research/proposal.

## 10. Decisions needed before implementation

1. **Product name:** working name `Expository Transfer`; approve later if brand evolves.
2. **Source home:** propose a **separate Android repo** (e.g. `gamesbyian/Expository-Transfer`) with native Gradle project and an Expository-family link, rather than putting Android secrets/build outputs beside GitHub Pages. No new repo was created by this exploratory proposal.
3. **Protocol priority:** first release should handle FTP (explicitly warned), FTPS explicit and implicit, and SFTP. Start implementation with SFTP+FTPS as secure defaults.
4. **Distribution:** likely a signed Android APK for direct install/testing first, plus Play Store policy review before broader distribution.
5. **Transfer policy:** copy default, no automatic deletes, explicit overwrite, no unattended sync in v1.
6. **Privacy commitment:** credentials never leave the device except to the selected remote server as mandated by its authentication protocol; no Expository relay. Optional backup/export must be encrypted.
7. **No forced cloud:** can choose local Android storage or installed SAF cloud providers; the client has no proprietary cloud library.
8. **Scope of system picker integration:** native save/open now, DocumentsProvider later. This avoids promising that selecting a directory with `ACTION_CREATE_DOCUMENT` can create a whole folder automatically.

## 11. Recommendation

Build **a transfer-first Android app with two complementary faces**: easy native file handoff by default, two-pane workspace for those who want to manage their files deliberately. Invest in correctness and recovery before broad protocol coverage. The actual differentiator is that people can always tell **what will be copied, where it will land, and whether the whole transfer truly succeeded**.

**Background evidence:** [Exploratory report](EXPLORATORY-REPORT.md), [SAF](https://developer.android.com/training/data-storage/shared/documents-files), [Android intents](https://developer.android.com/reference/android/content/Intent), [Compose drag/drop](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/drag-and-drop), [Android 17 LAN access](https://developer.android.com/privacy-and-security/local-network-permission), [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt), [RFC 4217](https://www.rfc-editor.org/rfc/rfc4217).
