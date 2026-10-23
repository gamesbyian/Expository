# Expository feature programme

> Explore the story of your repository.

**Product contract:** One public, static, dependency-free Expository deployment can explore any public GitHub repository or private repository explicitly permitted by a visitor's own read-only credential. No per-repository installation, tracking, shared tokens, cloud index or write access is required for core browsing.

**Canonical code:** `gamesbyian/general-purpose/tools/expository/`; public static-only mirror: `gamesbyian/Expository`. Keep authentication and read-only semantics intact. Never publish the private repository tree, logs, tokens or private browsing data. The public deployment must contain only reviewed application code and non-sensitive public documentation.

## Weighted backlog

Ratings describe *general Expository relevance* (10 is highest) rather than implementation progress. "Delivery" records scope for this programme, not an unverified production acceptance.

| Relevance | Feature | Primary inspiration | Implementation slice | Delivery |
|---:|---|---|---|---|
| 10 | Repo-wide content search | Sourcegraph, TooToo | Opt-in bounded client scan; clear file/byte caps, cancellation, line snippets | Wave 1 |
| 10 | Syntax highlighting, line numbers, code outline | Gitako, GitHub | Safe text-node highlighting, first-party dependency-free language patterns | Wave 1 highlighting + lines; outline later |
| 10 | Raw downloads/copy | Gitako, TooToo | Fetch through read-only API, copy text and save using local Blob | Wave 1 |
| 10 | File type/size sort and filters | FolderFrame | Folder-first and indexed-search sorting, extension and type controls | Wave 1 |
| 10 | Adjustable gallery density | FolderFrame | Touch-sized sizing control, bounded thumbnail sizes | Wave 1 |
| 10 | Huge repository resilience | Gitako, FolderFrame | Bounded concurrent network, keep truncated-index warnings, incremental pages | Wave 1 ongoing |
| 9 | Zoom, pan, fullscreen, rotate | FolderFrame | 1×–4× zoom, scroll/pan/reset shipped; native pinch, rotate, fullscreen remain | Wave 2 partial |
| 9 | Rendered/raw Markdown, safe structured data | TooToo | Render/source switch, safe local Markdown links, JSON tree, CSV tables | Wave 2 |
| 9 | Public-only favorites and recent folders | Gitako | Never store private-repo names/paths or tokens, clear control | Wave 1 bookmarks |
| 9 | Code symbol navigation | Sourcegraph | Lightweight client outline, optional generated index for large repos | Wave 3 |
| 9 | File revision timeline | GitLens, GitHub | Path-scoped last 20 commits with dates and GitHub provenance links implemented | Wave 2 initial |
| 9 | Pixel diffs | Image compare tools | Canvas-based diff with pixel-alignment and mismatch caveats | Wave 2 |
| 9 | Multi-select contact sheets | FolderFrame | Client-only capture with explicit selection count and caps | Wave 2 |
| 9 | Relative Markdown navigation | GitHub | Safe links constrained to repository path | Wave 2 |
| 8 | JSON/CSV/YAML/Mermaid viewer | Structured data viewers | Bounded expandable JSON tree and raw toggle implemented; CSV/YAML/Mermaid pending | Wave 2 partial |
| 8 | Activity dashboard | GitHub, GitLens | Public/restricted commit and PR APIs, bounded counts | Wave 3 |
| 8 | Story Mode: project evolution | GitLens, GitHub | Evidence-backed commits/tags/releases/PRs with provenance links; no invented chronology | Wave 3 |
| 7 | Slideshow | FolderFrame | Browser-local selected collection, accessible pause and interval | Wave 3 |
| 7 | File-size treemap | GitLens | Indexed git blob sizes; label when index is truncated | Wave 3 |
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
7. **Deploy gate:** source-branch unit checks, real Chromium fixture smoke including portrait viewport screenshots, reviewed single-file publication to `gamesbyian/Expository`, then public source-parity check.
8. **Portable semantics:** no feature relies on `gamesbyian` paths unless optional detected adapter metadata justifies it.

## Delivery order

**Wave 1, immediate:** clear foundations for filtering/sorting, adjustable density, read-only raw actions, syntax rendering, public bookmarks; a bounded opt-in content search. Each requires regression tests. Merge only after green scoped CI and replay against Chromium fixtures.

**Wave 2:** image zoom and inspection, safe Markdown links/source mode, JSON/CSV, file history, multi-image contact sheets, pixel diff. Revisit thumbnail object URL lifetime and API quota pressure before adding more media requests.

**Wave 3:** first-class Story Mode (commits, tags, releases, PR milestones), treemap, change heatmap, code outlines, optional large-repository index. Keep all milestones evidence-linked.

## Acceptance and current limits

Unit mocks and synthetic Chromium fixtures do **not** prove real-world private GitHub authentication, full API compatibility, large-file rendering, mobile assistive technology or production performance. A public deployment is separately verified by comparing the exact static HTML with the canonical source.

Implementation status is recorded in commits/PRs and CI, not implied by backlog presence. The next audit should check desktop/Android real repositories, both public and private, rate-limit handling, and refusal of malicious links from arbitrary Markdown/SVG files.
