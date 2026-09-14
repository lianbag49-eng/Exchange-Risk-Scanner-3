#!/usr/bin/env bash
set -euo pipefail
cd android-app
trap 'mkdir -p screenshots; adb pull /sdcard/Android/data/com.example.riskscanner/files/. screenshots/ || true' EXIT
gradle --no-daemon :app:connectedDebugAndroidTest
