# Expository Transfer native engineering

This self-contained `android-transfer/` Gradle root is a temporary isolated source area pending a dedicated native repo. Do not import app writers into the public Expository Pages `index.html`, and do not change that site's read-only permissions. This source tree must remain portable.

## Start
Read `../AGENTS.md`, `../docs/android-transfer/HARDENED-IMPLEMENTATION-PLAN.md`, `../docs/android-transfer/RELEASE-READINESS.md`, `../docs/android-transfer/GOOGLE-DRIVE-INTEGRATION.md`, and issues #25–#28. Inspect main/PRs before editing. G0 and G1 have independent acceptance; do not mark either complete merely from scaffolding.

## Invariants
- No real server credentials, account IDs, auth tokens, user filenames, or private URLs in source, synthetic tests, CI logs or APK.
- Read-only Pages app keeps its separate trust model. App must never import web tokens.
- Pure planner contains no Android types or I/O. UI/drag/button only generate a reviewable immutable plan. Executor, durable queue, live adapters and receipt grades belong to later gates.
- Unknown/stale capabilities block unsafe native moves and deletes. Copy cannot call rename. Reconciliation is mandatory for uncertain writes.
- Google Drive Save, Docs conversion, external Open and Share handoff have different destinations, confirmation and receipt meanings.
- The historical G0 shell was synthetic-only. Current G2/G4 code performs foreground SAF Copy and pinned SFTP Save As/Upload, with app-private journaled pre-write attempts in PR #42. Device installation, process-death recovery and provider-specific acceptance remain unproven. Do not confuse compiled APKs or synthetic server labs with real device acceptance.

## Workflow autonomy and real-server evidence
Agents may trigger the repository's opt-in workflows after reviewing their
security and scope. For a bounded read-only FTPS interoperability check, add
one exact `/transfer-smoke <profile>` comment to a PR using the connected
repository-owner GitHub identity, then inspect the resulting Actions run and
record its outcome. Public profiles and owner FTPS profile allowlists, safety
requirements, and manual SFTP fingerprint procedure are in
[`EXTERNAL-SMOKE-OPERATIONS.md`](../docs/android-transfer/EXTERNAL-SMOKE-OPERATIONS.md).
Normal PR CI stays synthetic and cheap. Never use these workflows for owner
media mutation, public demo writes, load tests, or claims of Android-device
acceptance. A triggered run is not a passed test; investigate failures, update
source and rerun with evidence where warranted.

## Commands
Use Gradle 8.13 / JDK 17:
`gradle :core:planner:scenarios --no-daemon` from this directory, no Android SDK necessary.
`gradle -PwithAndroid=true :app:assembleDebug --no-daemon` with Android SDK 36 installed.
A debug signing key must be ephemeral/local and never checked in. Record APK SHA256 and CI run; normal PR tests only exercise synthetic Kotlin.
