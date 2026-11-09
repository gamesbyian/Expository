'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');

const authoritative = [
  'AGENTS.md',
  'android-transfer/AGENTS.md',
  'android-transfer/WORK-QUEUE.md',
  'docs/WORK-QUEUE.md',
  'docs/android-transfer/CURRENT-CONTRACT.md',
  'docs/android-transfer/EXECUTION-DEPENDENCIES.md',
  'docs/android-transfer/RELEASE-READINESS.md',
  'docs/android-transfer/RELEASE-BLOCKER-CONSOLIDATION-2026-11-03.md',
];

test('every current agent and queue entrypoint points to existing authoritative documents', () => {
  for (const relative of authoritative) {
    const content = read(relative);
    const directory = path.dirname(path.join(root, relative));
    // These are intentional local document references, not external URLs or
    // historical links to GitHub PRs, runs or snapshots.
    const matches = content.matchAll(/\]\(([^)#?]+\.md)(?:#[^)]*)?\)/g);
    for (const match of matches) {
      assert.ok(fs.existsSync(path.resolve(directory, match[1])),
        relative + ' has a broken current local Markdown link: ' + match[1]);
    }
  }
});

test('native release ledger cannot revive bootstrap to-do items as live instructions', () => {
  const ledger = read('docs/android-transfer/RELEASE-READINESS.md');
  assert.doesNotMatch(ledger, /^## Immediate next agent tasks \(ordered\)$/m);
  assert.match(ledger, /^## Historical bootstrap task list .*superseded/m);
  assert.match(ledger, /\]\(\.\.\/\.\.\/android-transfer\/WORK-QUEUE\.md\)/);
});

test('current queues acknowledge distinct native and web authority and historical checkpoints', () => {
  const nativeQueue = read('android-transfer/WORK-QUEUE.md');
  const webQueue = read('docs/WORK-QUEUE.md');
  const dependencies = read('docs/android-transfer/EXECUTION-DEPENDENCIES.md');
  assert.match(nativeQueue, /RELEASE-BLOCKER-CONSOLIDATION-2026-11-03\.md/);
  assert.match(webQueue, /Historical native acceptance checkpoint/);
  assert.match(dependencies, /Later #401 independently accepted Dual recursive real-picker E2/);
  assert.doesNotMatch(dependencies, /^(?!.*Historical).*draft #401.*(?:qualif|blocked)/im);
});
