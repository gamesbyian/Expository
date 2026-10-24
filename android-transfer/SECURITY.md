# Native Transfer security and Android 17 plan

## Non-negotiable boundaries
The native app ID `dev.expository.transfer`, Android Keystore / local vault (not implemented), OAuth client (not configured) and future network permission set are independent of Expository's read-only GitHub Pages explorer. Never use or copy the web app's GitHub token. The G0 shell has **no INTERNET, account, broad-file, or local-network permissions**, no real server URLs, and no persistent files.

Real FTP/SFTP credentials will be entered by the user, stored only in a device-local protected vault when implemented, never in drag payloads, scripts, receipts, GitHub Secrets, or CI artifacts. Redact authority names, paths and user filenames in telemetry; the current deterministic CI uses synthetic names only. GitHub Actions debug signing is test-only; never publish its debug keystore or present the APK as a signed release.

## Android 17 LAN rule, API 37
Android 17 enforces `android.permission.ACCESS_LOCAL_NETWORK` for targetSdk **37+**, including socket connections to LAN FTP/SFTP servers. The G0 skeleton targets SDK 36 and **must not claim** that it has tested local-network access or request the new Android 17 permission at this target level. When adding real network adapters and moving to target 37, request `INTERNET` for internet hosts; add and request `ACCESS_LOCAL_NETWORK` at runtime **only when needed for a user-initiated LAN connection**. Provide a plain explanation, denial/revocation handling and deterministic fake verification. Never silently downgrade to plaintext FTP or retry indefinitely after a permission denial. Follow the official platform guidance: https://developer.android.com/privacy-and-security/local-network-permission .

## Provider and destructive semantics
The Android Storage Access Framework picker returns opaque granted URIs; never manufacture filesystem paths or escalate to MANAGE_EXTERNAL_STORAGE. Persist grants only if the provider legally offers them; revoke/URI-remap stops or revalidates jobs. Remote-to-Drive copy must use verified writable SAF access or a narrowly authorized Drive API adapter; Share and external Docs opening remain unverified handoffs. Drive conversion is a separate opt-in fidelity-changing action. No source delete before destination proof and explicit confirmation.

## Evidence
Synthetic fixture data are **E1**, an APK compilation artifact is **build provenance**, not a device acceptance. Keep debug APK artifacts at short retention; no user data in screenshot jobs. Real device, server and OAuth tests must be opt-in with separately documented consent and scope.
