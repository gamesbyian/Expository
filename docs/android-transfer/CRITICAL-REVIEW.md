# Expository Transfer: adversarial product and engineering review

**Review date:** 23 October 2026 · **Status:** historical adversarial *design* review. Native implementation now exists; this review itself is not implementation or acceptance evidence. See [current contract](CURRENT-CONTRACT.md) and [release ledger](RELEASE-READINESS.md).  
**Applies to:** [original landscape](EXPLORATORY-REPORT.md), [product proposal](PRODUCT-PROPOSAL.md), [three-way workspace and renamer](WORKSPACE-AND-BULK-RENAME.md), [Drive/Docs integration](GOOGLE-DRIVE-INTEGRATION.md).  
**Decision owner:** integration / product; implementation gates in [Hardened plan](HARDENED-IMPLEMENTATION-PLAN.md).

**Follow-up:** [second-pass UX/architecture decisions](SECOND-PASS-DECISIONS.md) and [executable N01–N26 evidence matrix](TEST-AND-EVIDENCE-MATRIX.md) strengthen Android picker cardinality, uncertain outcomes and acceptance. This does not change the NO-GO for beta-readiness.\n\n## Verdict

**Conditional GO for a small, native vertical slice; NO-GO for claiming a beta-ready client.** The research correctly identifies the market gap as transfer clarity, first-class Android file handoff, flexible two-place workspaces and a genuinely good renamer. The existing documentation is not yet a tested protocol/capability model. Some user promises exceed what Android's document providers and remote servers can guarantee. A good mock screen is not acceptance evidence.

**The product's irreducible promise:** Given user-authorized locations and selected items, display a truthful *source → destination* plan, make the cheapest **safe** operation available, show exactly what changed, and preserve recoverability after interruption. The UI may be playful; the filesystem transaction engine must be suspicious.

### Critical findings and decisions

