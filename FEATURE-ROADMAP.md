# Expository feature programme

> Explore the story of your repository.

**Product contract:** One public, static, dependency-free Expository deployment can explore any public GitHub repository or private repository explicitly permitted by a visitor's own read-only credential. No per-repository installation, tracking, shared tokens, cloud index or write access is required for core browsing.

**Canonical code and GitHub Pages:** `gamesbyian/Expository`, root `index.html`. The old `gamesbyian/general-purpose/tools/expository/` is retired bootstrap history, not a current publishing source. Keep authentication and read-only semantics intact; never publish sensitive repository trees, tokens, or private browsing data.

## Weighted backlog

Ratings describe *general Expository relevance* (10 is highest) rather than implementation progress. "Delivery" records scope for this programme, not an unverified production acceptance.

| Relevance | Feature | Primary inspiration | Implementation slice | Delivery |
|---:|---|---|---|---|
| 10 | Repo-wide content search | Sourcegraph, TooToo | Opt-in bounded client scan; clear file/byte caps, cancellation, line snippets | Wave 1 |
| 10 | Syntax highlighting, line numbers, code outline | Gitako, GitHub | Safe 500-line windowed source with search/jumps across the 500k-character loaded preview, plus a heuristic symbol outline; full language accuracy and beyond-cap source fetching remain | Wave 1 + E04 initial |
| 10 | Raw downloads/copy | Gitako, TooToo | Fetch through read-only API, copy text and save using local Blob | Wave 1 |
| 10 | File type/size sort and filters | FolderFrame | Folder-first and indexed-search sorting, extension and type controls | Wave 1 |
| 10 | Adjustable gallery density | FolderFrame | Touch-sized sizing control, bounded thumbnail sizes | Wave 1 |
| 10 | Huge repository resilience | Gitako, FolderFrame | Reuse complete recursive tree; for truncated trees, recover capped current folders via bounded nonrecursive tree traversal (max 9 requests, 5,000 entries). Real giant-repo testing and deeper paths pending | Wave 1/E03 partial |
| 9 | Zoom, pan, fullscreen, rotate | FolderFrame | 1×–4× bounded zoom, two-finger pinch and drag-to-pan, buttons and reset; browser-native fullscreen with explicit fallback added; display-only 90° quarter-turn rotation and browser-native fullscreen delivered; real-device fullscreen/touch validation outstanding | Wave 2 partial |
| 9 | Rendered/raw Markdown, safe structured data | TooToo | Safe Markdown links to repo paths, expandable JSON, CSV/TSV tables delivered; richer GFM rendering and universal raw toggle pending | Wave 2 partial |
| 9 | Public-only favorites and recent folders | Gitako | Public bookmarks plus on-demand branch switching without persisting private repo or branch names; recent folders pending | Wave 1 + branch discovery |
| 9 | Code symbol navigation | Sourcegraph | Heuristic, bounded in-file JS/TS/Python/Rust/Go/Java/Kotlin symbol outline with line jumps; full language parser and repo-wide symbols pending | Wave 3 initial |
| 9 | File revision timeline | GitLens, GitHub | Path-scoped last 20 commits with dates and GitHub provenance links implemented | Wave 2 initial |
| 9 | Pixel diffs | Image compare tools | Local magenta diff heatmap, exact intrinsic-dimension gate, 1024×1024 bounded sampling, explicit percent changed | Wave 2 initial |
| 9 | Multi-select contact sheets | FolderFrame | Up to 12 source images in one repo; in-tab gallery, PNG export and bounded fetches, with private-session cleanup | Wave 2 initial |
| 9 | Relative Markdown navigation | GitHub | Path-normalized internal Markdown links, traversal refusal and HTTP(S)-only external links | Wave 2 initial |
| 8 | JSON/CSV/YAML/Mermaid viewer | Structured data viewers | Bounded JSON tree and CSV/TSV safe tables delivered; YAML/Mermaid pending | Wave 2 partial |
| 8 | Activity dashboard | GitHub, GitLens | Public/restricted commit and PR APIs, bounded counts | Wave 3 |
| 8 | Story Mode: project evolution | GitLens, GitHub | Dated commits/release/merged-PR milestones, 50 commits per manual page to 200, undated tags; on-demand closed-PR paging (20/page, max 80) and no exhaustive history | Wave 3 partial |
| 7 | Slideshow | FolderFrame | Browser-local selected collection, accessible pause and interval | Wave 3 |
| 7 | File-size treemap | GitLens | Opt-in treemap groups indexed blob sizes by immediate file/folder; largest 23 + Other, drill-down, accessible list and explicit truncated-index disclosure | Wave 3 initial |
| 7 | Change-activity heatmap | GitLens | Git-backed per-path history, clear sampling limitations | Wave 3 |

