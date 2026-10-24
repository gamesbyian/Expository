# Google Drive and Google Docs transfer integration

**Status:** Explicit product requirement added October 23, 2026. This is a design specification, not an implemented integration.

## User promise

From either a **Local** or an **FTP/FTPS/SFTP Remote** session, choose one file, many files, or folders and select **Copy to → Google Drive**. Select the Drive account and destination using familiar Android interfaces wherever the installed provider supports them. The experience must not require manually downloading into a temporary Downloads folder and then reopening Google Drive.

### Three operations that must not be confused

1. **Save original to Google Drive**: preserve the original file and MIME type. For one file, prefer Android ACTION_CREATE_DOCUMENT; for chosen folders, ACTION_OPEN_DOCUMENT_TREE and DocumentsContract where the provider permits writable trees/creation. A remote-to-Drive copy streams through the phone and produces a verified receipt only after the destination write completes. Progress, cancellation, duplicate handling, quota failures and permission revocation use the normal durable transfer queue.
2. **Send to Google Drive app**: use Android ACTION_SEND / ACTION_SEND_MULTIPLE and scoped content URI grants when direct provider writing is unavailable. This is a handoff to another app, not a transfer whose successful completion Expository can necessarily verify. Receipt: **Handed to Drive, confirm upload there**, never **Saved**.
3. **Create Google Doc**: separately offered, opt-in conversion for supported document formats. Using the Google Drive API, create a Workspace document with its distinct MIME type and verify the current importFormats. The user must understand format conversion, possible fidelity changes and account authorization. This is not just uploading a DOCX and not equivalent to **Open with Google Docs**. Show **Save original instead** when unsupported.

### UX placement and priority

**P0:** Present **Google Drive** as a visible destination alongside **Device** and **Remote server** in both simple Browse actions and split-pane Copy-to. Offer the native Android SAF route plus a Share-to-Drive fallback, with conspicuous provider capability/error states. Local → Drive and Remote → Drive both must work where installed providers permit the operation.

**P1:** Dedicated Google Drive destination adapter if native Android provider behavior is insufficient for folder selection, progress verification, resumability or precise destination choice. Use Google OAuth with least-privilege account scopes, separately from FTP/GitHub credentials. The companion remains native Android, not a change to the read-only Expository web app.

**P1/P2:** Explicit **Convert to Google Docs** action and **Open with Google Docs** interoperability for supported documents. No automatic conversion or overwrite. A native Google Doc is not byte-for-byte identical to the source document.

### Folder and queue semantics

Remote → Drive and Local → Drive multi-file/folder transfers must preserve hierarchy if the selected DocumentsProvider supports subfolder creation. No assumptions about filesystem paths, persistable URI grants, seekability, folder-root access, or arbitrary provider writes. The engine may use bounded streaming or user-approved encrypted/app-private staging for inaccessible or short-lived source streams. Expository must explain whether a job remains resumable after process death.

Across all providers, Copy is non-destructive. Move deletes original items only after each destination is independently verified and after explicit confirmation. In remote → Drive use, no claim of server-to-server direct transfer unless a separately verified transport supports it.

### Acceptance criteria

- Both Local and Remote offer **Copy to → Google Drive** without first requiring a download to Downloads.
- One and multiple files can be handed to a selected Google Drive destination with clear source/target and preflight; folders work wherever provider capabilities allow.
- Handle absent Drive app/account, unsupported tree URI, grant revocation, user cancellation, quota exceeded, duplicate names, offline/provider delays, interrupted remote streams, and process restart without false success.
- Receipts distinguish **Saved to Drive (verified)**, **Handed to Drive app (unverified)**, and **Created Google Doc (converted)**.
- Standard Android picker/share flows require only user-selected scoped permissions. No MANAGE_EXTERNAL_STORAGE or broad Drive OAuth scope merely for simple uploads.
- A Google Docs conversion confirms source MIME support, format changes, target Workspace MIME, ownership, completion and failure recovery.
- Test on physical Android, TalkBack, Android DocumentsUI/Drive provider variants, with large files and nested folders, before claiming P0 acceptance.

## Primary platform references

- Android Storage Access Framework: https://developer.android.com/guide/topics/providers/document-provider
- Android document picker workflows: https://developer.android.com/training/data-storage/shared/documents-files
- Google Drive API uploading and Workspace import conversion: https://developers.google.com/workspace/drive/api/guides/manage-uploads
- Google Workspace MIME types: https://developers.google.com/workspace/drive/api/guides/mime-types
