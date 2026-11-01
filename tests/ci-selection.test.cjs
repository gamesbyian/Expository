'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {classify} = require('../scripts/ci-selection.cjs');
const f = (filename, status = 'modified', previous_filename) => ({filename, status, previous_filename});
const verify = (name, files, jvm, android) => test(name, () => {
  const outcome = classify(files);
  assert.equal(outcome.jvm, jvm);
  assert.equal(outcome.android, android);
  assert.ok(Array.isArray(outcome.reasons));
});

verify('documentation-only changes do not schedule Gradle', [f('android-transfer/AGENTS.md'), f('docs/CI-ENGINEERING-STANDARD.md')], false, false);
verify('native app UI code gets Android compile/lint without unrelated JVM work', [f('android-transfer/app/src/main/java/Screen.kt')], false, true);
verify('pure Kotlin planner exercises JVM and Android downstream build', [f('android-transfer/core/planner/src/main/kotlin/Plan.kt')], true, true);
verify('FTPS / SFTP adapter changes exercise both risk boundaries', [f('android-transfer/adapter/sftp/src/main/kotlin/Foo.kt')], true, true);
verify('test fixture changes exercise both boundaries', [f('android-transfer/testing/fake-endpoints/build.gradle.kts')], true, true);
verify('Gradle build config and unknown native file fail conservatively', [f('android-transfer/gradle/libs.versions.toml'), f('android-transfer/NEW_BINARY')], true, true);
verify('workflow and selector changes cannot self-exempt', [f('.github/workflows/transfer-native.yml'), f('scripts/ci-selection.cjs')], true, true);
verify('cross-subsystem PR selects union of applicable native work', [f('index.html'), f('android-transfer/app/src/main/java/X.kt'), f('android-transfer/adapter/ftps/src/main/kotlin/Y.kt')], true, true);
verify('renamed Kotlin out of an adapter still triggers both', [f('docs/moved.md', 'renamed', 'android-transfer/adapter/sftp/src/main/kotlin/Source.kt')], true, true);
verify('renamed docs to app Kotlin triggers Android', [f('android-transfer/app/Source.kt', 'renamed', 'android-transfer/README.md')], false, true);
verify('native README and root Pages-only PR do not run Android', [f('android-transfer/WORK-QUEUE.md'), f('index.html')], false, false);
verify('unknown status cannot suppress required checks', [f('android-transfer/README.md', 'unexpected')], true, true);
verify('rename missing old filename fails conservative', [f('docs/new.md', 'renamed')], true, true);
test('missing or corrupt metadata is an explicit error, not a silent skip', () => {
  assert.throws(() => classify([]));
  assert.throws(() => classify([{status: 'modified'}]));
  assert.throws(() => classify(null));
});

verify('native code renamed outside native tree still demands complete downstream validation', [f('docs/moved-logic.kt', 'renamed', 'android-transfer/core/planner/src/main/kotlin/Logic.kt')], true, true);
verify('app file renamed into web tree still requires Android', [f('web/removed-activity.txt', 'renamed', 'android-transfer/app/src/main/java/Activity.kt')], false, true);
verify('shared workflow, action and diagnostic edits conservatively select both', [
  f('.github/workflows/validate.yml'), f('.github/actions/common/action.yml'),
  f('scripts/ci-failure-digest.cjs'), f('tests/ci-orchestration.test.cjs')
], true, true);
verify('metadata with only unknown root/Pages paths does not invent native execution', [f('index.html'), f('docs/quality/README.md')], false, false);