| ID / severity | Gap or incorrect assumption | Required correction and release gate |
|---|---|---|
| R01 **Blocker** | "Choose Google Drive with Android's standard Save dialog" can sound universally available. A DocumentsProvider may omit Drive or disallow selected-folder creation/rename. Google Picker is primarily a file-access/open picker, **not proof of destination-write semantics**. | P0 provider capability probe on real Drive builds, plus a **dedicated OAuth/Drive API upload adapter fallback within the Drive promise**, not a speculative P1 feature. Distinguish native SAF write, verified Drive API upload, and unverified Share handoff. No claim of selected destination unless confirmed. |
| R02 **Blocker** | "Same-server fast rename/move" can imply atomicity and can accidentally optimize **Copy** into a destructive Rename. FTP RNFR/RNTO only requests rename; POSIX semantics must be explicitly negotiated where available for SFTP. | Optimization is allowed **for explicit Move only** after same logical server + compatible filesystem/path + conflict checks. Copy needs confirmed server-copy support or device-mediated streaming. Display *Server move* vs *Copy via this device*. Crash-after-rename becomes "uncertain, reconcile", not "retry rename". |
| R03 **Blocker** | "Verified completion" used without a proof definition. Bytes-sent is not bytes-committed; ContentResolver close/provider acknowledgement isn't an independently verified checksum. | Emit a **proof grade**: accepted, destination re-read/stat confirmed, or hash verified. Never fabricate a checksum or promise all providers expose size/time. Move source deletion needs the policy-defined minimum grade and owner confirmation; a provider-only acknowledgement may block deletion. |
| R04 **Blocker** | Bulk rename has strong UX but the operation journal, intermediate staging and inverse plan are underspecified for cycles and crashes. | Freeze immutable selection & destination listing, build a dependency graph, run safe temporary staging where supported, journal **before** every mutation and re-stat **after** it. Unknown results block retries and undo until reconciled. Undo is best-effort and never a batch atomicity claim. |
| R05 **Blocker** | "Durable queue" can imply permissions are durable. Share-intent grants may expire; SAF URI can change after rename; credentials may not survive reinstall; Drive OAuth tokens can be revoked. | Durable **intent + state**, not mythical permission persistence. Persist only grants allowed by the provider; encrypt app-private staged bytes, cap/expire them, and present "Reauthorize source" after restarts. Persist *opaque ID/reference*, not credentials in the queue. |
| R06 **High** | Six source/destination directions in three pairings do not capture the most dangerous variants: same vs distinct profile, local same vs distinct provider, Drive, subtree move, large folders. | Add an endpoint/operation **matrix** with identity, permission, staging and verification path per combination (below). Test each relevant copy and move separately. Drive is a P0 destination from Local and Remote, not a silent fourth network filesystem equivalence. |
| R07 **High** | A "remote server" is treated like a Unix filesystem. FTP lacks portable symlink discovery, charset/path casing can be unknown, and RNTO conflict semantics vary. | Capabilities are **per connected session and directory**; unknown is not true. Server feature probing plus operation-level fallback/error receipts. No guessed canonical filesystem path, case-normalization rewrite or blind retries. |
| R08 **High** | "Start with full FTP+FTPS+SFTP protocol matrix" risks swallowing the entire engineering effort before one save is trustworthy. | Deliver the first local fake adapter and secure SFTP/FTPS proof before full UI. **Plain FTP compatibility** remains user-requested and must be consciously opted into; no implicit downgrade. Release breadth gated by protocol fixture matrix, not marketing checklist. |
| R09 **High** | Portrait two-pane at high font size can produce an unusable pair of half-height scroll lists; drag gestures can interfere with selection and scrolling. | Single pane must always do full job; split is optional; collapse based on *actual measured space* and font scale; expose button equivalent to every drag. 48dp minimum interactions, TalkBack focus reading source/destination, test 200% text and switch-access. |
| R10 **High** | "Upload local or remote to Docs" conflates a source file in Google Drive with a native Google Workspace document. | Separate **Save original to Drive**, **Hand off to Drive app (unverified)**, **Convert to Google Doc (fidelity-changing)**, and **Open with Docs**. Conversion checks live `about.importFormats` and explicit MIME, and never automatically replaces the original. |
| R11 **High** | Resume/retry policy unclear for a timeout during server rename or after uploading the last byte before final reply. | Journal mutation stage; on uncertain acknowledgement, requery both source and destination before retry. Never automatically replay non-idempotent rename/remove/replace. Connection continuity doesn't imply operation certainty. |
| R12 **Medium** | Competitor complaints presented as app defects could be over-interpreted. Play ratings vary, and source documents have a dated snapshot. | Keep each review tied to a date, sample and evidence class. Benchmark via installed-device scripted tasks and measured outcomes, not ratings as engineering evidence. |
| R13 **Medium** | Repo/source hygiene: public Expository Pages browser has **read-only** security invariants. | Native writer gets separate package, signed APK, permissions, secrets, CI lane, agent guide and acceptance. Do not grant network/storage/Drive permissions to the existing web explorer. The public docs PR is research only. |
| R14 **Medium** | Background work choice is not only WorkManager vs a long coroutine. | Test Android 14+ UIDT, user notifications, Android 15 background service quota, Android 17 LAN permission and process death with real target SDK. Show blocked/recovery states; never promise indefinite unattended service uptime. |
| R15 **High** | Remote-to-Drive may require a device relay and sensitive temporary disk bytes. | Stream over bounded buffers when two authorized endpoints can coexist; otherwise ask permission before capped encrypted temporary staging. Show upstream/downstream bytes and metered-network cost, not "server-to-server" or zero-device-bandwidth. |

## Endpoint identity and operation matrix

A **Location** has stable *kind*, human label, opaque grant/profile ID, **validated underlying authority**, and a currently selected container; identity is not a color, display name or guessed path. Every mutation carries an immutable plan ID and a preflight snapshot of capability and listing revisions.

