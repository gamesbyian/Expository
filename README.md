# Expository

The **Sizes** view turns the already loaded Git blob index into an interactive file-size treemap with folder drill-down and an accessible list. Truncated tree indexes are marked as partial; no extra file scan is performed.

### Explore the story of your repository.

A mobile-first, dark-first visual explorer for GitHub repositories, with just enough rainbow to make a directory listing feel alive.

**[Open Expository](https://gamesbyian.github.io/Expository/)**

Expository browses **public repositories without login** and can browse selected **private repositories** using a fine-grained GitHub personal access token with **Contents: Read-only** access. Tokens stay in the browser tab's memory: they are not saved to local storage, embedded in links, or sent to an Expository server. For public repositories Expository keeps shareable browse links in the address bar; for **private** repositories it clears repo/path fragments, including incoming links that fail authentication, and the share control copies a standard GitHub URL instead. Private browsing still requires the visitor's own GitHub authorization. Use only your own trusted deployment when supplying a token. A user with a private-repo link still needs their own GitHub access.

## What you can do

- Explore branches and collapsible nested folders
- Search paths across a repository and browse recursive image galleries
- Switch between gallery thumbnails and compact file lists
- Browse up to 100 branches on demand from the repository menu, or enter any branch manually; private branch names stay in tab memory
- Sort by name, size or extension; filter by file category and adjust thumbnail density using collapsible phone filters
- Run bounded, opt-in full-text scans with matching-line snippets
- Read source with line numbers and lightweight syntax colours; copy or download originals
- Find text across the loaded source preview, cycle matching lines and jump directly to any loaded line. Source rendering is windowed to 500 lines at a time instead of creating thousands of DOM rows; the first 500,000 characters of a file can be inspected, with a clear warning when truncated. Expand the optional, **heuristic** symbol outline to jump directly to likely functions, classes and types in JS/TS, Python, Rust, Go, Java or Kotlin. Outlines are heuristic rather than full language parsing and scan only the first 2,600 loaded lines.
- Star public repositories for quick switching, without storing private repository names
- Preview SVGs, photos, plain/safe Markdown, source files, PDFs, and supported media
- Pin two images for side-by-side comparison, touch-friendly reveal, or a bounded local pixel-difference heatmap
- Select up to 12 images across repository folders, inspect a contact sheet, and save a PNG locally without uploading private art
- Zoom images from 1× to 4× with two-finger pinch and drag-to-pan gestures, or use keyboard-accessible zoom controls; native page scrolling stays available at fitted 1×. Rotate raster and SVG previews clockwise in 90° steps, with geometry fitted to the stage; rotation never modifies the downloaded original. Where the browser supports the Fullscreen API, the image viewer has a fullscreen/exit control, with Escape exiting fullscreen without abandoning the preview; unsupported/refused fullscreen is clearly surfaced.
- Inspect the recent Git history of an individual file
- Expand JSON as a safe tree or switch to raw source
- Follow safe relative Markdown links within repositories and view CSV/TSV as bounded tables
- Enter Story Mode for dated GitHub commits, releases, genuinely merged PR milestones and undated tags. Load older commits explicitly, up to four pages (200 commits). PR discovery starts with the 20 most recently updated closed PRs, and you can explicitly load up to 3 older pages (80 closed PRs total). Only genuinely merged PRs enter the dated story, and even 80 closed PRs may omit older merges.
- Copy deep links to folders and previews
- Switch dark/light appearance, and clear private browsing credentials explicitly

Story Mode uses real GitHub evidence, displays the sample size, and makes no claim to reconstruct missing history. The [feature roadmap](FEATURE-ROADMAP.md) distinguishes shipped capabilities from remaining planned work.

Expository is **read-only**, uses direct requests to the GitHub API, and has no advertising, analytics, server-side session store, or dependency-loaded remote scripts. Its browser Content Security Policy restricts outgoing fetches to the GitHub API and forbids remote scripts, workers, objects, and external media origins. Inline app code/style and browser-local blob previews are allowed to preserve the standalone-file architecture. This is an additional defense, not a substitute for the inert DOM renderer and private-token checks. GitHub API rate limits and tree-index truncation may limit results on large repositories. When GitHub's 1,000-entry directory limit is reached and a complete Git tree index is available, Expository supplements the folder from that existing index, without additional API requests. If the recursive Git tree is truncated or unavailable, Expository can instead try bounded nonrecursive Git tree reads for the currently opened capped folder (root plus up to eight directory levels). It labels incomplete responses and caps displayed recovery at 5,000 direct tree entries; it never treats a truncated result as complete. On a rate-limit response Expository uses GitHub's reset/Retry-After headers where available, shows a retry time, and refrains from making further requests during the cooldown. Requests started in an older repository/session are aborted when switching or disconnecting. It is not a replacement for GitHub's editor.

For the feature plan and current development stages, see the [weighted feature roadmap](FEATURE-ROADMAP.md). Not every planned item is implemented.

## Expository family: Android Transfer exploration

The Expository brand is exploring a **separate, native Android FTP/FTPS/SFTP transfer client**. This is a product proposal, **not an implemented app or permission expansion of this read-only GitHub explorer**. The first requirements are native Android **Save to device** / **Upload files or folders** using system interfaces, optional Device/Device, Device/Server and Server/Server split panes with drag-and-drop and accessible copy controls, fast same-server **Move to… → New folder** and a compact preview-first **bulk renamer**.

- [Online competitor, user-review, platform and engineering research](docs/android-transfer/EXPLORATORY-REPORT.md)
- [Detailed product proposal, user flows, architecture, priorities and acceptance gates](docs/android-transfer/PRODUCT-PROPOSAL.md)
- [Any-two-location split workspace and preview-first bulk renamer: concrete screens, remote-move rules, failure recovery and acceptance tests](docs/android-transfer/WORKSPACE-AND-BULK-RENAME.md)

## How this site is published

This public repository is now the **canonical application and development source**. The earlier `gamesbyian/general-purpose/tools/expository/` version is preservation/bootstrap history, not a second active development lane. The Pages deployment continues to use root `index.html` only. Public agent instructions, synthetic test fixtures and visual review scripts live beside it; no personal repository content or credentials are included.

The original `.expository-managed` marker has been removed deliberately: it prevents the archived guarded publisher in the old private source from overwriting current public development. `.nojekyll` disables Jekyll transformations.

For a byte-level release check, run the manual **Expository Pages Surface Audit** Action, or run `node scripts/pages-audit.cjs` with network access. It compares the deployed `index.html` with this checkout and reports whether a few already-public development paths are also served through Pages. The check does not change settings, download private content, or submit credentials. A public repository does not become private just because its development files are omitted from Pages.

For development work, start at [AGENTS.md](AGENTS.md) and [the live work queue](docs/WORK-QUEUE.md). For screenshot acceptance and a standalone offline atlas, see [visual review](review/README.md) and the manual **Expository Visual Review** Action. The [weighted roadmap](FEATURE-ROADMAP.md) is the strategic feature list; it is not evidence that every item is already implemented.

## Development and verification

The ordinary PR check runs `node --test tests/expository.test.cjs` plus lightweight script syntax checks. For meaningful interaction changes, run `node tests/browser-smoke.cjs` with Chrome installed. Visual reviews are **manually dispatched**, synthetic-data-only, and produce an offline HTML screenshot atlas with SHA/provenance receipts. A passing browser run does not imply Pixel 6 Pro handset approval or artistic acceptance.
