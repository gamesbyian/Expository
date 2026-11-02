'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workflows = path.resolve(__dirname, '..', '.github', 'workflows');
const read = (name) => fs.readFileSync(path.join(workflows, name), 'utf8');
const names = fs.readdirSync(workflows).filter((name) => /\.ya?ml$/.test(name));

test('exactly one issue_comment dispatch, preserving opt-in command entry point', () => {
  const listeners = names.filter((name) => /^  issue_comment:\s*$/m.test(read(name)));
  assert.deepEqual(listeners, ['transfer-comment-commands.yml']);
});

test('comment router delegates only recognized commands to reviewed-main workflows', () => {
  const router = read('transfer-comment-commands.yml');
  assert.match(router, /github\.event\.comment\.user\.login == 'gamesbyian'/);
  assert.match(router, /github\.event\.issue\.pull_request != null/);
  const expected = [
    'transfer-native.yml',
    'transfer-emulator-ui.yml',
    'transfer-pr-apk.yml',
    'transfer-pr-emulator.yml',
    'transfer-live-smoke.yml',
  ];
  for (const name of expected) {
    assert.ok(router.includes('uses: ./.github/workflows/' + name), name + ' must be delegated');
    assert.match(read(name), /^  workflow_call:\s*$/m, name + ' must be callable');
    assert.doesNotMatch(read(name), /^  issue_comment:\s*$/m);
  }
  assert.match(router, /    secrets: inherit/);
  assert.doesNotMatch(router, /actions\/checkout|run:|secrets\.[A-Z_]/);
});

