'use strict';

// Logs may hold fixture names and third-party diagnostics. Only permit known
// build tasks, source basename:line, and synthetic test labels into artifacts.
const fs = require('node:fs');
const path = require('node:path');

const bounded = (s, n = 160) => String(s || '').slice(0, n);
const safeLabel = (s, n) => bounded(s, n).replace(/[^A-Za-z0-9_ .:#()\/-]/g, '_');

function analyze(log, kind) {
  const lines = log.split(/\r?\n/).slice(0, 40000);
  let task = null, source = null, test = null;
  for (const line of lines) {
    if (!task) {
      const match = line.match(/^> Task (:[A-Za-z0-9:_-]+) FAILED$/);
      if (match) task = match[1];
    }
    if (!source) {
      const match = line.match(/(?:^e: |^error: )(?:file:\/\/)?[^\s:]*\/([A-Za-z0-9_.-]+\.(?:kt|java|js|cjs)):(\d{1,6})/);
      if (match) source = match[1] + ':' + match[2];
    }
    if (!test && kind === 'web') {
      const match = line.match(/^\s*not ok \d+ - (.{1,180})$/);
      if (match) test = safeLabel(match[1], 120);
    }
  }
  const classification = task
    ? (/compile|kotlin|javac/i.test(task) ? 'compile' :
       /test|check/i.test(task) ? 'test-assertion-or-harness' :
       /lint/i.test(task) ? 'static-analysis' : 'build-or-dependency')
    : test ? 'test-assertion-or-harness' : 'unclassified';
  return {
    classification,
    observedTask: task,
    sourceLocation: source,
    failedTest: test,
    firstMeaningfulError: task ? 'Gradle task ' + task + ' failed' :
      test ? 'Node test failed: ' + test : null,
    evidence: task || source || test ? 'observed' : 'unclassified'
  };
}

function build(env, log) {
  const suite = safeLabel(env.CI_SUITE || 'unspecified', 64);
  const repository = safeLabel(env.GITHUB_REPOSITORY || 'unknown/unknown', 120);
  const runId = /^\d+$/.test(env.GITHUB_RUN_ID || '') ? env.GITHUB_RUN_ID : 'unknown';
  const sourceSha = /^[0-9a-f]{40}$/.test(env.CI_TESTED_SHA || '') ? env.CI_TESTED_SHA : null;
  const runUrl = 'https://github.com/' + repository + '/actions/runs/' + runId;
  return {
    schema: 'expository.ci.failure.v1',
    outcome: 'failure',
    suite,
    runUrl,
    job: safeLabel(env.GITHUB_JOB || '', 64),
    event: safeLabel(env.GITHUB_EVENT_NAME || '', 48),
    exactTestedSha: sourceSha,
    selectedCheckOutcome: ['failure','success','skipped','cancelled'].includes(env.CI_CHECK_OUTCOME) ? env.CI_CHECK_OUTCOME : 'unknown',
    ...analyze(log, suite),
    logsUrl: runUrl,
    reproduction: bounded(env.CI_REPRO || 'See workflow source and exact commit', 240),
    nextAction: 'Inspect earliest failing step in the linked run, then reproduce on the recorded source SHA.',
    expected: null,
    actual: null,
    redaction: 'Only validated task names, source basenames and bounded synthetic test labels; raw logs are never uploaded.'
  };
}

if (require.main === module) {
  try {
    const logPath = process.env.CI_LOG_PATH || '';
    let log = '';
    if (logPath && fs.existsSync(logPath) && fs.statSync(logPath).isFile()) {
      const size = fs.statSync(logPath).size;
      // Avoid reading large binary/verbose logs; the first portion contains
      // setup/test headers and early failures. If not, classify as unknown.
      log = fs.readFileSync(logPath).subarray(0, Math.min(size, 1024 * 1024)).toString('utf8');
    }
    const result = build(process.env, log);
    const output = path.join(process.cwd(), 'ci-failure-digest.json');
    fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
    const summary = ['### CI failure digest', '',
      '- Suite: ' + result.suite,
      '- Tested source: ' + (result.exactTestedSha || 'unknown; see run metadata'),
      '- Classification: ' + result.classification + ' (' + result.evidence + ')',
      '- First indicator: ' + (result.firstMeaningfulError || 'no safe diagnostic extracted'),
      '- Full logs: ' + result.logsUrl,
      '- Reproduce: ' + result.reproduction,
      '- Machine artifact: ci-failure-digest.json',
      ''].join('\n');
    if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
    console.log('Wrote redacted failure digest; status remains failed.');
  } catch (error) {
    console.error('CI_DIGEST_ERROR: ' + bounded(error.message || error, 200));
    process.exitCode = 2;
  }
}
module.exports = {analyze, build};
