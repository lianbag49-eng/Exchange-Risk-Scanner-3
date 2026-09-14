#!/usr/bin/env bash
set -euo pipefail
cd android-app
trap 'mkdir -p screenshots; adb pull /sdcard/Download/ers-ui/. screenshots/ || true' EXIT
gradle --no-daemon :app:connectedDebugAndroidTest
