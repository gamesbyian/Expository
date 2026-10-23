# Expository

### Explore the story of your repository.

A mobile-first, dark-first visual explorer for GitHub repositories, with just enough rainbow to make a directory listing feel alive.

**[Open Expository](https://gamesbyian.github.io/Expository/)**

Expository browses **public repositories without login** and can browse selected **private repositories** using a fine-grained GitHub personal access token with **Contents: Read-only** access. Tokens stay in the browser tab's memory: they are not saved to local storage, embedded in links, or sent to an Expository server. Use only your own trusted deployment when supplying a token. A user with a private-repo link still needs their own GitHub access.

## What you can do

- Explore branches and collapsible nested folders
- Search paths across a repository and browse recursive image galleries
- Switch between gallery thumbnails and compact file lists
- Sort by name, size or extension; filter by file category and adjust thumbnail density
- Run bounded, opt-in full-text scans with matching-line snippets
- Read source with line numbers and lightweight syntax colours; copy or download originals
- Star public repositories for quick switching, without storing private repository names
- Preview SVGs, photos, plain/safe Markdown, source files, PDFs, and supported media
- Pin two images for side-by-side comparison, touch-friendly reveal, or a bounded local pixel-difference heatmap
- Zoom images up to 4× and pan through detail
- Inspect the recent Git history of an individual file
- Expand JSON as a safe tree or switch to raw source
- Follow safe relative Markdown links within repositories and view CSV/TSV as bounded tables
- Enter Story Mode for dated GitHub commits and releases, plus tags in a separate section
- Copy deep links to folders and previews
- Switch dark/light appearance, and clear private browsing credentials explicitly

Story Mode uses real GitHub evidence, displays the sample size, and makes no claim to reconstruct missing history. The [feature roadmap](FEATURE-ROADMAP.md) distinguishes shipped capabilities from remaining planned work.

Expository is **read-only**, uses direct requests to the GitHub API, and has no advertising, analytics, server-side session store, or dependency-loaded remote scripts. GitHub API rate limits and tree-index truncation may limit results on large repositories. It is not a replacement for GitHub's editor.

For the feature plan and current development stages, see the [weighted feature roadmap](FEATURE-ROADMAP.md). Not every planned item is implemented.

## How this site is published

This public repository is now the **canonical application and development source**. The earlier `gamesbyian/general-purpose/tools/expository/` version is preservation/bootstrap history, not a second active development lane. The Pages deployment continues to use root `index.html` only. Public agent instructions, synthetic test fixtures and visual review scripts live beside it; no personal repository content or credentials are included.

The `.expository-managed` marker is historical from the original guarded deployment and is not an instruction to overwrite this repository from the archived private source. `.nojekyll` disables Jekyll transformations.

For development work, start at [AGENTS.md](AGENTS.md) and [the live work queue](docs/WORK-QUEUE.md). For screenshot acceptance and a standalone offline atlas, see [visual review](review/README.md) and the manual **Expository Visual Review** Action. The [weighted roadmap](FEATURE-ROADMAP.md) is the strategic feature list; it is not evidence that every item is already implemented.

## Development and verification

The ordinary PR check runs `node --test tests/expository.test.cjs` plus lightweight script syntax checks. For meaningful interaction changes, run `node tests/browser-smoke.cjs` with Chrome installed. Visual reviews are **manually dispatched**, synthetic-data-only, and produce an offline HTML screenshot atlas with SHA/provenance receipts. A passing browser run does not imply Pixel 6 Pro handset approval or artistic acceptance.
