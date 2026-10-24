# Google Drive and Google Docs transfer integration

**Status:** P0 destination requirement critically reviewed October 23, 2026. This is a design contract, **not an implemented integration or a verified Google Drive DocumentsProvider capability**. See [critical review](CRITICAL-REVIEW.md), [hardened plan](HARDENED-IMPLEMENTATION-PLAN.md).

## User promise

From either a **Local** or an **FTP/FTPS/SFTP Remote** session, choose one file, many files, or folders and select **Copy to → Google Drive**. Select the Drive account and destination using familiar Android interfaces wherever the installed provider supports them. The experience must not require manually downloading into a temporary Downloads folder and then reopening Google Drive.

### Three operations that must not be confused

1. **Save original to Google Drive**: preserve the original file and MIME type. For one file, prefer Android ACTION_CREATE_DOCUMENT; for chosen folders, ACTION_OPEN_DOCUMENT_TREE and DocumentsContract where the provider permits writable trees/creation. A remote-to-Drive copy streams through the phone, and completion must report an explicit proof level. Provider write success alone is **WRITE_ACKNOWLEDGED**, not independent confirmation of content or a chosen Drive folder. Progress, cancellation, duplicate handling, quota failures and permission revocation use the normal durable transfer queue.
2. **Send to Google Drive app**: use Android ACTION_SEND / ACTION_SEND_MULTIPLE and scoped content URI grants when direct provider writing is unavailable. This is a handoff to another app, not a transfer whose successful completion Expository can necessarily verify. Receipt: **Handed to Drive, confirm upload there**, never **Saved**.
3. **Create Google Doc**: separately offered, opt-in conversion for supported document formats. Using the Google Drive API, create a Workspace document with its distinct MIME type and verify the current importFormats. The user must understand format conversion, possible fidelity changes and account authorization. This is not just uploading a DOCX and not equivalent to **Open with Google Docs**. Show **Save original instead** when unsupported.

### UX placement and priority

**P0:** Present **Google Drive** alongside **Device** and **Remote server** in both simple Browse actions and split-pane Copy-to. A real, selected, **writable and verifiable destination** is mandatory for the original-file copy promise. First test the installed Drive/SAF provider. If it lacks a writable destination or folder root, provide a **native Drive API adapter within P0**, not a P1 wish. Never market the Share-to-Drive fallback as a completed P0 file transfer.

**P0 fallback:** Dedicated Drive upload adapter with Google OAuth, scoped to `drive.file` where a user-selected parent can truly be authorized. The Google Picker is primarily a file/folder *selection/open* interface, not a generic Save-as destination dialog: experimentally prove folder authorization and Drive `files.create(parent)` with the current mobile picker. If that narrow scope cannot write to the desired folder, offer a clearly labeled app-created folder or explicit product-level scope review. **Never silently escalate to restricted whole-Drive OAuth.** The companion is native Android and has no credential relationship with the read-only Expository web app.

**P0 user entry points:** **Save original to Google Drive**, **Create Google Doc (convert)** when the live API confirms a supported format, and **Open in Google Docs** when an installed compatible app handles the file. These are three separate actions and receipts. **P1:** richer Docs editing/reimport and reusable conversion preferences. No automatic conversion or overwrite. A native Google Doc is not byte-for-byte identical to the source document.

### Capability decision tree (mandatory before announcing Save)

1. **SAF route:** Launch user-requested `ACTION_CREATE_DOCUMENT` (file) or `ACTION_OPEN_DOCUMENT_TREE` (folder). Enumerate returned URI and provider flags, verify `FLAG_DIR_SUPPORTS_CREATE`, successful child creation and write, and if available requery size/name. The Android picker does not guarantee that the installed Drive app exposes a writable folder tree.
2. **Drive API route:** If the selected Drive destination is unavailable via SAF, authorize a scoped account with Google Identity and `drive.file`. On Android, Google's Picker flow is an *open/choose* flow; require a tested `PICKER_ALLOW_FOLDER_SELECTION`/parent grant + `files.create` upload using returned folder ID. Record created file ID/parent and requery with `files.get`. For large uploads, use a resumable upload session; after ambiguous success, reconcile before starting another create.
3. **Handoff route:** If only Android Share is available, label the action `Send to Drive app`. Receipt is **Handed off, unverified**. Do not count it as confirmed completion, and do not delete originals.
4. **Unsupported case:** If neither verified SAF nor authorized API writer is available, show which permission/provider capability is missing. Offer retry/account choice or explicit handoff, never fabricate a destination.

