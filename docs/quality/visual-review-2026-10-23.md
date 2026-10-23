# Expository synthetic visual review, 2026-10-23

**Evidence class:** viewed Chrome screenshots using **synthetic GitHub fixtures**, not a handset/owner acceptance. The inspection was conducted after the targeted PR #2 screenshot run. Browser: Google Chrome 155.0.8059.39; fixture `synthetic-github-v1`. CI run: https://github.com/gamesbyian/Expository/actions/runs/40023999317 ; artifact ID `12531368626`. PR test checkout SHA (merge ref): `2f63560cdac68653aea310b54bf3665732fe7a65`; exact PNG digests are in the archived `receipt.json`.

## Viewed observations

- `expository-contact-sheet-393.png`, **393×852**: two selected SVGs render clearly side by side, labels fit, the PNG action is visible, modal close remains accessible and no page-level overflow is apparent in this captured frame. **Viewed-no-findings** for this specific fixture image; this is not physical-touch approval, successful file-download confirmation or large-gallery performance evidence.
- `expository-gallery-393.png`, **393×852**: repository browsing/gallery usable with source labels and dark palette. However the toolbar/refinement controls consume a large fraction of the first screen before the gallery. **Viewed-with-findings:** consider compact/collapsible refinement controls while keeping discoverability and 44px touch sizes.
- `expository-gallery-1440.png`, **1440×900**: contact selection checkmarks, gallery cards and search snippet evidence visible. **Viewed-with-findings:** the sidebar's long “Forget credentials & clear session” text wraps to multiple lines and visually collides with the following heading; give the control enough vertical space or shorten its visible label while preserving its accessible name.

## Next actions

1. Fix desktop sidebar credential-button spacing and portrait toolbar density in a focused follow-up; verify 393 and 1440 screenshots.
2. Test actual export PNG bytes and ordered caption layout with a deterministic browser assertion.
3. Physical Pixel 6 Pro user journeys, real GitHub auth, reduced motion and TalkBack remain **unverified**.
4. Avoid promoting this fixture atlas into an approved visual baseline or automatic screenshot requirement.

The review artifact remains a short-retention GitHub Actions ZIP and is not committed to main. `review/atlas/receipt.json` in the artifact retains each screenshot hash and dimensions.
