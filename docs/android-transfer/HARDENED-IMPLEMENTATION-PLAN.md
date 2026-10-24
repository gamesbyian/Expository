# Expository Transfer: hardened implementation and verification plan

**Planning baseline:** October 23, 2026. **Stage:** independent critical review accepted for engineering planning; no native APK or protocol implementation exists in this PR.  
**Read before work:** [Critical review](CRITICAL-REVIEW.md), [Product proposal](PRODUCT-PROPOSAL.md), [Workspace and renamer](WORKSPACE-AND-BULK-RENAME.md), [Google Drive contract](GOOGLE-DRIVE-INTEGRATION.md).  
**Do not confuse with:** the public Expository GitHub repository explorer. That product is read-only and must remain so.

## North-star workflows, all release critical

1. Remote file → **Save to device** using Android `ACTION_CREATE_DOCUMENT`; remote selection → **Save folder** using permitted document tree. No broad all-files grant.
2. Local file(s) → **Upload here** using `ACTION_OPEN_DOCUMENT`, multi-select, tree or incoming share intent. Source URI may represent cloud-backed/nonseekable content.
3. Remote directory → **New folder** → **Move selected here**, using server-side rename when *explicit Move* and verified capable. No device download/reupload when safe native rename is available.
4. Split **Local↔Local**, **Local↔Remote**, **Remote↔Remote** (same/different profiles) with two independent breadcrumbs, a clear source/destination swap, drag as optional shortcut and equivalent large accessible action.
5. Select N files → **Rename N** → intuitive operations and a virtualized current→proposed preview; collisions or unknown semantics block Apply; journal + recovery.
6. Local or remote → **Google Drive** as an explicit destination. Prefer a working user-selected SAF destination; if not supported, integrate scoped OAuth + Drive resumable upload. Share to Drive is **only an unverified handoff**, not completion. Convert to Docs only as separate opt-in.
7. Any transfer interrupted → resumable only if capability/identity supports it, otherwise partial/blocked/retry-from-start receipt; never silently report success or delete source.

## Package and module boundaries

**Recommended source:** separate native Android repository (candidate `gamesbyian/Expository-Transfer`), linked by brand to Expository. Do **not** add Android credentials, build jobs or a writer feature to the static read-only Pages app.

```text
android-app/             Kotlin, Jetpack Compose, adaptive Material 3 screens
feature/connect/         server wizard, host key/certificate explanation
feature/browse/          single-pane remote browsing + system chooser handoff
feature/workspace/       independently navigable panes; drag and action equivalence
feature/transfers/       durable per-file queue, progress and receipt
feature/rename/          compact preview + error/undo UI
core/model/              immutable opaque IDs, path/provider capability types
core/planner/            pure Copy/Move/Rename plans, conflict graph, ordering
core/engine/             journalled operation state machine, receipt/verification
adapter/android-saf/     ContentResolver, DocumentsContract, DocumentsProvider capability
adapter/ftp/             FTP/FTPS client, feat MLSD fallback, TLS policy, reply handling
adapter/sftp/            verified SSH host keys, streaming SFTP
adapter/drive/           scoped OAuth, parent IDs, resumable upload + Docs conversion
core/security/           Keystore vault, redaction, secret-free intents and backups
testing/fake-endpoints/  programmable failure injection and recordings (NO user data)
testing/protocol-lab/    ephemeral vsftpd/ProFTPD/OpenSSH and controlled failures
review/                  owned synthetic screenshot atlas, separate physical acceptance
```

**Read/write adapter interfaces** should be precise, not pretend every provider is a POSIX filesystem:

- `LocationKey(kind, opaqueAuthorityId, rootId, containerId, grantId?)`, `ItemKey(authorityId, stableIdOrOpaquePath, version?)`. User-facing names are not identity.
- `Capability` is `SUPPORTED | UNSUPPORTED | UNKNOWN` **per operation/per location**, with proof/test source and expiry: list, read, write, createChild, rename, providerMove, rangeRead, append, tempCommit, serverCopy, remoteChecksum, atomicReplace, trash, caseRule.
- `stat(), listPaged(), openRead(), startStagedWrite(), finishWrite(), rename(), createDirectory(), deleteAuthorized(), probeCapabilities()`; methods that cannot be implemented return typed `Unsupported`, never pretend success.
- SAF rename returns a possibly **new URI**. Drive API upload returns Drive file ID and state, not local path. FTP may supply no stable identity beyond path/metadata; a changed file becomes `NEEDS_REVALIDATION`.
- Separate `OperationPlanner` (pure, offline), `OperationExecutor` (side-effectful, journalled), `Reconciler` (reads actual remote state), `ReceiptSigner` (typed proof evidence), `UI` (no direct remote mutation).
- **Least-authority transfer tickets**, not raw passwords in queues/drag payloads. Recover credentials only from explicit user-approved device-local vault.

## Milestones with evidence

| Gate | Implementation slice and artifacts | Mandatory evidence before advancing |
|---|---|---|
| **G0** ownership | New Android-specific AGENTS, queue, strict CI, package architecture, provenance rules, no production secret/remote URLs | Independent source privacy review, docs references consistent; public web app unmodified |
| **G1** pure planning | Kotlin/JVM `LocationRef`, typed capabilities, destination identities; deterministic Copy/Move/Rename plan, DAG and temp staging for cycles | Property-based/fuzz tests, 10k filenames under memory bounds, case/Unicode/collision fixtures, snapshot invalidation and idempotency tests; **zero** network |
| **G2** fake Android E2E | Compose single-pane + split + native picker intent launch, fake DocumentProvider and fake RemoteServer, queue and preflight; sheet-style Rename | 360/393/412dp + landscape screenshot atlas actually viewed; TalkBack semantics, keyboard; source/target and conflict plan visibly equivalent for drag and buttons |
| **G3** secure protocols | SFTP + FTPS explicit first, then implicit FTPS + opt-in plaintext FTP. Certificate/host-key rules and protocol capability probes | Local controlled server fixtures, TLS chain/hostname reject, SFTP key-change reject, active/passive/EPSV/MLSD/LIST/REST, disconnect at each protocol phase, no unsafe downgrade |
| **G4** actual SAF | Android file/folder upload/save, persist grants only when legal, ContentResolver streaming, URI-replacement handling, staged partial cleanup | On-device picker/Drive provider tests, source revocation, Downloads/data root restrictions, nonseekable streams, empty folders, 2GiB file and 2k children without unbounded memory |
| **G5** durable engine | Room-backed plan journal, user-confirmed intents, transfer queue, typed proof grades, UIDT/WorkManager path | Kill after every stage, reboot/cancel, metered constraints, idle/timeouts, ambiguous FTP final reply, no data loss/silent success, no destructive replay |
| **G6** complete workspace | Every Local/Local, Local/Remote, Remote/Remote subtype; New folder + Move into it, same-server rename shortcut, cross-server relay | Six direction matrix × copy/move, single/dual portrait, landscape, accessibility action path, physical responsive review and honest metered-cost UI |
| **G7** beta bulk rename | Preview-first compact chips + number/sort/extension lock + collision DAG + journal/uncertain/undo proposals | 40-episode UX, 500-file operations, 10k-row virtual preview, 50-item cycle failure at every step, changed sibling set blocks Apply, both SAF and server cases |
| **G8** Drive/Docs | First real Drive provider capability matrix; if SAF path fails, dedicated OAuth Drive API for P0 original upload, scoped account folder access/resumable upload; P0 native Docs conversion/open for supported files with truthful proof states | *Real* local→Drive and remote→Drive single/multi/folder, account switch/revocation/quota/conflict, unverified handoff separate, no duplicate on session expiry |
| **G9** independent beta acceptance | Signed installable debug/sideload APK, usability and performance studies, threat review, reproducible release receipts | A01–A32 + D01–D20 + T01–T12 executed or explicit failures; physical Pixel, Android 14–17 emulator matrix, TalkBack/200% text, reliable journal zero silent corruption |