**OAuth scope boundary:** The non-sensitive `drive.file` scope covers files created/opened with the app or selected by the user in the relevant authorized flow. Do not assume it can browse/write every existing arbitrary Drive parent. Broad `drive` scope is restricted and requires separate consent, verification/security review and product decision. No broad scope just for convenience.

**Native Docs:** Before conversion, check live Drive `about.importFormats` for source→Google Workspace MIME support. Native Docs conversion may change layout, content and ownership semantics. No byte-for-byte preservation claim and no automatic conversion from an ordinary upload.

### Folder and queue semantics

Remote → Drive and Local → Drive multi-file/folder transfers must preserve hierarchy if the selected DocumentsProvider supports subfolder creation. No assumptions about filesystem paths, persistable URI grants, seekability, folder-root access, or arbitrary provider writes. The engine may use bounded streaming or user-approved encrypted/app-private staging for inaccessible or short-lived source streams. Expository must explain whether a job remains resumable after process death.

Across all providers, Copy is non-destructive. **Google Drive destination Move is deliberately out of v1 scope:** neither a Share handoff nor a mere SAF provider-write acknowledgement authorizes source deletion. Other cross-provider Move commands require explicit confirmation and the agreed proof threshold. In remote → Drive use, no claim of server-to-server direct transfer unless a separately verified transport supports it.

### Acceptance criteria

- Both Local and Remote offer **Copy to → Google Drive** without first requiring a download to Downloads.
- One and multiple files can be handed to a selected Google Drive destination with clear source/target and preflight; folders work wherever provider capabilities allow.
- Handle absent Drive app/account, unsupported tree URI, grant revocation, user cancellation, quota exceeded, duplicate names, offline/provider delays, interrupted remote streams, and process restart without false success.
- Receipts distinguish **Drive write acknowledged**, **Drive destination confirmed**, **Drive content hash verified when actually possible**, **Handed to Drive app (unverified)**, and **Created Google Doc (converted, source bytes not identical)**.
- Standard Android picker/share flows require only user-selected scoped permissions. No MANAGE_EXTERNAL_STORAGE or broad Drive OAuth scope merely for simple uploads.
- A Google Docs conversion confirms source MIME support, format changes, target Workspace MIME, ownership, completion and failure recovery.
- Test on physical Android, TalkBack, Android DocumentsUI/Drive provider variants, with large files and nested folders, before claiming P0 acceptance.

## First-class destination UX: initiation from anywhere (owner requirement, 23 October 2026)

**In scope at P0:** a local selected item, local folder, FTP/FTPS/SFTP remote item or remote folder can start **Send / Copy to…** without manually downloading to the device first. Both the simple single-pane browser and *each* pane of any-two-location Workspace expose the same intent-based action sheet. This is **not** permission to write to arbitrary Google accounts or to masquerade as a third-party app.

### Visible action sheet

From one selection, multi-select, or long-press:

1. **Save to device** → Android Save-as / folder picker.
2. **Copy to other pane** → preflight destination, default **Copy**, never implicit **Move**.
3. **Save to Google Drive** → original bytes/MIME, explicitly selected account and folder, actual write/confirmation when provider/API supports it.
4. **Create a Google Doc** → available on supported *single document* source formats only; conversion notice includes format/layout risk and selected Google account. Use a real Drive `files.create` conversion and verify the resulting native Docs MIME/id. Don't show for arbitrary directories or pretend a ZIP/image/audio will automatically become an editable word-processing document.
5. **Open with Google Docs** → when a compatible installed document editor is advertised by Android, launch an `ACTION_VIEW` (or applicable verified edit/open contract) on a MIME-correct `content://` URI with a temporary read grant. This opens the existing format for editing/viewing, **not** a copy saved to Drive or a native Docs conversion. If unavailable, offer **Save original to Drive, then open**, and explain that the user must open it in Docs.
6. **Share with app…** → Android Sharesheet `ACTION_SEND` / `ACTION_SEND_MULTIPLE`; can show Drive/Docs if the system resolves them. Label resulting receipt **Handed off to another app; destination unverified**. Do not pretend choosing an app guarantees import.

