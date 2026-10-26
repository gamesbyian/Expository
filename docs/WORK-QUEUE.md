# Expository live work queue

**Source of truth for next work.** Reconcile live main/PRs before taking a task; update these rows only when delivery state changes. Keep completed implementation history in PRs, not in repeated status paragraphs.

| Order | Status | Deliverable / acceptance |
|---|---|---|
| E01 | Complete (#1) | Public canonical agent router, lean CI, synthetic fixture suite and manual screenshot atlas with provenance |
| E02 | Complete (#2) | Multi-select up to 12 images, in-tab sheet preview/PNG export, three concurrent fetches, explicit session cleanup; physical phone acceptance still pending |
| E03 | In progress | On-demand first-100 branch discovery and switching added; GitHub primary/secondary cooldown, generic safe API errors, abort on repo/session switch, sanitized path validation and private-URL fragment isolation; CSP request boundaries added; outstanding: real-token network validation, SVG/Markdown cross-browser security audit, arbitrary >8-level directory recovery, direct-tree 5,000-item display cap and real-world giant-repo validation |
| E04 | In progress | In-file find/previous/next, matching-line highlight and bounded go-to-line implemented; heuristic symbols for common languages implemented; 500-line windowed source rendering, cross-window find and direct loaded-line jumps delivered; accurate language parser, extended source beyond 500k characters and additional languages pending |
| E05 | In progress | Image pinch-to-zoom and drag-to-pan added with 1×–4× bounds; side-by-side and dimension-registered sampled pixel diff already shipped; browser-native image fullscreen with Escape/exit semantics added; display-only 0°/90°/180°/270° image rotation with fitted geometry added; physical touch/Android fullscreen and screen-reader acceptance outstanding |
| E06 | In progress | Merge-only PR milestones from recently updated closed PR sample and user-triggered older commit pages (max 4/200) shipped; opt-in four-page/80 closed-PR sampling with merged-only dedup delivered; exhaustive history, tag dates and live GitHub acceptance pending |
| E07 | Planned | Real device/owner acceptance: Pixel 6 Pro portrait touch/focus/Chrome, private token use, 200% font, reduced motion and large gallery |
| E08 | In progress | Read-only manual Pages source-parity and published-path audit added; app-only publish-path migration requires verified Pages configuration and rollback plan; optional PWA only after privacy review |
| E09 | Complete (#15; synthetic reviewed) | Size treemap from already fetched Git blob index, bounded groups with keyboard/touch list, folder/file drill-down, truncation disclosure; mobile visual atlas and actual Pixel acceptance separate |

## Separate Expository Transfer (native Android)

Native transfer work is ranked **only** in [android-transfer/WORK-QUEUE.md](../android-transfer/WORK-QUEUE.md); product P01–P12 and the all-pairing, folders, bulk rename and Google destination commitments are in [CURRENT-CONTRACT.md](android-transfer/CURRENT-CONTRACT.md). The [release ledger](android-transfer/RELEASE-READINESS.md) authorizes evidence/gate claims. Do not reproduce a second independent T00–T11 queue here; duplicated histories quickly become stale.

**At 26 October 2026:** Kotlin/Compose Local Open→Save As Copy has scoped Android E2; guarded SFTP/FTPS sessions, local/share handoffs, SFTP one-file Move/New Folder, journal/recovery index and preview-only renamer exist. The current mode cards **do not** fulfill independent Local/Local, Local/Remote and Remote/Remote panes. Authenticated Android server upload, Google Drive/Docs destination writes and conversions, recursive trees, executable bulk Rename Apply, physical-device E4 and signed beta E5 are **not accepted**. [PR #103](https://github.com/gamesbyian/Expository/pull/103) is **merged and API 35 E2 accepted** ([2/2 targeted tests #40403069744](https://github.com/gamesbyian/Expository/actions/runs/40403069744)), building on #108's synchronous picker-epoch guard. [#114](https://github.com/gamesbyian/Expository/pull/114) also merged root-destination SFTP Move with isolated [E3 #40404046235](https://github.com/gamesbyian/Expository/actions/runs/40404046235); neither establishes a complete split workspace or physical-device acceptance. See native ranked workboard for exact next steps.

The native app must never alter the web explorer's read-only trust boundary. Native Changes are independently tested and merged without manufacturing browser/PWA acceptance.