**Parallel execution:** after G1 immutable interfaces are approved, assign (A) protocol adapters, (B) SAF/Drive, (C) Compose interactions, (D) quality/failure-injection. One integration owner controls common contracts and merges, avoids dual ownership of core classes. Every work lane can test against the deterministic fake adapter; no real credentials in CI.

**CI economy:** one quick PR gate (format/lint/unit, changed module only), one nightly/explicit platform build, explicit opt-in protocol server matrix, manual/targeted screenshot atlas, no automatic screenshot floods. Use immutable test server fixtures and avoid committing private listings. Failure evidence is artifact-retained briefly and redacted.

## Durable planner invariants

- `PlanId` immutable. Plan generated from verified snapshot; must be approved **after** conflict policy and destination identity resolved. UI and executor consume identical plan digest. Revalidate immediately before mutation; drift ⇒ new confirmation.
- Copy never becomes Move. Move within same server may use server-native rename only when operation capability and identity are confirmed. **Native rename is not an assumption of atomicity.**
- Source deletion forbidden while destination proof below explicit move threshold; any partial result retains originals or flags missing original as incident requiring independent diagnosis.
- Queue/staged files have a user-visible expiration and cleanup policy. Abandoned temporary files are recoverable but not automatically purged if ownership/certainty unknown.
- No automatic retries of ambiguous writes, rename, overwrite, delete, native provider move or Google Drive create after lost acknowledgement without reconciliation. Idempotency tokens where API supports them; otherwise precise remote probing.
- Per-item redacted receipt captures plan digest, endpoint *opaque ID*, source/destination relative names (only on-device), source fingerprint, bytes/size, response category, proof tier, final state and whether provider grant persists.
- Content hashes: use only when a supported read-back/server digest establishes identical bytes. `DESTINATION_CONFIRMED` is not a content hash; `HANDOFF_ONLY` isn't a transfer.
- No process-resumption guarantee when permission grant, stream seek or auth has expired. Pause `NEEDS_PERMISSION` with explicit user action.
- Cloud conversion is lossy semantics by design and never has the "bytes equal" proof grade. Keep original distinct from converted Workspace artifact.

## UI contracts worth wireframing and testing

- **Default browse:** Remote path as hero, `Upload` and `Save to device` in thumb reach. Selection reveals `Copy to...`, `Move to...`, `New folder`, `Rename (N)`, `Google Drive`. No permanent two-pane crowding.
- **Workspace:** Device ↔ Device, Device ↔ Server, Server ↔ Server presets, independently switched endpoints; in high font/IME/short height display one-pane A/B tabs with pinned destination rather than two unusably short lists.
- **Drag:** always proposes Copy and opens the *same preflight* as action button. Moving after drag is opt-in with a separate explicit choice. Internal drag holds opaque IDs, never credentials.
- **Renamer sheet:** fixed location/count/preview summary, horizontal chip menu, first 3 fields for active operation, virtualized old→new rows; errors filter and one never-offscreen `Apply N` disabled while invalid/uncertain. Recipes saved without source paths/secrets.
- **Drive choice:** `Copy to → Device | Server | Google Drive`; Drive option announces whether it is verified SAF / native API / handoff, and asks account/parent destination only with capability to complete.
- **Queue:** `Copying`, `Waiting for network`, `Needs permission`, `Cannot verify destination`, `Partially completed`, `Unknown outcome: inspect`. Do not render false ETA or progress 100% before provider completion.
- **Receipts:** saved (with proof grade), handed off (unverified), converted (format changes), moved (source-deletion receipt), uncertain (requires reconciliation).

## Google Drive and Google Docs go/no-go specifics

