'use strict';

// Focused PR native CI selection. No network, shell expansion or environment secrets.
const fs = require('node:fs');

function classify(files) {
  if (!Array.isArray(files) || files.length === 0) {
    throw Error('Missing PR file metadata: refuse to produce a green selection');
  }
  let jvm = false;
  let android = false;
  const reasons = new Set();
  for (const change of files) {
    if (!change || typeof change.filename !== 'string' || !change.filename) {
      throw Error('Invalid PR filename metadata');
    }
    if (!['added', 'modified', 'removed', 'renamed', 'copied', 'changed'].includes(change.status)) {
      // Unknown status: include all. Metadata must never silently remove checks.
      jvm = android = true;
      reasons.add('unknown change status');
    }
    const names = [change.filename];
    if (change.status === 'renamed') {
      if (typeof change.previous_filename !== 'string' || !change.previous_filename) {
        jvm = android = true;
        reasons.add('rename without old path');
      } else {
        names.push(change.previous_filename);
      }
    }
    for (const filename of names) {
      // CI changes must prove both lanes. Even a renamed selector cannot self-exempt.
      if (/^\.github\/workflows\/[^/]+\.ya?ml$/.test(filename) ||
          /^\.github\/actions\//.test(filename) ||
          /^scripts\/ci-[^/]+\.cjs$/.test(filename) ||
          /^tests\/ci-[^/]+\.test\.cjs$/.test(filename)) {
        jvm = android = true;
        reasons.add('CI contract');
      } else if (filename.startsWith('android-transfer/')) {
        if (/\.(md|markdown)$/i.test(filename)) {
          reasons.add('native documentation');
        } else if (filename.startsWith('android-transfer/app/')) {
          android = true;
          reasons.add('Android app');
        } else if (/^android-transfer\/(core|adapter|testing)\//.test(filename)) {
          // All shared Kotlin modules are linked into the Android app.
          jvm = android = true;
          reasons.add('shared Kotlin or test fixture');
        } else {
          // Unknown native files, Gradle, plugins, wrapper, and dependencies:
          // never infer harmlessness from an unfamiliar path.
          jvm = android = true;
          reasons.add('native build or unknown impact');
        }
      }
    }
  }
  return {jvm, android, reasons: [...reasons].sort()};
}

if (require.main === module) {
  try {
    if (!process.argv[2]) throw Error('Expected JSONL file metadata path');
    const raw = fs.readFileSync(process.argv[2], 'utf8');
    const files = raw.split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
    const result = classify(files);
    const output = 'jvm=' + result.jvm + '\nandroid=' + result.android + '\n';
    if (!process.env.GITHUB_OUTPUT) throw Error('Missing GITHUB_OUTPUT; refusing implicit green gate');
    fs.appendFileSync(process.env.GITHUB_OUTPUT, output);
    console.log('Native selection: ' + JSON.stringify(result));
  } catch (error) {
    console.error('CI_SELECTOR_ERROR: ' + String(error.message || error).slice(0, 300));
    process.exitCode = 2;
  }
}

module.exports = {classify};
