# Story Mode older PR pagination · Synthetic visual review

**Evidence class: viewed Chromium screenshot with synthetic GitHub fixtures.** Not physical Pixel 6 Pro or owner art approval.

- GitHub Actions run: https://github.com/gamesbyian/Expository/actions/runs/40041455453
- Source checkout SHA: `09097951118dce6a381396435d242eccd9cffef6` (temporary PR merge checkout)
- Fixture: `synthetic-github-v1` with 20 recently updated closed PRs (one merged) and one older merged PR from the explicitly fetched second page.
- Chrome: Google Chrome 155.0.8059.39, emulated 393×852 CSS-pixel viewport
- Captured: `2026-10-23T23:45:50.261Z`
- Screenshot: `expository-older-prs-393.png` SHA-256 `f442e8c46e71b64349f0c8b3f68b84cc9282563806750f16c872168a3b293d86`
- Visual atlas artifact: `12539422688`, short-retention HTML screenshot gallery/receipt.

**Viewed-no-findings for this captured state:** Expository's focused Story Mode toolbar now shows only Files and Sizes. The story heading, authenticity/date disclaimer, four filter controls, sampling receipt and first merged PR milestone are visible without the previous overflowing gallery/contact-sheet toolbar. The screenshot displays `2 merged PRs among 21 recently updated closed PRs` across 2/4 possible pages. Unmerged PRs were not converted into fictitious dated events. Colors and labels remain readable in this one synthetic frame.

**Behavior verified by Chrome:** The second PR page was explicitly requested; one older merged PR appeared, PR-only filtering showed two legitimate merged milestones, and existing commit paging/release navigation continued working. The file/gallery toolbar was suppressed in Story and Sizes views but restored upon returning to files, preserving the original selected images. Unit tests cover per-page limits, deduplication, stale responses and closed-not-merged exclusions.

**Still unverified:** Actual Android screen reader, real GitHub private-token use, PR API behavior under live organization permissions/rate limits, 200% system font scale, full historical merge coverage and owner visual acceptance. The four-page/80-closed-PR cap is an intentional **sample**, not the full project timeline.
