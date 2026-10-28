# Expository Transfer: session authentication and biometric gate

**2026-10-27 implementation boundary:** The policy reducer has E1 acceptance. The AndroidKeyStore AES-256-GCM, app-private no-backup store is now used by **explicitly saved SFTP connection-only bookmarks** (#234). Bookmarks hold host/port/folder/username/pinned SSH host key, not passwords or private keys. Loading one never connects and always requires a freshly supplied password. The key does NOT require biometric/user unlock and is not a credentials vault. No biometric prompt, unlock settings UI, persisted gate choice, or production access enforcement is shipped. Implement these before describing profile remembering or biometric authentication as available.

## Requested product experience

Each separately configured scope has an optional access gate:

- **Local workspace:** optional additional unlock before viewing an Android folder or starting operations involving that local scope. The user still chooses/grants the folder through Android's own system picker. The gate does not manufacture or extend any SAF authority.
- **Remote connection:** optional additional unlock of an existing connection view or a future Keystore-protected saved profile. SSH host-key pin, FTPS certificate check and server authentication are independently mandatory. A thumbprint **cannot** stand in for the remote server password, key, OAuth credential or transport trust decision.
- **Default security cadence when enabled:** once **per app process/session**. Separate connections have different ephemeral opaque session identities. A restart, manual Lock or new session invalidates every previous grant.
- **Convenience / higher security:** turn the optional gate Off, require a new unlock on return from background, after 1–60 minutes of genuine inactivity, or separately for each protected mutation. Show active policy and Lock now control in settings.
- **Biometrics:** use the device's native biometric UI when available. Offer the system PIN/pattern/password fallback, with capability and OS-version checks. Expository must never ask users to disclose their Android unlock secret in its own text field. Accessibility and cancellation are first-class, and repeated failed/cancelled prompts never produce a proof.

The user may independently opt in or out for Local and Remote. An operation crossing scopes needs **both** applicable approvals. A per-action prompt binds the exact immutable operation digest and consumes the authorization once, atomically across the required scopes. The app may not reuse old permissions after source/destination/profile/session identity changes. If a user switches accounts or disconnects, revoke the corresponding remote gate.

## Android integration contract (not yet delivered)

1. Generate one unguessable, RAM-only process session nonce and per-connection opaque IDs (never server URL/username/path). Never save grants in Compose saveable state, Preferences, intents, pending activities, backups, notifications or logs. Keep protected browser/file previews obscured while locked; don't rely on disabled buttons alone. Defer deep links, inbound share, copy/paste, accessibility actions, picker callbacks and background workers until gated source and destination are authorized.
2. Use **AndroidX BiometricPrompt** and `BiometricManager.canAuthenticate`; prefer `BIOMETRIC_STRONG` with `DEVICE_CREDENTIAL` fallback where supported. Android 10/API 29 and earlier do not support the combined strong+device-credential setting. Implement a checked version-specific device-credential alternative, including API 26–27, rather than constructing an unsupported prompt. Enroll/unsupported/no-lock states need truthful UI, not silent bypass.
3. Wire `SessionAccessGate.onPlatformAuthenticationSucceeded` **only from a verified platform success callback** tied to the same frozen in-flight request, Activity process nonce, endpoint identity and (per-action) operation digest. Cancel, biometric lockout, interrupted Activity, changed profile, missing device lock and unknown result all stay locked. `onAppBackgrounded` increments on actual lifecycle leave, not on the normal prompt itself.
4. Real remote **remembered** passwords/SSH key material must use authenticated, device-bound Android Keystore encryption and explicit account/profile consent, with secret zeroization and no telemetry. A low-level one-shot AEAD record store can be tested separately, but its key is currently *not user-authentication-bound*. It must never be used for password/private-key restoration by production login until a consented secret profile codec, lifecycle and native access gate are implemented and tested. The connection-only bookmark UI is separate and never invokes auto-login. Existing in-memory remote logins remain distinct from future vault unlock. Hardware-key availability, invalidation and restoration/backup need explicit acceptance before profiles can be persisted.
5. Manual Lock revokes all in-memory grants, disconnect/profile changes revoke corresponding connection grants, and policy edits invalidate all prior grants. Per-action proof may authorize a reviewed write at most once; no automatic retry after uncertain writes. No unlocking inside the mutation executor after journal commit, and no mutation on mere prompt-display success.

## Verification and release gates

**E1 current:** pure `SessionAccessGateSuite` verifies opt-in default, once-per-session, independent Local and remote connection identity, background re-lock, idle cutoff/monotonic time, per-action single-use and atomic multi-endpoint checks, settings revocation and explicit Lock. This is policy-model evidence, not Android security enforcement.

**E2 future:** a real Android emulator biometric/device-credential prompt success/cancel/error, foreground/background switches, incoming Share and picker callback while locked, two remote profiles, pane swap and selected Local→Remote copy; prove no sensitive previews, source bytes or journaled writes leak before successful unlock, and that proofs are not resurrected after process recreation.

**E4 future:** real Pixel fingerprint/device credential, missing-biometric devices, PIN fallback, lockscreen enrollment change, screen-reader accessibility, rotation, screen-off, restart, malicious/failed prompt callbacks, profile-vault unlock and actual server authentication.

Platform references:
- https://developer.android.com/identity/sign-in/biometric-auth
- https://developer.android.com/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder
- https://source.android.com/docs/security/features/biometric

No changes to app UX are accepted until the actual verified callback, lock overlay and data-flow enforcement have been tested. A pure policy reducer is **not a security boundary on its own**.
