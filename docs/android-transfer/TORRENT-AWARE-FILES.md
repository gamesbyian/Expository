# Torrent-aware FTP file associations: Expository Transfer consumer contract

**Status:** planned cross-app capability; no shipped integration or measured latency acceptance. Companion: `gamesbyian/Chunks/docs/EXPOSITORY-FILE-ASSOCIATIONS.md`. Applies only to native Android Expository Transfer; the Pages GitHub viewer remains unchanged and read-only.

## User outcome and scope

When Expository browses an FTP/FTPS/SFTP endpoint representing a Chunks-managed rTorrent seedbox, optionally annotate entries with truthful torrent membership and current state without changing remote filenames or filesystem metadata. Expository works normally when Chunks is absent, locked, disconnected or not configured. Default integration is off until the user explicitly links the exact Expository endpoint/root to the exact Chunks environment; never match by hostname alone or merge different numbered ruTorrent instances.

For visible files, show a compact status with text and redundant icon: **Seeding**, **Downloading**, **Stopped**, **Torrent known / state stale**, **Association unverified** or **No known association (complete inventory only)**. Multiple torrents can own one path; present a count and allow expansion with exact scoped infohash and environment. Directories show aggregate associated-file counts only for indexed descendant coverage, never by initiating a recursive network walk during browse. Provide toggles to show/hide badges and filters for active/seeding/downloading/stopped/unassociated; do not exclude unknown coverage from safety checks. Offer **View in Chunks** by explicit user action, preserving identity and without automatically issuing mutations.

## Read-only inter-app contract

Chunks provides an optional Android permission-protected, versioned read-only ContentProvider or equivalently constrained IPC endpoint. Expository is an independent client, not granted access to Chunks' internal SQLite database or raw rTorrent credentials. Documented schema: protocolVersion; generation ID; environment immutable ID/display name; endpoint identity fingerprint (never credentials); authoritative inventory scope/coverage/completeness; observedAt and per-state timestamps; torrent identity (environment ID + infohash, supporting hash variants); torrent state; torrent base/data path; sanitized file-relative path, expected size and optional completion evidence; ambiguity and redaction flags. Multi-file/single-file layouts, zero-length files and paths containing Unicode/case differences must be modeled.

Grant only deliberately paired app/package identity with suitable signature/permission or user-mediated temporary access; protect data from arbitrary third-party apps. No implicit cross-app grants, exported writable operations, credential/token transfer, trackers, private torrent names in diagnostics, or background service merely to paint a badge. User can unlink, revoke and clear locally cached inventory. Reevaluate identity when accounts, profiles, endpoint roots or app installations change. Never place this feed in the Pages web origin.

## Path mapping and confidence

Do not assume rTorrent absolute paths equal FTP paths. Pair mapping of canonical remote data root to the FTP-visible root, validated using a bounded set of existing filenames plus size/structure and explicit user confirmation when ambiguity persists. Normalize separators and dot segments safely; preserve case unless remote semantics prove case-insensitive; preserve Unicode canonically only with evidence; reject traversal, prefix collisions (e.g. /shows vs /shows2), symlink escapes and unsupported URI schemes. Path/size agreement is **probable association**, not physical identity proof. Hardlinks, copies and renamed files require server-side file identity where available, otherwise show an uncertainty badge rather than declaring safe-to-delete. Avoid credential leakage.

## Performance / offline

Use locally indexed snapshots keyed by environment + normalized path, with independent status updates. File-list rendering reads a local in-memory/SQLite index and must not do per-file RPC or recursive FTP traversal. Chunks refreshes on explicit user request, ordinary app lifecycle or bounded change-driven events; Expository consumes generation changes without polling constantly. Run indexed join work off main thread, allow immediate ordinary FTP listing while badges arrive, cancel stale joins when navigating, and keep cache bounded. Initial full inventory costs are disclosed; UI smoothness must be established by measured 320x700 / 412x892 device traces with 1k/10k/100k file fixtures, cold/warm navigation and offline cases before claiming 'non-laggy'. Timestamp stale snapshots and show incomplete coverage explicitly.

## Data-integrity UX and operations

Association badges are advisory evidence, not a permission oracle. Every destructive or mutating operation (delete, move, rename, Replace, Smart Merge, bulk cleanup) must use the existing typed plan, independent explicit review and fresh destination/source authority. A known active association triggers a prominent targeted warning naming the affected transfer(s); stale/missing inventory never means 'safe'. If Chunks cannot supply fresh evidence, label it **Torrent status unknown**. No automatic protection bypass, silent stopping, relocation, rechecking, deletion or modification of live payload; no automatic mutation via Chunks. Copy is typically non-disruptive to source but destination overwrite and storage issues remain separately guarded.

## Execution dependencies / QA

1. After Chunks remote inventory and exact-environment identity are reliable, agree on shared schema, versioning, permissions and endpoint-root pairing fixtures in both repos.
2. Chunks implements a read-only bounded snapshot feed; test access controls, stale/revoked feeds and complete versus partial server inventory.
3. Expository implements explicit pairing, verified root mapping, local join, optional badge/filter/detail UI; maintain independent functioning and fallbacks.
4. Integrate contextual reviewed-operation warnings into current operation planner and multi-source Smart Merge; never bypass W01 dispatcher or W05 recovery.
5. Test real rTorrent + FTP/SFTP same-host trees, two profile identities, single/multi-file torrents, active/seeding/stopped, duplicate path ownership, hardlinks/symlinks, moved files, partial inventory, huge folders, offline and reinstalls. Distinguish synthetic tests, installed emulator, physical handset and actual seedbox validation. Capture latency and frame-drop metrics, security failure and no-mutation receipts.

**Priority:** additive feature after core transfer/remote correctness and operation-safety P0 gates. Track in native queue as a planned cross-app dependency; no claims of implemented badges yet.
