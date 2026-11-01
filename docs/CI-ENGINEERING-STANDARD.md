# CI engineering standard: efficient, trustworthy verification

**Status:** Normative engineering standard for this repository. **Purpose:** govern future CI audits, modifications and ongoing verification. Existing AGENTS instructions, current work queue, release safety contracts and observed evidence retain their authority. This is a design and operating requirement, **not** a claim that its target state has already been implemented.

## Objective

Maximize reliable, actionable defect-detection evidence per unit of elapsed time, aggregate runner time, money, compute and agent attention. A workflow dispatch MUST have a specific necessary verification purpose. No test, dependency setup, compile, matrix job or artifact upload should be duplicated without measured justification. Optimize separately for (1) first actionable failure, (2) safe merge decision latency, (3) aggregate compute/cost and (4) diagnostic discoverability. Saving time must not conceal failures, weaken trust boundaries or reduce high-value coverage.

## Repository-specific verification map

Distinct web Pages explorer and android-transfer application. Route browser/Node/API/visual and Android Kotlin/FTPS/SFTP/SAF/protocol tests independently unless shared files or risk demand both. Preserve read-only browser operations and private token memory boundaries, and safe authorized filesystem mutations in Android Transfer. Prefer mocked APIs for focused tests, separate live integration/protected server evidence.

## Mandatory audit and baseline

Inspect live main, active PRs/issues, repository instructions, **all** GitHub Actions YAML, reusable/composite actions, job conditions, matrices, concurrency, runners, cron/manual/push/PR/merge-queue triggers, dependencies, timeouts, caches, artifacts, permissions, protected credentials, build scripts and actual test suites. Review recent and historical successful, failed, cancelled, skipped and retried runs, including fixes that were later reversed. Establish the event-to-evidence graph: for each dispatch, state event/ref, change type, required result, exact commit, tests, dependencies, duration, runner minutes, reusable results, risk, and downstream consumers. Distinguish observed numbers from speculation; do not claim speedups without a comparison baseline.

## Dispatch intelligence and risk-aware coverage

Classify changed paths by subsystem **and transitive impact**. Use four verification tiers: minimal deterministic preflight; focused changed-subsystem compilation/tests; cross-boundary integration/security/regression checks; full assurance on release, high-risk, ambiguous impact, relevant scheduled audits or explicit request. Documentation-only changes must not spawn unrelated heavy native/emulator/browser-visual jobs. Shared build configuration, scripts, selectors, workflow files, dependency versions, renamed files or unknown impact require conservative fallback. Multi-subsystem changes get the union of required checks. Preserve stable required check contexts, branch protection and merge queue compatibility: skipped or filtered checks cannot leave ambiguous/pending gates or impersonate successful execution. Assert dispatch behavior with offline tests for every representative class and failure case.

Inventory tests by defect class caught, historical failure yield, flake rate, cost, platform, and trust/integration tier. Remove genuinely redundant suites only when equivalent or stronger independent coverage demonstrably remains. Repair weak assertions, needless mocks and expensive fixtures. Prioritize meaningful positive, negative, concurrent, interruption and recovery behavior. Never claim a mocked/API-only result proves a live server, installed device, image quality or actual user journey.

## DAG, parallelism and reusable computation

Measure critical paths and unnecessary serial dependencies. Tune fan-out/fan-in, matrix size, test sharding, toolchain and dependency setup, language compiler workers, native builds where applicable, CPU/memory/I/O saturation, cache performance, artifact transport and queue latency for **actual** runner resources. Parallelism is justified by lower wall-clock duration without unacceptable total runner waste or instability; more processors/jobs are not inherently faster.

Avoid repeated checkout, dependency download, compilation, fixture generation, static checks, packaging and visual setup. Use reusable workflows/composite actions for common instructions, immutable artifacts for safe result reuse and content-addressed caches with correct toolchain/configuration/dependency invalidation. Do not mistake shared YAML for shared computational output. Evaluate cache/artifact upload/download costs against recomputation. Keep artifacts and cache from untrusted forks away from privileged/release paths.

## Agent-readable failure intelligence

Each significant run SHOULD produce a compact GitHub job summary. Every failure MUST, where technically possible, expose a versioned machine-readable failure digest (JSON, plus JUnit XML/SARIF when appropriate) with run/job/step links, exact ref/SHA/environment, outcome/executed/skipped status, earliest meaningful error, subsystem, failed test/task, file/line, expected/actual, bounded relevant excerpt, link to full logs/artifacts, reproduction command, and next useful debugging action. Classify workflow/harness, setup, dependencies, compile/link, runner/emulator/browser, real assertion, external integration, timeout/cancellation or unknown. Distinguish **observed**, **inferred**, **unclassified**; a teardown/reporting failure must not obscure the primary error. Handle failures before tests start. Redact secrets and private inputs. A fresh agent must be able to determine what to fix in seconds.

## Correctness, security and flakes

Review least-privilege GITHUB_TOKEN and third-party actions, protected events, injected PR inputs, dependency/supply-chain pins, artifact provenance, secret handling, cache poisoning, cancellation groups, reproducibility and release safeguards. Cancellation may remove only superseded redundant evidence, never another branch's distinct obligations. Track first-attempt failures separately from retry eventual success. Bounded retries need a specific justification and visible original evidence; no blanket continue-on-error, false-green harnesses, silent quarantine, or weakening gates to accelerate merges. Investigate historical botched optimizations, noisy workflows, flaky tests, misleading test proxies, environment boot failures and missing coverage.

## Instrumentation and self-tests

CI selection logic is production software: add deterministic offline controls covering docs-only, feature-code, shared-code, build config, CI/YAML, dependency, multiple subsystems, path renames, ambiguous impact, forks, releases, merge queues, stale heads, cancellation, cache misses and infrastructure failure. Reproduce representative past regressions to test selector false negatives.

Track dispatch count, unnecessary fan-out, runner minutes, queue time, first actionable failure, merge latency, critical-path steps, cache hit rate, first-pass flakiness, test/shard duration, artifact overhead, diagnostic completeness and high-risk coverage. Compare warm/cold runs and representative changes. Keep metrics light; avoid a large bespoke CI platform when standard GitHub Actions and existing tools suffice.

## Execution mandate and priority

This is an **implementation** assignment, not just an audit. Investigate, rank by expected value, change topology and test implementation, verify exact head and trust boundaries, integrate coherent PRs safely, and document measured results. While long CI runs execute, pursue independent improvements. Reconcile existing related PRs/issues before duplicating work. Follow existing repository documentation conventions and one active queue; no competing task board.

Priority: **P0** real correctness, test-selection safety and security; **P1** stop unnecessary workflow dispatches; **P2** reduce measured critical path and total compute through reuse/parallelism; **P3** machine/human failure diagnostics; **P4** prevent recurring historical failures and flakes; **P5** maintain lightweight performance telemetry.

A completed optimization must explain the old redundant action/failure; exact commit and impacted events; required-check semantics; new and retained tests; negative controls; measured costs and wall-clock outcomes; risks/rollback; PR/merge/evidence links. Distinguish merged, pending, verified, hypothesized and blocked. Never silently relax releases or call unexecuted checks successful. Every workflow must retain an evidence-based reason to exist.