| Source → destination | Copy path | Move path, if requested separately | Important failure |
|---|---|---|---|
| SAF document/tree → same SAF provider tree | Use provider native copy only if flag supports it; else stream | Provider native move only after capability+same authority; else copy, verify, consent, delete | Rename/move may return a *new* URI/document ID; keep old/new mappings |
| SAF A → SAF B | Stream between content URIs | Copy + defined proof + explicit source delete | One or both grants revoked; nonseekable stream; provider quota |
| SAF → remote | Binary upload via device; unique staged target when possible | Copy/verify/delete only; default **Copy** | SOURCE read fails after remote partial temp; no REST/resume |
| Remote → SAF | Android Create Document or granted tree; staged where supported | Copy/verify/delete only with explicit warning | Provider may precreate empty document before transfer; no atomic commit |
| Remote A → Remote B, same verified server | For Copy: tested server-side copy **only** if advertised, else bounded device relay | For Move: RNFR/RNTO or SFTP rename only when directory/identity/capabilities permit | Move timeout makes result unknown; cross-volume rename denied |
| Remote A → Remote B, different server | Bounded authenticated stream through phone, two independent sessions | Copy/verify/explicit delete | Two links, mobile data and battery; no FTP FXP by default |
| SAF or remote → Drive via writable DocumentsProvider | Scoped document URI write, only if available and destination actually writable | **Copy only in v1**; do not delete source on provider acknowledgment alone | Drive folder absent/unselectable; grant and provider delays |
| SAF or remote → Drive API | Explicit user-scoped OAuth and resumable upload, verified created Drive file ID + metadata | **Copy only in v1** | Token expired/revoked, quota, resumable upload session expiration |
| SAF or remote → Android Share to Drive | Scoped content URI handoff | Not supported (unverifiable destination) | "Handed off" only, never "Saved" |
| Supported document → native Google Doc | Explicit conversion action with MIME mapping and preview warning | Not supported in v1 | Fidelity/structure changes, no binary equality |