test('no broad comment prefix opens execution or secrets access', () => {
  const router = read('transfer-comment-commands.yml');
  assert.doesNotMatch(router, /startsWith\(github\.event\.comment\.body, '\/transfer-native '/);
  assert.doesNotMatch(router, /startsWith\(github\.event\.comment\.body, '\/transfer-smoke '/);
  assert.match(router, /startsWith\(github\.event\.comment\.body, '\/transfer-native emulator-pr '/);
  const external = read('transfer-live-smoke.yml');
  assert.match(external, /github\.event_name == 'workflow_dispatch'/);
  assert.match(external, /github\.event\.comment\.user\.login == 'gamesbyian'/);
  assert.match(external, /ref: main/);
});

test('native PR gates, emulator jobs and manual workflows survive delegation', () => {
  assert.match(read('transfer-native.yml'), /^  pull_request:\s*$/m);
  assert.match(read('transfer-native.yml'), /^  workflow_dispatch:\s*$/m);
  assert.match(read('transfer-live-smoke.yml'), /^  workflow_dispatch:\s*$/m);
  assert.match(read('transfer-sftp-android-loopback.yml'), /^  pull_request:\s*$/m);
  assert.match(read('transfer-sftp-relay-acceptance.yml'), /^  pull_request:\s*$/m);
});

test('automatic expensive suites cancel superseded PRs but not manual runs', () => {
  for (const name of [
    'transfer-sftp-android-loopback.yml',
    'transfer-sftp-relay-acceptance.yml',
    'transfer-plain-ftp-loopback.yml',
    'transfer-native.yml',
    'validate.yml',
  ]) {
    const workflow = read(name);
    assert.match(workflow, /^concurrency:\s*$/m, name);
    assert.match(workflow, /cancel-in-progress: \${{ github\.event_name == 'pull_request' }}/, name);
    assert.match(workflow, /github\.event\.pull_request\.number \|\| github\.run_id/, name);
  }
  assert.match(read('transfer-plain-ftp-loopback.yml'), /^  workflow_dispatch:\s*$/m);
  assert.match(read('validate.yml'), /^  workflow_dispatch:\s*$/m);
});

test('all PRs reach a fail-closed native selector before the expensive lanes', () => {
  const native = read('transfer-native.yml');
  assert.match(native, /^  pull_request:\s*$/m);
  assert.match(native, /^  merge_group:\s*$/m);
  assert.doesNotMatch(native, /^\s+paths:\s*$/m, 'native prefilter would hide rename origins');
  assert.match(native, /^  select:\s*$/m);
  assert.match(native, /Native change-selection integrity/);
  assert.match(native, /github\.event\.pull_request\.base\.sha/);
  assert.match(native, /github\.event\.pull_request\.head\.sha/);
  assert.match(native, /if \(\( total > 3000 \|\| observed != total \)\)/);
  assert.match(native, /node scripts\/ci-selection\.cjs/);
  assert.match(native, /newest.*EVENT_HEAD/);
  assert.match(native, /Merge queue requires both native lanes/);
  assert.equal((native.match(/^\s+needs: select\s*$/gm) || []).length, 2);
  assert.match(native, /needs\.select\.outputs\.jvm == 'true'/);
  assert.match(native, /needs\.select\.outputs\.android == 'true'/);
  assert.match(native, /gh api --paginate/);
  assert.match(native, /previous_filename/);
  assert.match(native, /github\.event_name == 'issue_comment' && 'main' \|\| github\.ref/, 'manual workflow remains reviewed-main');
});

test('merge-queue web and native verification do not depend on PR-only metadata', () => {
  const native = read('transfer-native.yml');
  const web = read('validate.yml');
  assert.match(web, /^  merge_group:\s*$/m);
  assert.match(native, /github\.event_name == 'merge_group'/);
  assert.match(native, /echo "jvm=true"/);
  assert.match(native, /echo "android=true"/);
  assert.match(web, /github\.event\.pull_request\.head\.sha \|\| github\.sha/);
});

test('required native check contexts fail on selector failure, without unnecessary Gradle work', () => {
  const workflow = read('transfer-native.yml');
  for (const id of ['planner', 'android-pr-compile']) {
    const start = workflow.indexOf('  ' + id + ':');
    assert.ok(start >= 0);
    const nextId = id === 'planner' ? 'android-pr-compile' : 'android-debug';
    const end = workflow.indexOf('\n  ' + nextId + ':', start + 3);
    assert.ok(end > start);
    const portion = workflow.slice(start, end);
    assert.match(portion, /needs: select/);
    assert.match(portion, /!cancelled\(\)/, id + ' must execute when selector fails');
    assert.match(portion, /Enforce native selection integrity before completing required check/);
    assert.match(portion, /SELECT_RESULT: \${{ needs\.select\.result }}/);
    assert.match(portion, /SELECT_JVM: \${{ needs\.select\.outputs\.jvm }}/);
    assert.match(portion, /SELECT_ANDROID: \${{ needs\.select\.outputs\.android }}/);
    assert.match(portion, /Report lane non-applicability explicitly/);
  }
  assert.equal((workflow.match(/Enforce native selection integrity before completing required check/g) || []).length, 2);
  assert.equal((workflow.match(/Native selection failed; required check cannot be skipped/g) || []).length, 2);
});


test('isolated Single real-picker E2 methods stay behind exact owner-only allowlist', () => {
  const workflow = read('transfer-pr-emulator.yml');
  assert.match(workflow, /github\\.event\\.comment\\.user\\.login == 'gamesbyian'/);
  assert.match(workflow, /Fork or ambiguous PR source forbidden/);
  for (const method of [
    'LocalPickerPositiveCopyTest#selectAndSaveNewDocumentWithByteVerifiedReadback',
    'LocalPickerPositiveCopyTest#incomingShareDuringSaveAsCannotSwitchTheSource',
  ]) {
    assert.ok(workflow.includes(method + ') ;;'), 'missing scoped gate: ' + method);
  }
  assert.match(workflow, /\*\) echo "Unsupported test selection"; exit 2/);
  assert.match(workflow, /TARGET_TEST_CLASS=dev\\.expository\\.transfer\\.%s/);
});
