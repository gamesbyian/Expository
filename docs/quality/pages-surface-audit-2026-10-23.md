# Public Pages surface and byte-parity audit · 2026-10-23

**Evidence class:** live unauthenticated HTTP checks from a GitHub-hosted Linux runner, not synthetic browser fixtures. No personal tokens, authenticated repositories or user-private paths were used.

- Run: https://github.com/gamesbyian/Expository/actions/runs/40045003918
- Target: `https://gamesbyian.github.io/Expository/`
- Tested canonical checkout (pull request): source `index.html` has the same bytes as main, since this PR changes only audit tools/docs
- Observed canonical and deployed SHA-256: `ba06943e2b151797d52010312e2b73eb8cf11be6b96f392ce0a3558569d39e4a`
- Result: **PASS**, exact byte parity for `index.html` under an explicit cache-busting audit URL.

## Public path inventory

All probes were unauthenticated HEAD requests to explicitly known paths on the same GitHub Pages host.

| Public Pages URL path | HTTP | Interpretation |
|---|---:|---|
| `AGENTS.md` | 200 | Public repository agent guide is also served through Pages |
| `README.md` | 200 | Public repository readme is also served through Pages |
| `docs/WORK-QUEUE.md` | 200 | Public repository work queue is also served through Pages |
| `tests/browser-smoke.cjs` | 200 | Public browser fixture/test script is also served through Pages |
| `.github/workflows/validate.yml` | 404 | Not served by Pages in this probe |
| `.expository-managed` | 404 | Retired private publisher marker absent from Pages |

These paths are **public GitHub files already**; their availability through Pages is not evidence that any private user data was exposed. The site is therefore functioning with main-root publication rather than a separately isolated app-only artifact. This does not prove every path on the site has been inventoried.

## Recommendation and limitations

Leave the deployed site untouched for now. If an app-only Pages artifact is desired, first prepare a separate allowlisted build containing only the reviewed app shell and requisite static assets, verify identical behavior and published content, then migrate the Pages source with a documented rollback procedure. This audit neither changes Pages settings nor installs credentials or server infrastructure.

**Remaining gates:** production parity over time, public Pages hosting/source migration, actual Pixel 6 Pro interactions, real authorized private GitHub API traffic, broader public-path enumeration and privacy review for any future PWA/service worker. Do not treat the existence of an audit command as proof of those.
