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

**P1/P2:** Explicit **Convert to Google Docs** action and **Open with Google Docs** interoperability for supported documents. No automatic conversion or overwrite. A native Google Doc is not byte-for-byte identical to the source document.

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
