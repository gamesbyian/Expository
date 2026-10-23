# Expository offline visual review

This is Expository's **opt-in visual QA** lane, modeled after Glimpse's standalone storyboard atlas and Ring Road's bounded Chrome viewport evidence, but deliberately *not* a permanent screenshot job on every PR.

1. Open **Actions → Expository Visual Review → Run workflow** from the current branch. The action uses synthetic public GitHub fixtures only; it never touches private GitHub repositories or live PATs.
2. When the run finishes, download the **expository-visual-atlas** ZIP and open `review/atlas/index.html` offline in Chrome. The HTML embeds screenshots, so no web or server is needed.
3. Filter screenshots by scene or viewport. Select one or two screenshots and choose **Compare**, or double-click a plate to inspect a single view. Zoom and scroll. Use the receipt to verify source SHA, browser version, fixture, PNG dimensions and SHA-256.
4. Record *actual viewing findings* against [visual review contract](../docs/quality/VISUAL-REVIEW.md). Distinguish synthetic browser capture from actual physical Pixel observations and explicit owner art approval. A green run alone leaves inspection **uninspected**.

Local reproduction (Node 22, installed Chrome):

```bash
EXPOSITORY_ATLAS=1 node tests/browser-smoke.cjs
node review/build-atlas.cjs --source-sha "$(git rev-parse HEAD)" --fixture synthetic-github-v1
# Open review/atlas/index.html in a browser, no network required
```

Expected plates include initial gallery, 393/412/1440 image galleries, reveal/side-by-side/pixel diff, JSON and CSV viewers and Story Mode. Captures are browser-generated implementation images, **not approved visual reference artwork**. The atlas is a helper to inspect exactly what Chrome rendered, never an automated aesthetic score. A production private-repository acceptance session requires a separate local-only, explicitly authorized non-uploading test.

Avoid committing screenshots to main. The ignored `review/evidence/` and `review/atlas/` folders are generated and included only as short-retention CI artifacts. Changes to the atlas creator need unit syntax and manual Chrome verification, not a new routine workflow.
