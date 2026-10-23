# Expository synthetic visual review, 2026-10-23

**Evidence class:** viewed Chrome screenshots using **synthetic GitHub fixtures**, not a handset/owner acceptance. The inspection was conducted after the targeted PR #2 screenshot run. Browser: Google Chrome 155.0.8059.39; fixture `synthetic-github-v1`. CI run: https://github.com/gamesbyian/Expository/actions/runs/40023999317 ; artifact ID `12531368626`. PR test checkout SHA (merge ref): `2f63560cdac68653aea310b54bf3665732fe7a65`; exact PNG digests are in the archived `receipt.json`.

## Viewed observations

- `expository-contact-sheet-393.png`, **393×852**: two selected SVGs render clearly side by side, labels fit, the PNG action is visible, modal close remains accessible and no page-level overflow is apparent in this captured frame. **Viewed-no-findings** for this specific fixture image; this is not physical-touch approval, successful file-download confirmation or large-gallery performance evidence.
- `expository-gallery-393.png`, **393×852**: repository browsing/gallery usable with source labels and dark palette. However the toolbar/refinement controls consume a large fraction of the first screen before the gallery. **Viewed-with-findings:** consider compact/collapsible refinement controls while keeping discoverability and 44px touch sizes.
- `expository-gallery-1440.png`, **1440×900**: contact selection checkmarks, gallery cards and search snippet evidence visible. **Viewed-with-findings:** the sidebar's long “Forget credentials & clear session” text wraps to multiple lines and visually collides with the following heading; give the control enough vertical space or shorten its visible label while preserving its accessible name.

## Next actions

1. A focused follow-up PR changes the long desktop private-session label and collapses advanced filters by default on phones. **Pending re-capture** at 393 and 1440 before marking the findings resolved.
2. Test actual export PNG bytes and ordered caption layout with a deterministic browser assertion.
3. Physical Pixel 6 Pro user journeys, real GitHub auth, reduced motion and TalkBack remain **unverified**.
4. Avoid promoting this fixture atlas into an approved visual baseline or automatic screenshot requirement.

The review artifact remains a short-retention GitHub Actions ZIP and is not committed to main. `review/atlas/receipt.json` in the artifact retains each screenshot hash and dimensions.

## Focused responsive re-review (PR #3)

- **Run** [40024588395](https://github.com/gamesbyian/Expository/actions/runs/40024588395), Chrome 155, synthetic fixture, PR checkout SHA `f58829b6f65e35d9ba94999a461b6813b0dd7dcc`. Automated Chromium assertions passed for filters collapsed at 393px, manual expand/collapse, automatic desktop expansion and unchanged core UI journeys.
- **Personally inspected** `expository-start-393.png` at 393×852: default filter refinement row is genuinely collapsed, and folder cards appear appreciably higher than in the first viewed build. **Viewed-no-findings for the specific density defect.** No claim of full touch ergonomics or handset acceptance.
- **Personally inspected** `expository-gallery-1440.png` from earlier focused run [40024455162](https://github.com/gamesbyian/Expository/actions/runs/40024455162): the renamed **Clear private session** control occupies one readable line and no longer collides with the repository heading. **Viewed-no-findings for the credential-button collision.**
- Remaining: physical Pixel/browser testing, fine-touch accessibility and complete wide/zoom matrix. Evidence remains synthetic, not owner-approved; screenshots and hashes are available in the short-retention atlas artifact.
