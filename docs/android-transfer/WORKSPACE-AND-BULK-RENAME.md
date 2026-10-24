# Expository Transfer: three-mode workspace and batch rename deep dive

**Supplement to [exploratory report](EXPLORATORY-REPORT.md) and [product proposal](PRODUCT-PROPOSAL.md).** 23 October 2026. Desk research and proposed design, **not shipped**, benchmarked or tested on an Android handset.

**Safety authority:** [critical review](CRITICAL-REVIEW.md) and [hardened plan](HARDENED-IMPLEMENTATION-PLAN.md) take precedence wherever older shorthand (e.g. "atomic", "verified", "same-server") is too broad. In particular, FTP rename isn't a guarantee of atomicity, only explicit **Move** may use native rename, cyclic batch updates must journal each staging transition, and the Google Drive P0 destination needs an independently verified capability or narrow-scope API adapter. The 10,000-row rename preview is a performance acceptance gate, not an assertion that it has been implemented.

## 1. Competitive signal, including the complaints

| Reference | Established behavior | Relevant friction / evidence strength | Requirement to borrow or avoid |
|---|---|---|---|
| [Total Commander for Android](https://www.ghisler.com/android/help.htm) | Two panes and explicit Copy/Move target; its **Multi-Rename Tool** supports original name/extension masks, counters, dates, search/replace, regex, immediate result preview and an attempted undo | High confidence **documented developer features**. Powerful symbolic syntax needs learning. Its undo explicitly *tries* to undo, which is not an atomic transaction guarantee. | **P0:** preview old→new; fixed extension by default; friendly operations first, patterns in Advanced. Journal exactly what happened rather than claiming transactional rollback. |
| [MiXplorer renumbering discussion](https://www.reddit.com/r/androidapps/comments/pp7x4p/) | A user got 01, 01(1), 01(2) when expecting 01, 02, 03; community explained `%A` and `%02d`, controlled by folder sort | Small Reddit support thread, **not** a population-wide defect | Visible starting index, step, pad width and selected **sort order**; show preview before Apply. Never silently change numbering based on hidden view sort. |
| [MiXplorer advanced rename discussion](https://www.reddit.com/r/androidapps/comments/1clawbd) | Another user sought literal punctuation cleanup and found advanced syntax unclear; asked for documentation | Small, qualitative report; feature may have improved since | One-tap Remove brackets / Replace text, optional regex behind Advanced, explain templates inline. |
| [Solid Explorer batch tooling discussion](https://www.reddit.com/r/androidapps/comments/hiu2r7) | One user says regex rename existed but repeatable saved chained transformations did not meet their need | Individual older anecdote, not current verification | Chain 2–4 operations with visual chip order; save named **nonsecret** recipes and reuse in local or remote pane. |
| [X-plore feature overview](https://www.reddit.com/r/androidapps/comments/182f0vz) | Enthusiasts praise dual pane tree, quick copy across panes and batch renaming; some dislike dense/dated UI | Community description and personal preferences | Keep two independent paths visible without forcing both pane trees on a 393px phone. |
| [Community manager comparison](https://www.reddit.com/r/androidapps/comments/14038q4) | Dual-pane X-plore and power-user MiXplorer win on depth; Solid on approachability; X-plore's atypical UI can be polarizing | Subjective community votes, not test results | **Three explicit workspace pair presets**, a quick single-pane default, and accessible actions parallel to drag. |
| [Android DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract.Document) | Individual document providers declare support for rename/move/remove using capability flags; operation support is **per provider** | Authoritative SDK API | Local SAF may not rename or move, even when the app sees the file. No fake filesystem paths or silent fallback to broad storage access. |

**Takeaway:** a genuinely differentiated app need not surpass every manager's protocol list. It should outperform their interaction clarity at *select destination → preview exactly what will happen → confidently execute or recover*.

## 2. The complete two-location matrix is a launch requirement

The workspace can pair **any two locations**, not just a mandatory device/server pairing.

| Left | Right | Example | Most efficient correct operation |
|---|---|---|---|
| Local SAF location A | Local SAF location B | Downloads → external SD card or another SAF provider | Try provider-native `moveDocument()` **only if flags and provider permit**; otherwise scoped stream copy → verify → optional confirmed delete. |
| Local SAF | FTP / FTPS / SFTP | Upload a Photos folder to `/incoming` | Stream bytes through phone; stage/verify/rename where capabilities allow. |
| FTP / FTPS / SFTP | Local SAF | Download server folder to selected device tree | Use native create-document/tree grant; stream into scoped URI destination. |
| Remote directory A | Remote directory B, **same server/account** | `/downloads/mixed` → new `/TV Series` folder | **First try a verified server-side rename/move** (FTP RNFR/RNTO; SFTP rename) when same filesystem/server and destination policy permit. It should not download and re-upload by default. If refused, explain and offer an explicit streamed copy fallback. |
| Remote server A | Remote server B | Backup between two FTP/SFTP profiles | Stream through device using two authenticated sessions. **Not** assumed server-to-server/zero-bandwidth; show both endpoints, network/metered cost and possibility of mobile disconnect. No FTP FXP by default. |
| Remote server A | Remote server A in two tabs | Two entirely independent paths, same profile | Same-server fast path and persistent independent navigation. Avoid deadlock on single-control-connection FTP; use a safe command transaction/connection strategy. |

**No magic assumptions:** two `LocationRef` objects each expose a type, provider/profile identifier, granted root, path, writable/rename capabilities, trust state, and session identity. A source may be local tree, single SAF URI, or remote folder; two Local panes may have different providers and access grants. `sameServer` means an explicitly validated endpoint identity and compatible session/path scope, *not* matching display labels.

**For remote moves within one server:** FTP [RFC 959](https://www.rfc-editor.org/rfc/rfc959) defines `RNFR` followed by `RNTO`, typically efficient metadata operations. Servers can refuse moves across volumes, directories, permissions, or filesystems. SFTP rename supports provider-dependent semantics and may lack POSIX replace/atomic behavior. Feature detection and error codes determine fallback; avoid optimistic “instant move” promises. A real remote rename may still cause irreversible partial results in bulk operations; each item needs its own receipt.

## 3. Interaction design: a workspace, not a desktop squashed onto a phone

### Simple first, powerful when summoned

**Initial remote browser:** after connecting, show one remote path, **Upload**, **Save to device**, **New folder**, **Move to…**, and a compact transfer-status chip. Tap any file/folder; select multiple with long-press or a visible Select action. Selection toolbar provides **Copy to… / Move to… / Rename**. Native system picker launches immediately for ordinary upload/save, without opening Expository's own local-browser UI.

**Workspace:** a separate obvious toggle offers three presets: **Device ↔ Server**, **Device ↔ Device**, **Server ↔ Server**. Both location buttons can switch independently. Users can choose any two location combinations, including two paths on the same server. Always show readable location identities and the current relative directory in both panes. Never quietly change a destination because a pane switches server.

**Portrait 360/393dp:** start with top/bottom panes, independently scrollable, 44–48dp row targets, divider with a labelled swap/rebalance control. At high text scaling/IME or height constraints, collapse to one active pane with **A / B** tabs and a pinned destination bar, retaining origin and target. Give a reachable **Copy to B** action and a corresponding Move menu even when split collapses. Avoid “drag-only” behavior; tapping an action is primary.

**Landscape/tablets/foldables:** side-by-side, resizable and independently navigable. A central visible connector identifies **A → B / B → A**, with transfer target highlight and item count. Dragging onto a folder row offers **Copy into this folder** but does not auto-move/delete. Dropping onto blank area copies to that pane's current folder. Hover/scroll near edge reveals more destination children without triggering unsafe writes during drag.

**Preflight every meaningful write:** source (including selected count), absolute/reliable relative destination, Copy vs Move, whether a new folder is made, approximate byte count (or *unknown*), secure/insecure connection marker, and conflicts/permissions. **Drag is Copy**, always; Move is explicit and separately confirmed. Within one server, explain **Move on server** vs **Copy requires transfer through device**. Undo depends on capability, not a universal guarantee.

**New folder + move flow:** Select files in remote `/incoming` → Move to… → show remotely navigable destination picker → **New folder** at current destination, inline name validation → folder appears selected as destination → **Move 12 files** → progress and inspectable receipt. Keep source pane/selection after an error. Same operation is available as a folder drop in the other remote pane.

### Safety invariants for any pairing

- Copy transfers to staging destination where supported and verifies bytes/metadata before presenting Completed. If server supports no staging, clearly label weaker guarantees.
- Same-provider local `moveDocument` and same-server remote rename may be optimized, but require capability, path and conflict preflight. Never silently turn an explicit copy into a move or vice versa.
- Cross-provider or cross-server Move is **Copy → Verify → Delete source**, with per-item verification and explicit deletion consent; interrupted items retain originals.
- No move of a folder into itself/its descendant; never follow symlinks outside user-chosen root. Revalidate source/destination at execution to catch changes since preview.
- No hidden storage/credential elevation, no impossible resume guarantee, no automatic plain-FTP fallback.
- Clipboard/drag payloads contain scoped, short-lived opaque operation IDs, not passwords, connection strings, SSH private keys or external-app-readable remote paths.

## 4. Compact bulk renamer: specification worthy of the brand

**Entry point:** select 2+ files/folders in either pane → **Rename (N)**. Remote and local use the *same* interface, but provider capabilities and server constraints are visible. The core sheet should be understandable without learning regex or placeholder language.

**One-screen portrait sheet (393dp, default):**
1. Header: `Rename 38 items` · scoped location badge · **Live preview** count (`32 change, 4 unchanged, 2 conflicts`).
2. Horizontal operation chips: **Replace text**, **Number**, **Prefix/suffix**, **Case**, **Clean up**. Tap one to expose at most 2–3 primary fields; chips can be reordered, duplicated or removed. Presets suggested only when matching a need.
3. A two-column virtualized preview: **Current name → Proposed name**, with meaningful changed spans, sorting/order control and red inline collisions. A small summary stays fixed above the Apply bar. Filter **Changed / Unchanged / Issues**.
4. Sticky **Apply N renames** (disabled while errors remain), **Save recipe**, and **Cancel**; detailed preview expands upward. Advanced drawer: regular expressions, capture groups, date/EXIF-like metadata only when available and explicit, template placeholders, recursion, counter step/padding, dry-run/export.
5. **Extensions locked by default**. Explicit **Include file extensions** toggle with warning if a transformation changes a type association. Folders have no protected extension convention.

**Examples (previewed, not blindly applied):**
- `IMG_0007.JPG` → `Holiday 007.JPG`, operation `Replace IMG_ → Holiday ` plus three-digit counter; count order = visible, pinned snapshot.
- `My Show S1 E3.mkv` → `My Show S01E03.mkv`, a regex-based advanced recipe with capture groups, validated against the sample set.
- `Episode 2 (1080p).mp4` → `Episode 2.mp4`, easy literal `Replace " (1080p)" → ""` without regex.
- `photo ()  (copy).jpg` → `photo.jpg`, **Clean up separators and bracket clutter** preset, opt-in; never default to stripping meaningful punctuation.
- Selection sorted natural ascending generates `001 ... 038`. Reversing sort **explicitly updates preview**; no hidden change at execution.

**Operation model:** pure, deterministic `RenameRecipe` pipeline evaluated on an immutable selection snapshot. Each transformation has explicit inputs and toggles for case, regex, start/step/width, date field, separator normalization and extension inclusion. A recipe is portable across different folders/profiles, but contains no user file list, server path, credentials or secret metadata. Undo ledger is specific to the operation, never a promise of general filesystem transactions.

### Collision and validity engine

Preflight against both **(1) other proposed names** and **(2) existing destination siblings not selected for rename**, with a provider-aware case-sensitivity policy. FTP/SFTP may treat names case-sensitively but mounts and destinations differ; never rely on global `lowercase()` equivalence. Handle Unicode normalization collisions, reserved `.` / `..`, slashes and NUL/control chars, excessive byte-length server limits, provider-invalid names, empty stems, extensions, and paths that become folders inadvertently. Don't silently "sanitize" into a different user-intended name: show proposed correction or block.

**Cycles and swaps:** `A → B, B → A`, case-only `Report.txt → report.txt` on case-insensitive backing storage, and `A → B, B → C` can overwrite unless operations are correctly ordered or staged. Plan a dependency graph; use unique collision-checked temporary names within the same directory where supported; only commit after a reversible/staged plan is validated. A server may deny temporary renames, atomic replace or cross-directory renames. Preflight must reveal that and offer a safe alternative rather than proceed optimistically. Folder rename must detect and update pending descendant transfer references or prevent while transfers in flight.

**Execute + recovery:** Journal each selected item with stable initial identity, parent/path, old/new names, capability/preflight, intermediate temp name, current server response and final result. On crash/timeout, reconcile actual server state before retrying; don't repeat blind `RNFR/RNTO`. Completion can be **38 renamed**, **35 renamed + 3 blocked**, or **unknown state for 1, needs reconciliation**. Undo is **best effort**: preflight collisions and external changes again, reverse the successful subset in safe dependency order, stop on ambiguity. It is not atomic rollback, and undo might be unavailable for providers that don't support it.

**Performance:** draft preview entirely local from one bounded parent listing, virtualize rows for 10,000 entries, debounce expensive regex, cap expression complexity/time; never run untrusted code or dynamically interpret templates. Snapshot order to avoid changes during preview. On remote apply, cap command rate, retry only idempotent reads automatically, and surface per-item progress. For regex, use a vetted, bounded pattern engine and guard catastrophic backtracking. For local SAF, `DocumentsContract.renameDocument` may change document identity/URI; requery returned URI and maintain mapping.

## 5. Implementation contracts and acceptance gates

`WorkspacePaneState(id, LocationRef, currentFolder, selectedRefs, sort, filter, breadcrumb, scrollAnchor, grantStatus)`. Two panes are separate; swapping positions swaps **views only**, never edits file locations.

`OperationPlan(type, originRefs, destinationRef, conflictRule, capabilityProof, confirmationVersion)`: immutable, revalidated immediately before mutation. `ExecutionReceipt` stores each item state: queued/inspecting/staging/transferring/verifying/committing/completed/blocked/unknown. UI arrows and drag targets only create operation plans; they cannot directly call `rename`, `delete` or `write` on the main thread.

`RenamePlan(snapshot, recipe, sourceAndTargetNames, collisions, ordering, staging, journalId)` drives preview and execution from the **same plan/version**, preventing UI-preview/execution drift. Plan creation is pure; adapter operations are isolated behind capabilities.

**Acceptance tests beyond the initial 20:**
- **A21** Local/local two SAF trees, including two different providers, copy and attempted move with correct permission flags; no all-files permission.
- **A22** Local/remote upload and download in each direction, multi-select and nested folders; no implicit move.
- **A23** Remote/remote same FTP/SFTP server, two different folders: a fast server-side rename/move where supported, without downloading file payload to device.
- **A24** Remote/remote *different* server profiles: streamed relay via device, honest network cost, interruption recovery and no FXP data-endpoint leakage.
- **A25** New remote folder created from **Move to…** destination picker; 10 selected files moved there with per-item status, no duplicate.
- **A26** Rotate phone/pane collapse/expand with both pane locations and selections intact; drag and action-toolbar copy produce identical preflight.
- **A27** Bulk rename 500 files with literal Replace + counter + prefix; 10,000-row preview virtualized; stable visible order and locked extensions.
- **A28** Collisions with unselected siblings, case-only changes, Unicode normalization, empty/reserved names, extension changes; invalid plans blocked before write.
- **A29** Cycles/swaps, temp-name staging, server denial after 23/50 renames; accurate partial result and recovery journal; undo never falsely promises complete restoration.
- **A30** Network timeout/process kill between FTP RNFR and RNTO, host-key changes mid-plan, local SAF URI changing after rename; ambiguous operations explicitly reconciled.
- **A31** TalkBack/keyboard completes all six pane-direction combinations, New folder → Move and renamer operations without drag; 200% system text scale with no off-screen Apply.
- **A32** Transparent external-storage semantics: moving between providers/servers never pretends to be instantaneous or atomic; no unsafe source delete on partial copy.

## 6. Delivery order and nonnegotiable beta scope

**Discovery spike:** fake LocalDocument/RemoteStorage pair matrix, implement a pure rename collision/dependency planner, validate Android picker URI grants and FTP RNFR/RNTO/SFTP rename against deliberately conflicting test servers. Design and test 393dp split-pane + one-pane fallback *before* protocol feature creep.

**Native beta P0:** easy Save to device/Upload via system UI, folder transfers, secure connections, queue and conflict engine, **all three pairings**, remote New folder/Move to…, bulk-renaming **literal, numbering, prefix/suffix, case, live preview and collision prevention**, action alternatives to drag. Shipping a UI containing tabs for these modes without completing end-to-end bytes and recovery is not acceptance.

**Beta P1, early:** regex/capture groups, chained transformations, saved recipes, cycle-safe staged renames and truthful best-effort undo after implementation/testing; flexible arbitrary server-to-server relay optimization, enhanced keyboard shortcuts and iconography. **The core compact bulk-renamer itself is not deferred.**

**Beyond beta:** sync rules, remote DocumentsProvider, more protocols, image comparisons and media extras. No FTP FXP by default; no proprietary cloud service; no internet-accessible helper server.

**Research and device caveat:** This is a source-linked design synthesis, not evidence that competitors were installed, Android FTP libraries passed target-SDK tests, or any UI was tested on the user's phone. Such acceptance is required before release.
