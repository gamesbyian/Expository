#!/usr/bin/env bash
# Live official Gradle bootstrap using the committed checksum-pinned wrapper.
# No dependency caches, production credentials, Android SDK or app build.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper="$root/android-transfer/gradle/wrapper"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/gradle-home"

expected_jar="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
expected_dist="20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
echo "$expected_jar  $wrapper/gradle-wrapper.jar" | sha256sum --check --status
grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip' \
  "$wrapper/gradle-wrapper.properties"
grep -Fqx "distributionSha256Sum=$expected_dist" \
  "$wrapper/gradle-wrapper.properties"
test -z "$(find "$scratch/gradle-home" -mindepth 1 -print -quit)"

# Unlike an installed gradle/action cache, this must fetch the official ZIP
# and have GradleWrapperMain verify its distributionSha256Sum before running.
(
  cd "$root/android-transfer"
  GRADLE_USER_HOME="$scratch/gradle-home" \
    bash ./gradlew --version --no-daemon > "$scratch/version.txt" 2>&1
)

if ! grep -Fq 'Gradle 8.13' "$scratch/version.txt"; then
  echo 'ERROR: pinned official Gradle 8.13 distribution did not launch'
  sed -n '1,35p' "$scratch/version.txt"
  exit 1
fi

# Gradle's unzip layout must be the actual Gradle binaries, not just a
# synthetic ZIP or the distro JAR. The wrapper may remove its ZIP afterward,
# so check the extracted launcher as well as the success marker if present.
launcher="$(find "$scratch/gradle-home/wrapper/dists" -type f \
  -name 'gradle-launcher-8.13.jar' -print -quit)"
if [[ -z "$launcher" || ! -s "$launcher" ]]; then
  echo 'ERROR: no extracted official Gradle 8.13 launcher in fresh wrapper cache'
  exit 1
fi

# If the downloaded ZIP was retained, additionally recheck its bytes.
while IFS= read -r -d '' archive; do
  echo "$expected_dist  $archive" | sha256sum --check --status || {
    echo 'ERROR: retained Gradle ZIP checksum differs from pinned official SHA256'
    exit 1
  }
done < <(find "$scratch/gradle-home/wrapper/dists" -type f \
    -name 'gradle-8.13-bin.zip' -print0)

echo 'PASS: official Gradle 8.13 bootstrapped from an empty isolated cache with pinned ZIP checksum.'
