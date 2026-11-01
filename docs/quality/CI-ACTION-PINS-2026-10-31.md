# CI Actions supply-chain pin register (2026-10-31)

All external `uses:` references are pinned to fully qualified Git commit IDs, not mutable `@v4` major tags. The mapping below was resolved from the public GitHub API for each repository's `refs/tags/v4` on 2026-10-31. `gradle/actions` uses nested annotated, PGP-verified tags: v4 → annotated v4.4.3 → commit. Existing immutable reactivecircus emulator-runner is retained unchanged.

| Action | Immutable commit | Previous ref |
|---|---|---|
| actions/checkout | 11d5960a326750d5838078e36cf38b85af677262 | v4 |
| actions/setup-node | 49933ea5288caeca8642d1e84afbd3f7d6820020 | v4 |
| actions/setup-java | cf277c60eb25467037889841efdb72551f06f6c3 | v4 |
| actions/upload-artifact | ea165f8d65b6e75b540449e92b4886f43607fa02 | v4 |
| gradle/actions/setup-gradle | ed408507eac070d1f99cc633dbcf757c94c7933a | v4.4.3 (nested annotated tag) |
| android-actions/setup-android | be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd | v4 |
| reactivecircus/android-emulator-runner | a421e43855164a8197daf9d8d40fe71c6996bb0d | already pinned |

A literal pin is not a security audit of vendor dependencies. It makes execution reproducible relative to reviewed trees and prevents floating-tag updates. Updates require explicit inventory and repeat validation of applicable manual/automatic paths. No privileges, secrets, events or artifact destinations are introduced.

Offline tests fail if a new external Action is referenced by a floating tag. Workflow-file changes can dispatch resource-heavy protocol or emulator gates, which require exact-head evidence before acceptance. Synthetic fixture and emulator runs do not prove production server or physical device behavior.

## Superseded PR jobs

Before this PR, adjacent commits could leave duplicate expensive Android emulator checks running simultaneously. The prior SFTP Android source-revision runs [41257255897](https://github.com/gamesbyian/Expository/actions/runs/41257255897) and [41257310483](https://github.com/gamesbyian/Expository/actions/runs/41257310483) overlapped even though the first result was already superseded. The revised automatic plaintext FTP, synthetic SFTP relay and Android SFTP emulator workflows now cancel only older runs of **the same PR**; manual invocations have independent `github.run_id` identifiers and are not cancelled. The web validation workflow now uses the same PR-only cancellation rule rather than cancelling deliberate `workflow_dispatch` requests. The exact conditions are asserted in `tests/ci-orchestration.test.cjs`.

This is observed duplicate-run prevention, **not** a claim that total runner minutes or merge latency have improved by a measured percentage. Use Actions job timings on comparable PRs before reporting performance gains.