**First spike:** on the real Android device enumerate actual installed DocumentsUI roots/flags and perform `ACTION_CREATE_DOCUMENT` and `ACTION_OPEN_DOCUMENT_TREE` → create child → write → reopen/verify. The Google Drive app *may not* expose an equivalent writable tree. Do not claim a universal Drive folder picker.

**Guaranteed fallback:** implement native Android OAuth authorized Drive API with `drive.file` where the flow can grant access to the specific parent file/folder (Google Picker + permission test). The current mobile Google Picker flow is fundamentally an *open/select* flow. Probe folder selection, files.create parent permissions and response with a controlled account before claiming it is a destination picker. If `drive.file` cannot authorize the chosen destination, present a truthful narrowed app-owned folder option or block and ask for deliberately reviewed wider-scope product requirements; never silently escalate to `drive` restricted scope.

**Upload:** `files.create` resumable as appropriate, report created ID, parent, MIME, MD5 checksum for binary if returned, `files.get` readback and quota errors; after upload session expiry investigate whether a file exists before blindly creating another. Auth and secret retention follow Google Identity/Android security guidance. Local/remote → Drive and supported-file **Create Google Doc** / **Open with Google Docs** actions are P0 entry points only once real behavior verified.

**Docs:** opt-in separate conversion using specific native Google MIME and live `about.importFormats` checks; preserving original is default. `Open with Google Docs` is a handoff, not conversion or a copy guarantee.

Sources: [Drive narrow scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [mobile picker limitations](https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker), [Drive upload/convert](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [Android document provider flags](https://developer.android.com/guide/topics/providers/create-document-provider).

## Release acceptance additions D01–D20

- **D01:** Drive app absent: option explains API alternative, no claim SAF route works.
- **D02:** Drive app installed but no writable SAF tree: flow selects supported API adapter, not false Save.
- **D03:** Local → Drive single + 20 files: same Drive account/parent, no temporary Downloads staging, exact receipt.
- **D04:** Remote → Drive file + nested folders: bounded relay, preserved folder structure, source remains intact.
- **D05:** Denied and revoked OAuth, wrong account, moved destination folder, quota error: per-item recoverable state.
- **D06:** Drive upload accepted, response lost: **no blind second files.create**; reconcile session/file ID.
- **D07:** Share to Drive unverified: "Handed off" state; no green "Saved".
- **D08:** Convert supported DOCX → Google Docs: verify MIME/importFormats, confirm, retain source; report conversion not byte equivalence.
- **D09:** Unsupported conversion (MIME, account policy, too large): offer Save original; don't coerce content.
- **D10:** Cross-server move source deletion blocked until destination proof and explicit approval.
- **D11:** Same-server remote copy must not invoke FTP RNFR/RNTO or SFTP Rename; only Move may.
- **D12:** 10k virtualized rename preview at 200% text, stable sort, no misnumbering after scroll.
- **D13–D20:** First-class Google Docs vs Google Drive action semantics, safe Android open/share contracts, per-pane source parity, real native Doc conversion, unverified handoffs, and multi-file recovery, as defined in [Google Drive/Docs destination UX](GOOGLE-DRIVE-INTEGRATION.md#p0-acceptance-extension-d13d20). Neither a Sharesheet launch nor a Google Drive filename display can substitute for proof of a successful copy/conversion.

## Explicit stop-ship defects

Silent overwrite, silent source deletion, trust verification disabled, plaintext FTP entered without warning, a failed provider write marked "Saved", an unverified Drive share marked "uploaded", duplicate Drive files after uncertain retry, a destroyed source after an uncertain remote rename, unrecoverable collision swap, leaking paths/secrets in public artifacts, drag-only critical operation, fixed-height split with offscreen Apply, or a queue that cannot determine which items did/didn't commit after process death.

This file is an engineering execution contract; changing scope, priority or required proof needs an updated acceptance receipt, not just a UI mock.
