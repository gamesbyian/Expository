# File-size treemap visual review · 2026-10-23

**Scope:** One inspected screenshot from the opt-in synthetic Chrome visual atlas. This is implementation evidence, not owner-approved visual reference or physical Pixel acceptance.

- Review run: https://github.com/gamesbyian/Expository/actions/runs/40040002339
- Exact checkout SHA: `166964525f5f50b4b8573568ec29542809de19a3` (temporary PR merge checkout)
- Fixture: `synthetic-github-v1`, repository `gamesbyian/Glimpse` synthetic tree
- Chrome version: `155.0.8059.39`
- Viewport: **393×852 CSS pixels**
- Capture timestamp: `2026-10-23T23:30:07.813Z`
- Screenshot: `expository-sizes-393.png`, SHA-256 `3cdcff0b2b563503c694d0676f8ec70257c1913fea7873bd91f374cf1e23072e`
- Evidence artifact: `expository-visual-atlas` ID `12538951361` (short-retention Actions ZIP, contains an offline HTML viewer and `receipt.json`)

## Observed screen

**Viewed-no-findings for this 393px fixture state:** Two size-proportional, distinct colored tiles (`design` = 500 bytes, `docs` = 408 bytes) fill the bounded chart. Labels and byte values sit at the top of each region instead of barely appearing along the lower edge. The first 44px list control (`design /`) is visible within the screen after the explanatory note, and a second list item starts at the bottom. Header, breadcrumbs, controls and chart remain readable without page-level horizontal overflow. The earlier oversized chart was reduced after inspection rather than accepted by default.

**Automated behavioral checks:** existing Git tree supplied 6 indexed blobs totalling 908 bytes with zero additional scan calls. The phone fixture tested that the map contains 2 regions and 2 list controls, folder drill-down, nested treemap for `design`, return to ordinary files, first list item in viewport, and absence of horizontal overflow. Unit tests checked group aggregation, capped Other grouping, size proportions, non-overlap, and truncated-index language.

**Remaining limitations:** When the recursive Git index is truncated, treemap totals explicitly cover only indexed paths; they cannot be interpreted as repository totals. Very small rectangles need the accessible list, and at least the second list item requires scrolling at this viewport. Large real repository performance, 200% text zoom, TalkBack, touch precision, real authentication, visual owner approval and physical Pixel Chrome testing have **not** been completed. None of these is marked passed.

## Review status

Capture class: `synthetic-chromium-capture`. Human inspection status: **viewed-no-findings for the exact 393×852 image** after mobile-height adjustment. Device acceptance: **not performed**. Owner artistic acceptance: **not requested**.
