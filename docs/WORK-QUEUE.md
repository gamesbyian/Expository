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

## Separate Expository Transfer (native development started; no functional transfer APK yet)

| Lane | Status | Decision or next executable gate |
|---|---|---|
| T00 | Complete (#24) | Critical review, hardened G0–G9 plan, Drive API fallback decision, release ledger; conflicting old #23 retired. Native code and debug-only shell now in draft PR #32. |
Second-pass design hardening: [decisions](android-transfer/SECOND-PASS-DECISIONS.md) and [26-scenario test matrix](android-transfer/TEST-AND-EVIDENCE-MATRIX.md) refine G0–G9; neither document is implementation evidence.

| T01 | In progress [#25](https://github.com/gamesbyian/Expository/issues/25), draft [PR #32](https://github.com/gamesbyian/Expository/pull/32) | Isolated native Gradle/Compose source and privacy/CI scaffold introduced; debug APK build and SHA256 verified ([run #40071709212](https://github.com/gamesbyian/Expository/actions/runs/40071709212)); dedicated repository and device acceptance remain outstanding; no writer in web Pages explorer |
| T02 | In progress [#26](https://github.com/gamesbyian/Expository/issues/26), draft [PR #32](https://github.com/gamesbyian/Expository/pull/32) | Pure Kotlin Copy/Move and rename preflight, capability/version digest, staging graph and synthetic E1 scenarios; independent property/fault stress and executor integration still required |
| T03 | In progress [#28](https://github.com/gamesbyian/Expository/issues/28), merged [PR #33](https://github.com/gamesbyian/Expository/pull/33) | One-file Android ACTION_OPEN_DOCUMENT→ACTION_CREATE_DOCUMENT Copy path and pure foreground engine merged; real-device, process death, adaptive split and broader G2/G6 acceptance incomplete |
| T04 | Open [#27](https://github.com/gamesbyian/Expository/issues/27) | G8: test Drive provider capability on physical Android, scoped API fallback and original/converted/handoff receipt semantics; renamer G7 in #26/#28 |
| T05 | Planned | G9: signed sideload APK and independently witnessed device/protocol/Drive/a11y acceptance |
| T06 | In progress · merged [#34](https://github.com/gamesbyian/Expository/pull/34), [#35](https://github.com/gamesbyian/Expository/pull/35) | G3 standalone FTPS/SFTP read/list adapters merged, controlled FTPS TLS [run #40125931787](https://github.com/gamesbyian/Expository/actions/runs/40125931787) and pinned SFTP [run #40126372217](https://github.com/gamesbyian/Expository/actions/runs/40126372217); wider matrix and device proof still open |
| T07 | In progress · draft [#37](https://github.com/gamesbyian/Expository/pull/37) | G4 pinned SFTP browse → Android Save As foreground source; app build and physical device, interruption, SAF revocation proof outstanding |

Evidence truth: native Android/Compose **source now exists in draft PR #32**, with pure Kotlin scenarios exercised; a synthetic-only **signed debug APK has been built with recorded SHA256 provenance**, but **has not been installed or tested on-device**. Do not use web-explorer acceptance to certify a native transfer application. See [hardened plan](android-transfer/HARDENED-IMPLEMENTATION-PLAN.md).

**Exclusions:** no automatic personal-data collection, server-hosted credentials, cross-project data mining, CI screenshot floods or unverified AI narration. Any extra work follows the same read-only and evidence contract.

**Closure:** mark a lane complete only with merged PR, exact tests, evidence class and remaining known limitations. If a parallel agent lands the feature first, validate and retire duplicate work instead.

