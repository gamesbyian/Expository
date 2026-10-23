# Expository live work queue

**Source of truth for next work.** Reconcile live main/PRs before taking a task; update these rows only when delivery state changes. Keep completed implementation history in PRs, not in repeated status paragraphs.

| Order | Status | Deliverable / acceptance |
|---|---|---|
| E01 | Complete (#1) | Public canonical agent router, lean CI, synthetic fixture suite and manual screenshot atlas with provenance |
| E02 | Implemented in PR | Multi-select up to 12 images, in-tab sheet preview/PNG export, three concurrent fetches, explicit session cleanup; physical phone acceptance still pending |
| E03 | Next | Tighten real-world repo access: GitHub API rate-limit handling, branch/path deep links, untrusted SVG/Markdown audit, avoid token leakage |
| E04 | Planned | Responsive code viewer improvements: selective file search, reliable highlighting/language labels, keyboard traversal, long-file virtualization |
| E05 | Planned | Image inspection: pinch/drag zoom, selected comparisons and truly registered pixel diff for same dimensions |
| E06 | Planned | Repository Story Mode: pagination/source-backed milestones (releases, PRs), evidence timestamps and explicit coverage |
| E07 | Planned | Real device/owner acceptance: Pixel 6 Pro portrait touch/focus/Chrome, private token use, 200% font, reduced motion and large gallery |
| E08 | Planned | Public packaging: security review of public Pages serving non-app project files, optional PWA after privacy review |

**Exclusions:** no automatic personal-data collection, server-hosted credentials, cross-project data mining, CI screenshot floods or unverified AI narration. Any extra work follows the same read-only and evidence contract.

**Closure:** mark a lane complete only with merged PR, exact tests, evidence class and remaining known limitations. If a parallel agent lands the feature first, validate and retire duplicate work instead.
