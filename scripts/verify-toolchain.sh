#!/usr/bin/env bash
set -uo pipefail
FAILED=0
for TOOL in java javac jar javadoc git curl unzip python3 cmake ninja gperf perl make; do
  if command -v "$TOOL" >/dev/null 2>&1; then echo "OK: $TOOL"; else echo "MISSING: $TOOL"; FAILED=1; fi
done
if [[ -n ${ANDROID_HOME:-} && -f "$ANDROID_HOME/platforms/android-36/android.jar" ]]; then
  echo 'OK: Android 36 platform'
else echo 'MISSING: ANDROID_HOME / Android 36 platform'; FAILED=1; fi
exit "$FAILED"