### Optional adapters (never required)

- **Glimpse:** SVG storyboard sequences, state coverage, responsive overflow and interaction annotations.
- **ROYGBIV / wallgen:** decode image recipe metadata, render parameters and side-by-side material variants.
- **UR-Recomp / Ring Road:** screenshot provenance, pixel-registered visual diffs, atlas browsing.
- **General-purpose:** non-sensitive document navigation only. Never couple the public app to private audit or mail endpoints.

Detect supported formats/metadata from files. Do not hardcode owner-specific behavior or transmit private data to an external inference service.

## Implementation principles

1. **Mobile-first:** layouts tested at 360 and 393 CSS px, keyboard accessible, visible touch targets, no horizontal overflow, reduced-motion.
2. **Zero compulsory infrastructure:** no server, browser extension, third-party scripts or organization installation to browse public repos.
3. **Private by construction:** tokens are memory-only; no public bookmarks of private data; no local caching of private previews, search hits, paths or manifests.
4. **Bounded cost:** repo-scanning search must be user-triggered, enforce explicit caps (file count, file size, matches), use at most three parallel fetches, and permit cancellation. Never silently download a whole private repository.
5. **Untrusted source text is data:** never execute HTML/SVG scripts in the app's own origin; use DOM/textContent and safe parser primitives. If HTML preview is added, it must be sandboxed and separately audited.
6. **Honest fidelity:** tree index truncation, file coverage, fallback results, approximate diffs, sampling and unsupported encodings must be visible.
7. **Deploy gate:** PR unit checks, targeted Chromium fixture smoke for behavior, optional manual visual atlas when design changes warrant it, merge to the canonical `gamesbyian/Expository` Pages repository.
8. **Portable semantics:** no feature relies on `gamesbyian` paths unless optional detected adapter metadata justifies it.

## Delivery order

**Wave 1, immediate:** clear foundations for filtering/sorting, adjustable density, read-only raw actions, syntax rendering, public bookmarks; a bounded opt-in content search. Each requires regression tests. Merge only after green scoped CI and replay against Chromium fixtures.

**Wave 2:** image zoom and inspection, safe Markdown links/source mode, JSON/CSV, file history, multi-image contact sheets, pixel diff. Revisit thumbnail object URL lifetime and API quota pressure before adding more media requests.

**Wave 3:** deepen evidence-linked Story Mode with authenticated PR/release pagination and date coverage, treemap, change heatmap, code outlines and optional large-repository index. Keep all milestones evidence-linked.

## Acceptance and current limits

Unit mocks and synthetic Chromium fixtures do **not** prove real-world private GitHub authentication, full API compatibility, large-file rendering, mobile assistive technology or production performance. A public deployment is separately verified by comparing the exact static HTML with the canonical source.

Implementation status is recorded in commits/PRs and CI, not implied by backlog presence. The next audit should check desktop/Android real repositories, both public and private, rate-limit handling, and refusal of malicious links from arbitrary Markdown/SVG files.
