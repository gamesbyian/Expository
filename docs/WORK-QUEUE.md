# Expository live work queue

**Source of truth for next work.** Reconcile live main/PRs before taking a task; update these rows only when delivery state changes. Keep completed implementation history in PRs, not in repeated status paragraphs.

| Order | Status | Deliverable / acceptance |
|---|---|---|
| E01 | Complete (#1) | Public canonical agent router, lean CI, synthetic fixture suite and manual screenshot atlas with provenance |
| E02 | Complete (#2) | Multi-select up to 12 images, in-tab sheet preview/PNG export, three concurrent fetches, explicit session cleanup; physical phone acceptance still pending |
| E03 | In progress | On-demand first-100 branch discovery and switching added; GitHub primary/secondary cooldown, generic safe API errors, abort on repo/session switch, sanitized path validation and private-URL fragment isolation; CSP request boundaries added; outstanding: real-token network validation, SVG/Markdown cross-browser security audit, arbitrary >8-level directory recovery, direct-tree 5,000-item display cap and real-world giant-repo validation |
| E04 | In progress | In-file find/previous/next, matching-line highlight and bounded go-to-line implemented; heuristic symbols for common languages implemented; accurate language parser, more language coverage and long-file virtualization pending |
| E05 | In progress | Image pinch-to-zoom and drag-to-pan added with 1×–4× bounds; side-by-side and dimension-registered sampled pixel diff already shipped; rotation/fullscreen and physical touch acceptance outstanding |
| E06 | In progress | Merge-only PR milestones from recently updated closed PR sample and user-triggered older commit pages (max 4/200) shipped; full PR/history pagination, tags' dates and end-to-end real GitHub acceptance pending |
| E07 | Planned | Real device/owner acceptance: Pixel 6 Pro portrait touch/focus/Chrome, private token use, 200% font, reduced motion and large gallery |
| E08 | Planned | Public packaging: security review of public Pages serving non-app project files, optional PWA after privacy review |

**Exclusions:** no automatic personal-data collection, server-hosted credentials, cross-project data mining, CI screenshot floods or unverified AI narration. Any extra work follows the same read-only and evidence contract.

**Closure:** mark a lane complete only with merged PR, exact tests, evidence class and remaining known limitations. If a parallel agent lands the feature first, validate and retire duplicate work instead.
