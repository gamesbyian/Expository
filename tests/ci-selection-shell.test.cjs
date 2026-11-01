'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const yaml = fs.readFileSync(path.join(root,'.github/workflows/transfer-native.yml'),'utf8');
const marker = '      - name: Verify exact PR head, complete file inventory and select suites\n';
const segment = yaml.slice(yaml.indexOf(marker)).split('\n\n  planner:')[0];
assert.ok(yaml.includes(marker));
const raw = segment.split('        run: |\n')[1];
assert.ok(raw);
const script = raw.split('\n').map(line => line.startsWith('          ') ? line.slice(10) : line).join('\n');
const HEAD = 'a'.repeat(40), BASE = 'b'.repeat(40);
const shim = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2).join(' ');
if (process.env.FAKE_FAIL === '1') process.exit(22);
if (args.includes('/files?')) {
  const files = JSON.parse(process.env.FAKE_FILES);
  process.stdout.write(files.map(x => JSON.stringify(x)).join('\n') + '\n');
  process.exit(0);
}
const file = process.env.FAKE_COUNTER;
const call = fs.existsSync(file) ? Number(fs.readFileSync(file,'utf8')) : 0;
fs.writeFileSync(file,String(call+1));
const values = call === 0 ?
  [process.env.FAKE_FIRST_HEAD,process.env.FAKE_FIRST_BASE,process.env.FAKE_COUNT] :
  [process.env.FAKE_LAST_HEAD,process.env.FAKE_LAST_BASE];
process.stdout.write(values.join('\t') + '\n');
`;

function execute(patch = {}) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(),'ci-366-'));
  try {
    const bin = path.join(tmp,'bin');
    fs.mkdirSync(bin);
    fs.writeFileSync(path.join(bin,'gh'),shim,{mode:0o755});
    const output = path.join(tmp,'output'), summary = path.join(tmp,'summary');
    const env = {
      ...process.env, PATH:bin + path.delimiter + process.env.PATH,
      GH_TOKEN:'synthetic', REPO:'fixture/repo', NUMBER:'17',
      EVENT_HEAD:HEAD, EVENT_BASE:BASE,
      GITHUB_OUTPUT:output, GITHUB_STEP_SUMMARY:summary, RUNNER_TEMP:tmp,
      FAKE_COUNTER:path.join(tmp,'counter'), FAKE_COUNT:'1',
      FAKE_FIRST_HEAD:HEAD, FAKE_FIRST_BASE:BASE,
      FAKE_LAST_HEAD:HEAD, FAKE_LAST_BASE:BASE,
      FAKE_FILES:JSON.stringify([{filename:'docs/quality/readme.md',status:'modified'}]),
      ...patch
    };
    const result = spawnSync('bash',['-c',script],{env,cwd:root,encoding:'utf8',timeout:8000});
    return {
      code:result.status,
      log:result.stdout + result.stderr,
      outputs:fs.existsSync(output)?fs.readFileSync(output,'utf8'):'',
      summary:fs.existsSync(summary)?fs.readFileSync(summary,'utf8'):''
    };
  } finally {fs.rmSync(tmp,{recursive:true,force:true});}
}
test('complete docs-only inventory avoids both Gradle lanes',()=>{
  const r=execute();
  assert.equal(r.code,0,r.log);
  assert.match(r.outputs,/^jvm=false$/m);
  assert.match(r.outputs,/^android=false$/m);
});
test('renamed native app file retains Android impact',()=>{
  const files=[{filename:'docs/moved.txt',previous_filename:'android-transfer/app/source.txt',status:'renamed'}];
  const r=execute({FAKE_FILES:JSON.stringify(files)});
  assert.equal(r.code,0,r.log);
  assert.match(r.outputs,/^jvm=false$/m);
  assert.match(r.outputs,/^android=true$/m);
});
test('head and base mismatch before selection are errors',()=>{
  for(const patch of [{FAKE_FIRST_HEAD:'c'.repeat(40)},{FAKE_FIRST_BASE:'d'.repeat(40)}]){
    const r=execute(patch);
    assert.notEqual(r.code,0);
    assert.match(r.log,/Stale PR head\/base/);
  }
});
test('head and base moving during selection are errors',()=>{
  for(const patch of [{FAKE_LAST_HEAD:'c'.repeat(40)},{FAKE_LAST_BASE:'d'.repeat(40)}]){
    const r=execute(patch);
    assert.notEqual(r.code,0);
    assert.match(r.log,/moved during selection/);
  }
});
test('truncated or over-3000 inventories select both native lanes',()=>{
  for(const count of ['2','3001']){
    const r=execute({FAKE_COUNT:count});
    assert.equal(r.code,0,r.log);
    assert.match(r.outputs,/^jvm=true$/m);
    assert.match(r.outputs,/^android=true$/m);
    assert.match(r.summary,/Incomplete PR metadata/);
  }
});
test('metadata API failure or invalid count never emits a green skip',()=>{
  for(const patch of [{FAKE_FAIL:'1'},{FAKE_COUNT:'0'},{FAKE_COUNT:'invalid'}]){
    assert.notEqual(execute(patch).code,0);
  }
});
