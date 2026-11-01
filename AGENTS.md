# Expository agent guide

**Mission:** ship a beautiful, fast, accessible, **read-only** GitHub repository explorer: *Explore the story of your repository.* Expository is one public Pages app for any public repository, plus each visitor's authorized private repositories. No per-repo installation, server, shared identity, trackers, or mandatory account.

This is a **small enduring router**, not a dated work diary. For what to do next, reconcile the latest `main`, open issues/PRs, [live queue](docs/WORK-QUEUE.md) and [feature roadmap](FEATURE-ROADMAP.md). Observed source and browser behavior determine implementation truth; the [visual evidence contract](docs/quality/VISUAL-REVIEW.md) determines what has actually been visually accepted. Check for concurrent agents before editing `index.html`.

## Task routes

| Work | Start here |
|---|---|
| Current priority / new agent | [live work queue](docs/WORK-QUEUE.md), current PRs/issues, affected code |
| Feature / product intent | [feature roadmap](FEATURE-ROADMAP.md), current `index.html`, corresponding tests |
| Browser behavior, accessibility, UI | [visual review contract](docs/quality/VISUAL-REVIEW.md), `tests/browser-smoke.cjs`, actual app |
| Agent visual review / screenshot atlas | [review tooling](review/README.md), manual [Visual review workflow](.github/workflows/visual-review.yml) |
| Security, private repos, token or API changes | This guide's invariants, browser network/API logic and relevant regression tests |
| CI or performance | [CI engineering standard](docs/CI-ENGINEERING-STANDARD.md), [lean validation policy](docs/quality/VALIDATION.md), `tests/ci-orchestration.test.cjs`, workflow YAML |
| Repo housekeeping | [work queue](docs/WORK-QUEUE.md), ownership notes, current source/tests |
| Android-native Expository Transfer **independent app under development** | [native agent guide](android-transfer/AGENTS.md), [ranked native queue](android-transfer/WORK-QUEUE.md), [current native contract](docs/android-transfer/CURRENT-CONTRACT.md), [G0–G9 ledger](docs/android-transfer/RELEASE-READINESS.md). Native code is scoped to `android-transfer/`; NEVER port writer behaviors into the read-only Pages explorer |

## Non-negotiable product and security rules

- **One app, arbitrary repos.** GitHub is the data source. Do not specialize core browse/search for the owner's repositories; project-specific viewers are optional, format-detected adapters.
- **Read-only by default, least privilege.** The main `index.html` explorer must never send GitHub write requests. An **optional isolated `zip-publisher.html` companion** may write only after a separate, explicit user-provided Contents: Read and Write credential, a full collision review and final commit approval; it must create a new branch only, and never transfer this credential to `index.html`. No extension installation, no embedded GitHub token, no broad OAuth scopes or secret-bearing server. Personal access tokens exist in tab memory only. Never store private repo names, paths, manifests, file bytes, user content or access tokens in localStorage, IndexedDB, service workers, telemetry, Actions logs, artifacts or URL fragments. Explicit public bookmarks may persist.
- **Untrusted files remain data.** GitHub Markdown, SVG, HTML, code and metadata must never execute as scripts in the app origin. Use safe DOM text, sanitized URLs and bounded parsers. Preserve privacy between repo/branch switches, handle stale fetches and object URL cleanup.
- **Bounded mobile work.** Avoid eagerly fetching all private source or screenshots. Show index truncation, rate limits, failed fetches, scan coverage, count/sampling, stale/incomplete evidence clearly. Reuse established GitHub fetch/cache and preview paths rather than adding a second browser or downloader.
- **Trust and product boundaries.** Proposals, fixture screenshots, automated browser tests, live production observations, actual Pixel handset checks and owner artistic approval are *different* proof classes. Never promote an attractive screenshot or green check into real-device or product acceptance.
- **Mobile-first.** Default to Pixel 6 Pro portrait Chrome/PWA ergonomics; check 360/393/412 CSS px, landscape and desktop where relevant, reduced motion, keyboard, screen reader labels, focus, safe insets and touch target comfort. Prefer coherent colorful dark styling, one-screen control flow where practical and no horizontal overflow.
- **Project ownership.** `gamesbyian/Expository` is now the **canonical source and Pages repo**. The former `gamesbyian/general-purpose/tools/expository/` is historical/bootstrap reference, **not** an active second development lane. Do not auto-sync from it or publish its private tree. Existing public app's root `index.html` is the live Pages entry point. The old `.expository-managed` deployment marker was removed to make the retired private-source publisher refuse writes; **do not restore it**.

## Autonomous agent protocol

1. **Reconcile:** inspect current `main`, PRs/issues, queue, source and relevant test; avoid duplicate work and shared-file collisions.
2. **Implement value:** pick the smallest useful slice with an observable user journey. Fix a defect before extending its subsystem. Prefer reusable helpers and one source of truth; don't write overlapping search/preview state machines.
3. **Test proportionately:** local syntax/Node checks for pure logic, targeted real Chromium fixtures for behavior. For significant visuals, **manually dispatch** the visual atlas workflow and inspect its screenshots; default PR CI stays cheap. Take screenshot artifacts only from synthetic public fixture data, never live authenticated private repos.
4. **Evidence honesty:** record exact branch/SHA, screenshot viewport, fixture and inspection status. Structural smoke test passing is not a visual review; inspecting the atlas is not actual Pixel or human acceptance. Do not invent acceptance.
5. **Finish:** focused PR, reconcile CI and base, merge safe verified work when permitted. Update queue on delivered state, roadmap on scope change, and visual ledger *only* for actually viewed evidence. Report limitations, PR/SHA and next action rather than a documentation cascade.
6. **Avoid CI factories:** one small PR validation workflow; manual, optional full viewport atlas. No screenshot harvest on every push, no runner babysitting, and no dependence on temporary one-off workflows. If a visual audit is necessary, collect and inspect meaningful states, not hundreds of near-duplicates.

## Key checks

```sh
node --test tests/expository.test.cjs
node tests/browser-smoke.cjs
node review/build-atlas.cjs --source-sha <sha> --fixture synthetic
```

The first is cheap. Browser smoke needs installed Chrome and exercises deterministic **synthetic** GitHub responses. The visual atlas generator packages those ignored screenshots into a self-contained, inspectable offline HTML review artifact; the second and third run together in the manual workflow. See [validation policy](docs/quality/VALIDATION.md) for evidence and limits.

**Precedence:** current source + observed behavior > older implementation reports; [work queue](docs/WORK-QUEUE.md) = immediate priorities; [feature roadmap](FEATURE-ROADMAP.md) = strategic scope; [visual contract](docs/quality/VISUAL-REVIEW.md) = acceptance definitions. Public visibility does not supply an OSS license; add a license only after owner choice.
