# Expository Transfer: second-pass design decisions

**23 October 2026 design baseline. Status: these are proposed acceptance rules, while native Android code has subsequently shipped as a developer preview.** See [current contract](CURRENT-CONTRACT.md) and [release ledger](RELEASE-READINESS.md) for actual scope/proof. This supplements [the existing critical review](CRITICAL-REVIEW.md), [hardened plan](HARDENED-IMPLEMENTATION-PLAN.md), [workspace/renamer specification](WORKSPACE-AND-BULK-RENAME.md) and [Google Drive contract](GOOGLE-DRIVE-INTEGRATION.md). The previous session's documents are present on main.

## Product decision

Build a **separate Android-native Expository Transfer app**, preserving the web Expository explorer as an independent, read-only product. Its value is safe, fast, comprehensible transfers using Android's familiar file selection and saving flows. Add rich features only after the simplest Save, Upload, Move and Rename journeys really work.

### Six corrections that require explicit test evidence

1. **One file is not many files.** `ACTION_CREATE_DOCUMENT` saves a single newly created file and cannot overwrite an existing file; the system can append a numeral. For multiple files or folders, select a writable tree and create children with collision preflight. Android 11+ restricts tree selection for some roots including Downloads. Read the actual returned URI/display name rather than promising the requested filename.
2. **One-pane first, split-pane second.** Save, Upload, New folder and Move to must be discoverable without opening the workspace. Local/Local, Local/Remote, Remote/Remote workspace modes use the *same* immutable operation plan as the one-pane actions. Drag creates a proposal, not an immediate destructive command; keyboard and TalkBack actions must be equivalent.
3. **Identity and grants cannot be guessed.** An SAF document reference combines the provider authority, granted tree/document reference and observed capabilities. A remote reference combines verified connection/namespace identity, current directory and server capability evidence. Display labels or superficially equal paths are insufficient. Provider rename may return a new URI. An expired share-intent grant cannot silently become a durable upload.
4. **Copies and moves have different guarantees.** Copy defaults to non-destructive. An explicit same-server Move may use a verified rename primitive; an FTP rename must never implement Copy. Cross-provider Move means copy, destination proof, explicit source-deletion authorization, and a deletion receipt. Uncertain responses stop automatic replay. Partial progress must remain visible.
5. **The renamer executes the previewed plan.** Snapshot identities, parent listing, sort, extension policy and proposed names; hash the immutable plan. Live before/after preview counts invalid, conflicting and unchanged names. Never silently overwrite to resolve a swap or case-only rename. Journal every staged mutation and reconcile unknown outcomes. Undo is a separately reviewed inverse plan, not a guaranteed batch rollback.
6. **Drive destinations are not generic Android folders.** Try the installed Drive DocumentsProvider only when a genuinely writable chosen location is available. Otherwise use a scoped OAuth Drive API upload adapter, with tested parent authorization. A Share intent is merely handed off. Conversion to native Google Docs is a separate explicit operation with MIME/fidelity warning. Never label a handoff or conversion as a verified original-file save.

### Product language is part of correctness

Use distinct, accessible completion states: **Queued**, **Transferring**, **Write acknowledged**, **Destination confirmed**, **Content hash verified**, **Handed off (unverified)**, **Copied; source deletion needs review**, **Moved**, and **Needs inspection before retry**. Progress reaches 100% only for bytes known to be transferred, not for destination durability. Unknown-length providers need indeterminate progress instead of fictional percentages.

### Compact mobile UX acceptance

A new user should complete a one-file remote Save using the Android Save As system UI, upload a selected local file using Android's picker, and move 10 remote files into a newly created directory without entering a split workspace. The workspace offers stacked panes in portrait, side-by-side on spacious viewports, and tabbed one-pane fallback when text scale or keyboard makes two-pane unusable. Every drag target has a 48dp-or-larger button equivalent.

The bulk renamer's 393dp sheet shows operation chips (Replace, Number, Prefix/suffix, Case, Clean up), the **current sort order**, counter starting number/step/padding, extension lock and a pinned collision summary. A 40-episode file rename should not require symbolic mask syntax. The 10,000-row preview is a benchmark to *pass*, not a feature to claim before implementation.

### Android, transfer and Drive source checks

- [Android document selection and saving](https://developer.android.com/training/data-storage/shared/documents-files): Create Document writes one new document, not overwrite; document trees have restricted roots.
- [Drive resumable upload and Docs conversion](https://developers.google.com/workspace/drive/api/guides/manage-uploads): before resuming, inspect acknowledged ranges; 308 indicates incomplete, 200/201 completed, session 404 may mean expired. Conversion needs currently supported import formats.
- [Android background data-transfer choices](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options): select user-initiated transfer jobs or deferrable work by actual conditions; no indefinite background uptime promise.
- Existing [adversarial review](CRITICAL-REVIEW.md) defines stricter proof-grade and retry rules, which still take precedence.

**This design document alone supplies no code, APK, Drive authorization, protocol interop, phone test or visual acceptance proof.** Later native APK and controlled-protocol tests are separately linked in the [release ledger](RELEASE-READINESS.md); Drive delivery, physical acceptance and complete workspace remain unverified. Engineering ownership continues in #25–#28.
