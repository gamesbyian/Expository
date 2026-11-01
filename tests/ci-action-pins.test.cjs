'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const test = require('node:test');

const workflowDir = path.join(__dirname, '..', '.github', 'workflows');
const workflows = fs.readdirSync(workflowDir).filter(f => /\.ya?ml$/.test(f));

test('all third-party GitHub Actions refs use immutable 40-character commit IDs', () => {
  let seen = 0;
  for (const name of workflows) {
    const source = fs.readFileSync(path.join(workflowDir,name), 'utf8');
    for (const line of source.split(/\r?\n/)) {
      const match = line.match(/^\s*-?\s*uses:\s+([^\s#]+)/);
      if (!match || match[1].startsWith('./')) continue;
      const ref = match[1];
      assert.match(ref, /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_./-]+@[0-9a-f]{40}$/, name + ': ' + ref);
      seen++;
    }
  }
  assert.ok(seen >= 30, 'expected full inventory of third-party steps');
});

test('privileged and secret-bearing workflows are not made automatic', () => {
  const read = name => fs.readFileSync(path.join(workflowDir,name),'utf8');
  assert.doesNotMatch(read('transfer-live-smoke.yml'), /^  pull_request:\s*$/m);
  assert.doesNotMatch(read('transfer-live-smoke.yml'), /^  push:\s*$/m);
  assert.match(read('transfer-live-smoke.yml'), /^  workflow_call:\s*$/m);
  assert.match(read('transfer-live-smoke.yml'), /^  workflow_dispatch:\s*$/m);
  assert.match(read('transfer-comment-commands.yml'), /github\.event\.comment\.user\.login == 'gamesbyian'/);
  assert.match(read('transfer-comment-commands.yml'), /    secrets: inherit/);
});
