# CI failure intelligence: implemented scope and limitations

The primary fast web test gate and native JVM/Android compile gates now retain original exit codes through \`set -o pipefail\` and generate a compact failure-only \`expository.ci.failure.v1\` JSON artifact plus job summary. Native and web logs remain on the runner; raw output is **not uploaded**. The parser extracts only allowlisted task names, Kotlin/Java/Node source *basenames* and bounded synthetic Node test labels. It never repeats free-form exception messages, URLs from test content, FTP server responses or credential material.

Each digest identifies source SHA, workflow run, job, event, observed execution outcome, conservative failure class, safe first indicator when available, log link and exact local reproduction. Where the first failing step is setup or the log format is unknown, the digest explicitly marks its classification \`unclassified\`, rather than fabricating a test failure or swallowing the original exit status. The diagnostic post-step does not use secrets or a privileged event. Artifacts retain five days.

Current boundary: these are run-centric diagnostics, not full JUnit consolidation or per-test expected/actual reports; PR-head scripts can be missing if checkout itself fails. Opt-in emulator integration already exposes sanitized testcase/file-line data and synthetic XML for five days. Broader failure digest coverage and run-level metrics must follow measured needs. Do not promote a safe digest into proof that all failures are automatically diagnosed.

Negative controls in \`tests/ci-failure-digest.test.cjs\` cover compile/test evidence, setup failure, provenance and attempted credential/private-name contamination. Successful runs do **not** upload extra diagnostic artifacts.
