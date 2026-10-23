# Lean testing and CI policy

Expository has one routine PR validation check: Node's built-in test runner and static checks. It uses no package install, auth token, build server or external scripts. Root `index.html` is the shipped artifact.

- **Quick default:** `node --test tests/expository.test.cjs`, plus static JS syntax and verification that only expected workflow triggers exist. Run for PRs touching the app/tests/CI. Aim for a short, single Linux job.
- **Targeted browser:** `node tests/browser-smoke.cjs` when changing navigation, fetching, search, previews, modals or responsive UI. Uses synthetic public GitHub fixtures and local headless Chrome; neither live private data nor a production acceptance certificate.
- **Optional visual review:** Actions → *Expository Visual Review* → Run workflow on chosen branch/commit, capture viewport screenshot evidence and offline gallery. Never add it as a required check for unrelated changes. Keep captures synthetic and provenance labeled.
- **Manual physical inspection:** actual Pixel 6 Pro Chrome touch and layout, keyboard/screen reader checks, data-protection/network traces. Record owner observations separately. Even checked screenshot atlases do not substitute for handset/human acceptance.

One change should receive the cheapest decisive tests. View significant visual diffs directly. Do not invent pass/fail art thresholds from fixture PNG hashes; use hashes for provenance and visual regression assistance. CI cannot approve visual style by itself. Missing/queued/skipped evidence is **not** a pass. On failure, read first failed step and exact source SHA before retrying.

Pages deploys root `index.html` from public main. No CI token injection or private source sync. Public fixture screenshots may exist in 7-day artifacts but not silently in the live site.
