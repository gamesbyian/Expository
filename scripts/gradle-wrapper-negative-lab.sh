#!/usr/bin/env bash
# Deliberate, offline negative: the OFFICIAL repository GradleWrapperMain must
# reject an isolated local ZIP whose declared SHA-256 is wrong.
# This never modifies the production wrapper or Gradle cache, downloads no
# distributions and cannot authorize an application build or release.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/gradle/wrapper" "$scratch/cache"
cp "$repo/android-transfer/gradle/wrapper/gradle-wrapper.jar" \
   "$scratch/gradle/wrapper/gradle-wrapper.jar"

# The fixture must be a valid ZIP to rule out a malformed-archive rejection.
# Its contents are intentionally inert: the checksum check must run before the
# wrapper could try to execute or unpack it as a real Gradle distribution.
zip_uri="$(python3 - "$scratch" <<'PY'
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
archive = root / "synthetic-gradle-distribution.zip"
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
    output.writestr("gradle-8.13/bin/gradle", "offline synthetic fixture; not executable")
print(archive.resolve().as_uri())
PY
)"

cat > "$scratch/gradle/wrapper/gradle-wrapper.properties" <<EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=$zip_uri
distributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

# Execute the real, independently SHA-pinned wrapper JAR with a clean user
# home. A nonzero exit by itself is insufficient: require the checksum-specific
# failure, not an unrelated Java, network, unzip or executable failure.
if GRADLE_USER_HOME="$scratch/cache" java \
    -cp "$scratch/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain --version \
    >"$scratch/wrapper-output.log" 2>&1; then
    echo "ERROR: Gradle wrapper accepted an intentionally false ZIP checksum"
    exit 1
fi

if ! grep -Fq 'Verification of Gradle distribution failed' \
    "$scratch/wrapper-output.log"; then
    echo "ERROR: Gradle wrapper failed for a reason other than ZIP checksum verification"
    sed -n '1,40p' "$scratch/wrapper-output.log"
    exit 1
fi
if ! grep -Fq 'Expected checksum:' "$scratch/wrapper-output.log"; then
    echo "ERROR: missing expected-checksum evidence from the real wrapper"
    exit 1
fi
echo "PASS: real pinned GradleWrapperMain rejected a locally supplied, false SHA-256 checksum."
