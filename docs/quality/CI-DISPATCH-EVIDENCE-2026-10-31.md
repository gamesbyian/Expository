# CI dispatch consolidation evidence (2026-10-31)

## Observed baseline

At main `d185440a19da8b2b9c4679f9bf2fb1316244b83e`, five separate workflows subscribed to every `issue_comment.created` event: `transfer-native`, `transfer-emulator-ui`, `transfer-live-smoke`, `transfer-pr-apk`, and `transfer-pr-emulator`. For example, the owner comment at 2026-10-31 22:28:30Z produced five independent runs, four skipped and one failed actual emulator test (runs 41248334699, 41248334633, 41248334574, 41248334559, 41248334552). Skipped dispatch records were not executed tests; this observation establishes a five-to-one **top-level dispatch** opportunity, not runner-minute savings or a measured wall-clock speedup.

## Intervention and boundaries

One trusted-main `transfer-comment-commands.yml` handles comment events; exact owner and PR filters fan out only recognized commands through the five original workflows as `workflow_call` targets. Those workflows retain immutable PR-head resolution where applicable, trusted-main jobs for live secrets, existing positive/negative checks, artifacts and manual dispatch. `transfer-native` retains its separate mandatory PR JVM + conditionally triggered Android build/lint behavior. Only `transfer-live-smoke` receives inherited secrets, and its owner job retains its own allowlist and protected configuration checks. No unrecognized or unowned comment starts executable jobs.

GitHub still records one skipped dispatcher for an unrelated issue comment, and selected commands incur a reusable workflow call. This design deliberately preserves the comment-command UX instead of trading it away for fewer run records. Neither the 4x reduction in redundant top-level dispatch **attempts** nor a likely improvement in log discoverability is asserted to be measured compute savings.

## Negative controls and follow-up

`node --test tests/ci-orchestration.test.cjs` enforces the one-listener topology, explicit invocation filters, callable targets, owner guards, and retention of manual and mandatory PR gates. Check successful *and failed* exact-head PR CI, then run one authorized command and one unrelated comment to confirm Actions' reusable-workflow event context. If a delegate fails to receive the original issue_comment payload, roll back this consolidation rather than bypassing ownership or secrets checks. No actual emulator or external/live credentials are introduced by this PR. Track status and real before/after run counts on the PR.

## Post-merge verification and measured dispatch topology

Merged [PR #362](https://github.com/gamesbyian/Expository/pull/362) as `8cb483559489047e71cdabc9c34d4032485532c1`. The ordinary PR-discussion negative control on PR #363 created exactly one `Expository Transfer authorized comment commands` run, [41256671852](https://github.com/gamesbyian/Expository/actions/runs/41256671852), which concluded `skipped` with zero executing jobs. The exact allowlisted `/transfer-native sftp-lab` owner comment created exactly one dispatcher, [41256677079](https://github.com/gamesbyian/Expository/actions/runs/41256677079), where only the delegated synthetic `native / sftp-lab` job ran and it concluded `success`. Original issue_comment permissions and source-SHA trust conditions were inherited in the actual reusable invocation.

**Measured event-level effect for these two representative comments:** 5 old workflow records/comment versus 1 new workflow record/comment, a reduction of 4 top-level records or 80%. This does **not** imply 80% fewer runner minutes. A delegated workflow still displays skipped inner jobs; no separate runner-minute measurement has been made. To monitor over longer periods, count top-level `event=issue_comment` workflow runs and compare with the number of actual authorized commands, without counting skipped jobs as executed coverage.

## Native pre-dispatch false-negative prevention (#366)

The earlier `transfer-native.yml` used `pull_request.paths` filtering. This could silently omit a PR if native source was **renamed out** of `android-transfer/`, even though `scripts/ci-selection.cjs` understood `previous_filename` after dispatch. The new topology guarantees one cheap selection run for **each** PR, with actual Gradle lanes conditional on the union of old and new paths. No duplicate per-job PR metadata API calls or per-job selector checkout remain. The selector runs the **trusted base revision** of its script, refusing to let unreviewed PR changes self-exempt, and verifies the event head against live PR metadata twice. Mismatched head or failed API is an error; GitHub's 3,000 changed-file limit or inventory-size mismatch forces full JVM + Android coverage. `merge_group` is also full-assurance.

Verification contract: inspect the exact-head PR web suite, selector outputs and conditional native jobs, then prove on a harmless staged PR that an actual native-to-docs rename dispatches the selector and selects the appropriate lane. Also prove docs-only changes leave JVM/Android jobs marked skipped, not passed. **No runtime-speed or billing savings are inferred without comparable measured run timings.** A docs-only PR now intentionally creates a cheap native selection record to prevent a correctness omission.
