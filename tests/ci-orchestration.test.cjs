'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workflows = path.resolve(__dirname, '..', '.github', 'workflows');
const read = (name) => fs.readFileSync(path.join(workflows, name), 'utf8');
const names = fs.readdirSync(workflows).filter((name) => /\\.ya?ml$/.test(name));

test('exactly one issue_comment dispatch, preserving opt-in command entry point', () => {
  const listeners = names.filter((name) => /^  issue_comment:\\s*$/m.test(read(name)));
  assert.deepEqual(listeners, ['transfer-comment-commands.yml']);
});

test('comment router delegates to reviewed-main workflows without checked-out PR shell evaluation', () => {
  const router = read('transfer-comment-commands.yml');
  assert.match(router, /github\\.event\\.comment\\.user\\.login == 'gamesbyian'/);
  assert.match(router, /github\\.event\\.issue\\.pull_request != null/);
  const expected = [
    'transfer-native.yml',
    'transfer-emulator-ui.yml',
    'transfer-pr-apk.yml',
    'transfer-pr-emulator.yml',
    'transfer-live-smoke.yml',
  ];
  for (const name of expected) {
    assert.match(router, new RegExp('uses: \\.\\/\\.github\\/workflows\\/' + name.replace(/\\./g, '\\\\.') + '$', 'm'));
    assert.match(read(name), /^  workflow_call:\\s*$/m, name + ' must be callable');
    assert.doesNotMatch(read(name), /^  issue_comment:\\s*$/m);
  }
  assert.match(router, /    secrets: inherit/);
  assert.doesNotMatch(router, /actions\\/checkout|run:|secrets\\.[A-Z_]/);
});

test('no broad comment prefix opens execution or secrets access', () => {
  const router = read('transfer-comment-commands.yml');
  assert.doesNotMatch(router, /startsWith\\(github\\.event\\.comment\\.body, '\/transfer-native '/);
  assert.doesNotMatch(router, /startsWith\\(github\\.event\\.comment\\.body, '\/transfer-smoke '/);
  assert.match(router, /startsWith\\(github\\.event\\.comment\\.body, '\/transfer-native emulator-pr '/);
  const external = read('transfer-live-smoke.yml');
  assert.match(external, /github\\.event_name == 'workflow_dispatch'/);
  assert.match(external, /github\\.event\\.comment\\.user\\.login == 'gamesbyian'/);
  assert.match(external, /ref: main/);
});

test('native auto PR gates and standalone manual workflows survive delegation', () => {
  assert.match(read('transfer-native.yml'), /^  pull_request:\\s*$/m);
  assert.match(read('transfer-native.yml'), /^  workflow_dispatch:\\s*$/m);
  assert.match(read('transfer-live-smoke.yml'), /^  workflow_dispatch:\\s*$/m);
  assert.match(read('transfer-sftp-android-loopback.yml'), /^  pull_request:\\s*$/m);
  assert.match(read('transfer-sftp-relay-acceptance.yml'), /^  pull_request:\\s*$/m);
});
