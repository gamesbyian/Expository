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

## Separate Expository Transfer exploration (not an implemented Android app)

| Lane | Status | Decision or next executable gate |
|---|---|---|
| T00 | Critical review written, pending review/merge | Recover prior research, reconcile native Android FTP/FTPS/SFTP + all three pairings + P0 bulk rename + Google Drive; apply [release blockers](android-transfer/CRITICAL-REVIEW.md) |
| T01 | Planned | G0: separate Android source and agent/CI/privacy contract; avoid putting writer code/credentials into public web Pages explorer |
| T02 | Planned | G1: pure Kotlin planner, capability identities and collision graph + fault-injection fixtures |
| T03 | Planned | G2–G5: accessible Compose fake-endpoint flow, native SAF, vetted secure protocols and durable journal |
| T04 | Planned | G6–G8: all pane pairings, first-class compact bulk renamer, explicit Drive account/parent selection and verified upload/Docs semantics |
| T05 | Planned | G9: signed sideload APK and independently witnessed device/protocol/Drive/a11y acceptance |

Evidence truth: the Android client **has not been built, tested or signed**. Do not use web-explorer acceptance to certify a native transfer application. See [hardened plan](android-transfer/HARDENED-IMPLEMENTATION-PLAN.md).

**Exclusions:** no automatic personal-data collection, server-hosted credentials, cross-project data mining, CI screenshot floods or unverified AI narration. Any extra work follows the same read-only and evidence contract.

**Closure:** mark a lane complete only with merged PR, exact tests, evidence class and remaining known limitations. If a parallel agent lands the feature first, validate and retire duplicate work instead.
