#!/usr/bin/env bash
# Clean-room project dependency bootstrap. Unlike the existing warm-cache
# validation, downloads the genuine Gradle distribution AND every required
# JVM/Android plugin/dependency from scratch in a disposable user home.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export GRADLE_USER_HOME="$scratch/gradle-home"
mkdir -p "$GRADLE_USER_HOME"
test -z "$(find "$GRADLE_USER_HOME" -mindepth 1 -print -quit)"

# Do not use setup-gradle, Gradle's shared cache, preinstalled Gradle, injected
# credentials, connected endpoints or the user's computer. The checked-in
# wrapper independently validates the official pinned distribution checksum.
(
  cd "$root/android-transfer"
  bash ./gradlew --no-daemon -PwithAndroid=true \
    :core:planner:check :core:engine:check \
    :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin \
    :app:lintDebug
)

# These must be independently populated from this empty user home; a green
# job with accidentally skipped Gradle project resolution is not acceptance.
test -d "$GRADLE_USER_HOME/caches/modules-2/files-2.1"
test -n "$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1" \
  -mindepth 2 -type d -print -quit)"
test -n "$(find "$GRADLE_USER_HOME/wrapper/dists" \
  -name 'gradle-launcher-8.13.jar' -print -quit)"
echo 'PASS: JVM scenarios plus Android app/test compile/lint resolved from a fresh Gradle home.'