The primary CTA on a selected file is **Copy / Send to…** with Device, Google Drive, Google Docs, and Other app as clear destinations. On 360–393dp screens display a compact bottom sheet with 48dp targets, short destination labels and a one-sentence description of each action. A long-press context menu and accessibility actions lead to the *same preflight planner*, not a separate copy engine. At 200% font size, sheet becomes scrollable/full-height, never clipped. For Google Docs the presentation is **Create native Doc** versus **Open existing format**, not a fictional Docs folder.

### Source-to-destination matrix

| Selected source | Save original to Drive | Create native Doc | Open with Docs | Share / handoff |
|---|---|---|---|---|
| Android local file (SAF `content://`) | P0, picker/API verified | P0 if MIME supported | P0 if target app supports MIME | P0, scoped URI |
| FTP/FTPS/SFTP file | P0, bounded phone-mediated stream, no Downloads round-trip | P0 if MIME supported, conversion with separate receipt | P0 via temporary ContentProvider URI/spool if necessary | P0 using scoped temporary URI; may need bounded private staging |
| Multiple local or remote files | P0, per-file queue + folder destination | P0 only with explicit **separate Doc for each supported document** bulk confirmation; no silent arbitrary formats | Not a guarantee; share/open per selected compatible file | P0 where target accepts `ACTION_SEND_MULTIPLE` |
| Local/remote folder | P0 recursively when provider/API authorizes child folders | **Not a single Google Doc**; offer Save folder to Drive or document-by-document conversion | Not a folder destination | Share as ZIP only with explicit archive semantics and size limitations |
| Local/Local or Remote/Remote split pane | Same action in either pane | Same guarded option for selected supported docs | Same content-URI/open workflow | Same explicitly unverified handoff |

**The target must say what it really is:** Drive is storage; Google Docs is a document editor / native document type, not a general destination filesystem. A `pdf` or image conversion via Drive may embed an image or invoke OCR; it is not a text-preserving equivalent of the original. Check `about.importFormats` at use time and make conversion opt-in with a preview/explanation. The mobile Docs app can open/edit formats such as DOCX/ODT/TXT/RTF/HTML, but it is not proof that an arbitrary `ACTION_SEND` intent will resolve to Docs on every Android build.

### Technical and proof contract

- **Source adapter:** `FileSource.openStream()` produces a bounded, cancellable stream. For remote-to-Drive direct API transfer, data flows **remote server → phone → Google HTTPS API**; never server-to-Google magic, raw credential in URI, or an unrestricted publicly accessible staging URL. For Docs app handoff, provide a narrowly granted `content://` through the Android `FileProvider` or a vetted streaming `ContentProvider`. An arbitrary remote stream might not be seekable and a receiving app may re-open it after Expository loses foreground status; if a reliable seekable snapshot is necessary, transparently stage in bounded encrypted app-private space, retain the URI grant for the receiving activity lifetime, and clean it up.
- **OAuth and folder identity:** default to narrow `drive.file`, use Google Picker only as a **user-selected access grant** and not an assumed Save dialog; check permission on intended parent using `files.create`. Resolve account+parent **before** streaming. Don't rely on a path-looking label or a remembered Drive folder name as identity; use account-scoped opaque parent/file IDs in private journal and recheck permissions after account changes.
- **Resumable upload:** treat session URL as a bearer capability (never log or expose), persist encrypted job state if session is resumable, send chunks in 256KiB multiples except final, reconcile `308` acknowledgements, verify the final created file ID/parent/size and hash only if Drive provides a comparable checksum. A true native Docs conversion generally **does not support pre-generated file IDs**, so ambiguous create results need careful session/Drive reconciliation before another create; never blindly repeat a create that may have committed.
- **Device/provider controls:** probe `ACTION_CREATE_DOCUMENT` for a single target file and `ACTION_OPEN_DOCUMENT_TREE` plus create/write flags for folder copies; a Drive SAF provider may not expose a writable root. Use Android FileProvider grants for shares/opens, and never expose remote credentials or private paths in Intent metadata. `ACTION_VIEW` and `ACTION_SEND` are **different**: a target editor may appear for only one.
- **Observable receipts:** `PLANNED` → `TRANSFERRING` → `WRITE_ACKNOWLEDGED` → `DESTINATION_CONFIRMED` (and `HASH_VERIFIED` if applicable). Native Docs conversion ends `GOOGLE_DOC_CREATED` with Drive ID, final MIME and conversion disclaimer, **not** a byte-equal copy. Sharesheet/editor handoff ends `HANDED_OFF_UNVERIFIED` or `OPENED_EXTERNALLY`; neither counts as a verified Drive save. Revoke/expire grants and clean ephemeral staging without deleting a source.
- **Conflict identity and duplicate avoidance:** before creating, show whether the exact selected parent contains a same-name item if visibility permits; otherwise state that duplicate-name detection is **unknown** rather than confidently claiming clear. On request timeout, check resumable upload/session and observed file IDs; a newly constructed native Workspace document must not be silently duplicated on an ambiguous response.
- **No invisible feature exclusions:** if Docs is not installed, login is revoked, import MIME unsupported, or Drive selected folder lacks write permission, the relevant action must explain why and give **Save original**, **Choose another Drive folder/account**, or **Share/open via Android** as a clearly distinct alternative. An unavailable route is not a reason to request whole-Drive OAuth silently.