**Same-server detection** needs a stronger predicate than equal hostname: protocol, port, authenticated account/namespace and server identity, plus a capability to operate on both paths. When uncertain use relay or ask; never silently attempt server-side FXP. For a **copy** between folders on FTP, RNFR/RNTO must *never* be used. It is a move/rename primitive. Sources: [RFC 959](https://www.rfc-editor.org/rfc/rfc959), [OpenSSH protocol extensions](https://www.openssh.org/specs.html), [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract).

## Formal transfer contract

**Preflight** → immutable `Plan(v,sourceIds,destinationId,mode,conflicts,capabilityFingerprint,verifiedIdentity,policy,expectedNames)`. The UI previews this exact version, not a freshly generated plan at Apply. Revalidate at execution; a changed source, destination, identity or capability blocks until reapproved.

Journal lifecycle:

```text
DRAFT → INSPECTING → READY → USER_CONFIRMED → QUEUED
      → DISCOVERING → REVALIDATING → STAGING → COPYING
      → TRANSFER_CLOSED → PROVING_DESTINATION → COMMITTING → COMPLETE
                                    ↘ NEEDS_REVIEW / UNCERTAIN / BLOCKED / FAILED
Any safe phase → CANCEL_REQUESTED → STOPPED / PARTIAL
```

- **Writes and deletes are idempotency-sensitive.** Persist intent, expected source metadata, temp ID, destination name and command attempt identity **before** request; never auto-retry a move/delete/overwrite after an ambiguous disconnect.
- **Receipt proof levels:** `HANDOFF_ONLY` (another app took an intent), `WRITE_ACKNOWLEDGED`, `DESTINATION_CONFIRMED` (requery size/path/identifier where possible), `CONTENT_HASH_VERIFIED` (supported end-to-end hash on both sides). Receipt also carries `unsupportedChecks`, `uncertaintyReason`, authoritative provider and timestamp. **A success proof must be tied to the bytes and destination of this exact job**, not an unrelated stat.
- **Staging is conditional:** temp file and atomic commit are stronger if supported, but unknown capabilities never imply atomicity. If staging/atomic rename unavailable, present a weaker guarantee before overwrite; prefer refuse-on-conflict.
- **Cancellation** may leave an app-owned temp file or an already-created SAF URI. Show the exact cleanup state; never silently overwrite an older destination on retry.
- **Move** adds a *deletion authorization and receipt per source item*. If proof is below the defined deletion threshold, stop as `COPIED_NEEDS_DELETE_REVIEW`; do not upgrade it to `MOVED`.
- **Crash recovery** begins with reconciliation of actual source/temp/destination entries. Treat ambiguous "rename applied?" as a state, not automatically replayable.
- **Operations on nested folder trees** do bounded discovery, preserve empty folders if supported, and reject symlink/junction loops and source→descendant moves.

## Rename planner, not just a rename form

A compact 393dp sheet with **Replace**, **Number**, **Prefix/Suffix**, **Case**, **Clean up**, immutable sort snapshot and extension lock is P0. Advanced regex and saved multistep recipes can be deferred; **graph safety cannot**.

1. Snapshot selected IDs, parent listing, byte-level/original names, sort order, user permissions and provider path/name rules. `Recipe` transforms a name deterministically without mutating providers.
2. Present diff for each row and aggregate `changes, unchanged, conflicts, invalid, unknown`. Do not auto-correct invalid names or pretend server casing/Unicode rules are known. Ambiguous case/normalization means **requires reconciliation**, not safe unchecked Apply.
3. Construct directed name dependencies. Unselected occupied target ⇒ block. Selected-item target ⇒ resolve ordering; swaps/cycles need collision-checked, same-directory opaque temporary names and an item-by-item journal. **Never overwrite an existing name to implement a swap.**
4. Immediately before apply, compare fresh sibling listing and source identities with snapshot. Drift invalidates the plan. Limit in-flight server commands and persist `BEFORE_SEND` / `RESPONSE` / `AFTER_STAT` evidence.
5. Abort/resume: if failure occurs after any rename, display partial result and each uncertain item. Rerun of the identical batch without server reconciliation is forbidden. Undo is an inverse *proposed plan*, not an automatic rollback; revalidate again before executing.
6. Local SAF's `DocumentsContract.renameDocument` may return a replacement URI, so update the item ID and any queued references. Remote directory renames may invalidate descendant queue paths; pause or rebase queued work under a conflict plan.
7. Validate 40 episode rename in a one-screen flow, 500 items plus 10,000 virtual preview rows, A↔B swap, A→B→C chain, case-only rename, extension override, Unicode composition collisions, peer edits mid-preview and crashes on each journal phase.

### Highest-risk test cases

| Gate | Fault injection | Must happen |
|---|---|---|
| T01 | FTP RNFR succeeds, RNTO acknowledgment lost | `UNCERTAIN`, inspect old and new before doing anything else |
| T02 | Remote rename A↔B, crash after A→temp | Temp is journalled and recoverable; neither file overwritten |
| T03 | Provider changes URI on local rename | Continue with returned URI, no stale handles used |
| T04 | User revokes SAF grant during a queued folder upload | `NEEDS_PERMISSION`, already validated items preserved, no hidden whole-disk permission |
| T05 | Plain FTP server lies about size/reply | Downgraded proof receipt / blocked destructive move |
| T06 | SFTP host key changed during pending move | Stop operations, require deliberate reviewed trust resolution |
| T07 | Two endpoints named "Home" are different remote servers | Never treat same display label as fast server-side move |
| T08 | Destination Drive provider lacks writable folder flag | Offer approved Drive API destination or explicitly unverified handoff; do not claim saved |
| T09 | Drive upload succeeded but app died before receipt | Reconcile server-created file ID / upload session before retry, avoiding blind duplicates |
| T10 | Drive → native Docs conversion | Display fidelity warning and converted MIME, no hash equality or unrequested source deletion |
| T11 | Case-insensitive remote rename `A→a` | Staged two-hop only if provider supports it, else blocked with explanation |
| T12 | User drags a directory onto its descendant | Block at plan creation and again before mutation |

## Evidence and release decision

**Not established by the research:** measured competitor speed/ratings, live Drive DocumentsProvider selection on current Pixel/Google Drive builds, native OAuth integration, actual FTPS/SFTP/FTP interop, uidt process recovery, 10k rename preview performance, owner visual approval, or any signed installable APK. No task in this review makes those claims.

**Must happen before beta:** fake-endpoint contract tests with injected failures, physical Android system picker/Drive capability tests, real protocol test servers, a reproducible signed debug APK, a credential/privacy audit, and independently reviewed UI captures at 360/393/landscape + high text scaling. Source-sensitive and credential-bearing sessions never appear in public CI artifacts.

**References:** [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files), [provider operation flags](https://developer.android.com/guide/topics/providers/create-document-provider), [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Picker mobile constraints](https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker), [Drive uploads/conversion](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt), [Android 17 LAN](https://developer.android.com/privacy-and-security/local-network-permission), [Total Commander Android Multi-Rename](https://www.ghisler.com/android/help.htm).
