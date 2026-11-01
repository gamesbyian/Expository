# CI dispatch consolidation evidence (2026-10-31)

## Observed baseline

At main `d185440a19da8b2b9c4679f9bf2fb1316244b83e`, five separate workflows subscribed to every `issue_comment.created` event: `transfer-native`, `transfer-emulator-ui`, `transfer-live-smoke`, `transfer-pr-apk`, and `transfer-pr-emulator`. For example, the owner comment at 2026-10-31 22:28:30Z produced five independent runs, four skipped and one failed actual emulator test (runs 41248334699, 41248334633, 41248334574, 41248334559, 41248334552). Skipped dispatch records were not executed tests; this observation establishes a five-to-one **top-level dispatch** opportunity, not runner-minute savings or a measured wall-clock speedup.

## Intervention and boundaries

One trusted-main `transfer-comment-commands.yml` handles comment events; exact owner and PR filters fan out only recognized commands through the five original workflows as `workflow_call` targets. Those workflows retain immutable PR-head resolution where applicable, trusted-main jobs for live secrets, existing positive/negative checks, artifacts and manual dispatch. `transfer-native` retains its separate mandatory PR JVM + conditionally triggered Android build/lint behavior. Only `transfer-live-smoke` receives inherited secrets, and its owner job retains its own allowlist and protected configuration checks. No unrecognized or unowned comment starts executable jobs.

GitHub still records one skipped dispatcher for an unrelated issue comment, and selected commands incur a reusable workflow call. This design deliberately preserves the comment-command UX instead of trading it away for fewer run records. Neither the 4x reduction in redundant top-level dispatch **attempts** nor a likely improvement in log discoverability is asserted to be measured compute savings.

## Negative controls and follow-up

`node --test tests/ci-orchestration.test.cjs` enforces the one-listener topology, explicit invocation filters, callable targets, owner guards, and retention of manual and mandatory PR gates. Check successful *and failed* exact-head PR CI, then run one authorized command and one unrelated comment to confirm Actions' reusable-workflow event context. If a delegate fails to receive the original issue_comment payload, roll back this consolidation rather than bypassing ownership or secrets checks. No actual emulator or external/live credentials are introduced by this PR. Track status and real before/after run counts on the PR.
