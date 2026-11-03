'use strict';
// Dependency-free source integrity evidence. The trusted wrapper JAR and distribution
// checksums were independently compared against Gradle's published 8.13 hashes.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.join(__dirname, '..', 'android-transfer');
const wrapper = path.join(root, 'gradle', 'wrapper');
const jarSha256 = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f';
const distSha256 = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78';
function verifyProperties(props) {
  const rows = Object.fromEntries(props.split(/\r?\n/).filter(x => x && !x.startsWith('#')).map(line => {
    const pos = line.indexOf('=');
    assert.ok(pos > 0, 'property has a name and value');
    return [line.slice(0, pos), line.slice(pos + 1)];
  }));
  assert.equal(rows.distributionUrl, 'https\\://services.gradle.org/distributions/gradle-8.13-bin.zip');
  assert.equal(rows.distributionSha256Sum, distSha256);
  assert.equal(rows.distributionBase, 'GRADLE_USER_HOME');
  assert.equal(rows.zipStoreBase, 'GRADLE_USER_HOME');
}
test('Android Gradle wrapper JAR is byte-for-byte official Gradle 8.13', () => {
  const bytes = fs.readFileSync(path.join(wrapper, 'gradle-wrapper.jar'));
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), jarSha256);
  assert.ok(bytes.length > 40000, 'official JAR is present, not a stub');
});
test('Android Gradle wrapper distribution remains HTTPS and checksum pinned', () => {
  verifyProperties(fs.readFileSync(path.join(wrapper, 'gradle-wrapper.properties'), 'utf8').trim());
  assert.throws(() => verifyProperties('distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\ndistributionSha256Sum=' + distSha256), /AssertionError/);
  assert.throws(() => verifyProperties('distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\ndistributionSha256Sum=' + '0'.repeat(64)), /AssertionError/);
});
test('Android wrapper scripts use GradleWrapperMain and no app-specific bootstrap URL', () => {
  for (const script of ['gradlew', 'gradlew.bat']) {
    const body = fs.readFileSync(path.join(root, script), 'utf8');
    assert.match(body, /org\.gradle\.wrapper\.GradleWrapperMain/);
    assert.doesNotMatch(body, /curl|Invoke-WebRequest|powershell -enc/i);
  }
});
