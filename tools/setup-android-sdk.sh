#!/usr/bin/env bash
# Installs a project-local Android SDK into .android-sdk/ (gitignored) and writes local.properties.
# Re-runnable. Gradle downloads any further SDK packages it needs into the same folder.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="$PWD/.android-sdk"
CLT=commandlinetools-linux-16111833_latest.zip

if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  tmp=$(mktemp -d)
  curl -sSL -o "$tmp/$CLT" "https://dl.google.com/android/repository/$CLT"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/$CLT" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
echo "sdk.dir=$SDK" > local.properties
echo "Android SDK ready at $SDK"
