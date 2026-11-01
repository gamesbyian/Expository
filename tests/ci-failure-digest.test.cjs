'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const {analyze,build} = require('../scripts/ci-failure-digest.cjs');

test('extracts only safe Gradle task and source basename', () => {
  const log = [
    '> Task :app:compileDebugKotlin FAILED',
    'e: file:///home/runner/work/private-prefix/app/src/main/Foo.kt:52:13: secret-message token',
  ].join('\n');
  const a = analyze(log, 'android');
  assert.equal(a.observedTask, ':app:compileDebugKotlin');
  assert.equal(a.sourceLocation, 'Foo.kt:52');
  assert.equal(a.classification, 'compile');
  assert.doesNotMatch(JSON.stringify(a), /private-prefix|secret-message|token/);
});

test('detects Node test labels while replacing untrusted characters', () => {
  const a = analyze('not ok 2 - synthetic token=<abc> failed', 'web');
  assert.equal(a.classification, 'test-assertion-or-harness');
  assert.ok(!JSON.stringify(a).includes('<abc>'));
});

test('setup failures without logs remain explicitly unclassified', () => {
  const a = build({CI_SUITE: 'android', GITHUB_RUN_ID: '123', GITHUB_REPOSITORY: 'gamesbyian/Expository'}, '');
  assert.equal(a.classification, 'unclassified');
  assert.equal(a.evidence, 'unclassified');
  assert.equal(a.exactTestedSha, null);
  assert.equal(a.runUrl, 'https://github.com/gamesbyian/Expository/actions/runs/123');
});

test('metadata uses exact source SHA and stable versioned schema', () => {
  const sha = '0123456789abcdef0123456789abcdef01234567';
  const a = build({CI_SUITE: 'android-jvm', GITHUB_RUN_ID:'123', GITHUB_REPOSITORY:'gamesbyian/Expository',
    CI_TESTED_SHA:sha, CI_CHECK_OUTCOME:'failure', CI_REPRO:'gradle check --no-daemon'}, '> Task :core:planner:test FAILED');
  assert.equal(a.schema,'expository.ci.failure.v1');
  assert.equal(a.exactTestedSha,sha);
  assert.equal(a.reproduction,'gradle check --no-daemon');
  assert.equal(a.observedTask,':core:planner:test');
});

test('does not surface free-form log lines or auth secrets', () => {
  const log = 'Authorization: Bearer secret-value\nFTP_PASSWORD=some_password\nBuild failed with private filenames\n';
  const a = analyze(log,'android');
  assert.equal(a.firstMeaningfulError,null);
  assert.doesNotMatch(JSON.stringify(a),/secret-value|some_password|private filenames/);
});