### P0 acceptance extension (D13–D20)

- **D13:** Local DOCX selected → **Create Google Doc**: explicit conversion consent, selected account/folder, verified native `application/vnd.google-apps.document` ID and readable Docs link; local source unchanged. No fabricated byte-size/checksum equality.
- **D14:** Remote DOCX or TXT selected → same conversion without first writing to public Downloads; interrupted network, process death, drive quota and resumed session each produce accurate per-item status.
- **D15:** Remote and local non-convertible content (binary, unknown formats, folders) → **Create Google Doc** unavailable or explicit unsupported explanation, **Save original to Drive** still available.
- **D16:** Local and remote document → **Open with Google Docs** via an Android editor/view intent when supported; target not installed / intent resolution fails produces explicit fallback. Opened editor is not falsely marked uploaded or converted.
- **D17:** `ACTION_SEND` to Drive/Docs and `ACTION_VIEW` to Docs must be tested independently; canceled chooser, receive-app rejection and short-lived grants never mark the transfer complete.
- **D18:** Twenty files and nested folders local/remote → Drive verified hierarchy; before/after account switch, wrong-parent denial, offline interruption and ambiguous `files.create` do not create silent duplicates or lose originals.
- **D19:** 393dp portrait, landscape and 200% font scale show one-tap Google destinations from either split pane, with clear selected source/target and distinct **copy vs convert vs open vs handoff** preview; TalkBack can complete without drag.
- **D20:** Provider compatibility matrix across installed/not installed Drive and Docs, writable/nonwritable Drive SAF roots, Google Picker folder permission yes/no, expired `drive.file` authorization, and constrained quota; receipt labels match actual proof. No live private file names, IDs or tokens in public artifacts.

**Primary evidence:** [Android SAF Save-as / tree behavior](https://developer.android.com/training/data-storage/shared/documents-files), [Android secure FileProvider shares](https://developer.android.com/training/secure-file-sharing), [Google Drive mobile Picker](https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker), [Drive scope least privilege](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Drive resumable upload, native import and pre-generated ID limitations](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [Google Docs supported Android import formats](https://support.google.com/docs/answer/7068618?co=GENIE.Platform%3DAndroid&hl=en).

## Primary platform references

- Android Storage Access Framework: https://developer.android.com/guide/topics/providers/document-provider
- Android document picker workflows: https://developer.android.com/training/data-storage/shared/documents-files
- Google Drive API uploading and Workspace import conversion: https://developers.google.com/workspace/drive/api/guides/manage-uploads
- Google Workspace MIME types: https://developers.google.com/workspace/drive/api/guides/mime-types

## Evidence, acceptance expansion, and references

Drive P0 cannot be marked delivered until **D01–D09** in [the hardened plan](HARDENED-IMPLEMENTATION-PLAN.md#release-acceptance-additions-d01d12) have been exercised against a real Android Drive provider/account and an actual Drive API OAuth client. Use a testing account; never log OAuth tokens, parent IDs or real file names in public CI.

- [Drive OAuth narrow/restricted scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Google Picker for mobile, folder selection and its file-open semantics](https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker)
- [Drive files.create and MIME settings](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/create)
- [Drive resumable uploads and importFormats](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Android provider operation flags](https://developer.android.com/guide/topics/providers/create-document-provider)
