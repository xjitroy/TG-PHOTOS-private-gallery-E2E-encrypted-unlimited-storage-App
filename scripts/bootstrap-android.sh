#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SDK_DIR="${ANDROID_HOME:-$PROJECT_DIR/.tools/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$PROJECT_DIR/.tools/android-user}"
mkdir -p "$ANDROID_USER_HOME/cache"
# Prefer Android Studio's SDK manager. Initial tool download is verified against
# Google's HTTPS repository metadata, not an invented checksum.
if command -v sdkmanager >/dev/null 2>&1; then
  SDK_MANAGER=$(command -v sdkmanager)
elif [[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
  SDK_MANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
else
  mkdir -p "$PROJECT_DIR/.tools" "$SDK_DIR/cmdline-tools"
  curl --fail --location --connect-timeout 15 --max-time 60 \
    https://dl.google.com/android/repository/repository2-1.xml -o "$PROJECT_DIR/.tools/android-repository.xml"
  python3 - "$PROJECT_DIR/.tools" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
dest = Path(sys.argv[1])
root = ET.parse(dest / 'android-repository.xml').getroot()
found = []
for package in root.findall('remotePackage'):
    if not package.attrib['path'].startswith('cmdline-tools;'): continue
    for archive in package.findall('./archives/archive'):
        if archive.findtext('host-os') != 'linux': continue
        url = archive.findtext('./complete/url')
        if url != 'commandlinetools-linux-13114758_latest.zip': continue
        found.append((url, archive.findtext('./complete/checksum')))
assert len(found) == 1, 'Pinned Android command-line tools absent from official metadata'
(dest / 'android-tools-download.txt').write_text('\n'.join(found[0]))
PY
  mapfile -t DOWNLOAD < "$PROJECT_DIR/.tools/android-tools-download.txt"
  curl --fail --location --connect-timeout 15 --max-time 300 \
    "https://dl.google.com/android/repository/${DOWNLOAD[0]}" -o "$PROJECT_DIR/.tools/android-tools.zip"
  python3 - "$PROJECT_DIR/.tools/android-tools.zip" "${DOWNLOAD[1]}" <<'PY'
import hashlib, pathlib, re, sys
expected = sys.argv[2]
assert re.fullmatch('[0-9a-f]{40}', expected), 'Invalid Android metadata checksum'
assert hashlib.sha1(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() == expected, 'Android tools checksum mismatch'
PY
  TEMP_DIR=$(mktemp -d "$PROJECT_DIR/.tools/android-tools.XXXXXX")
  unzip -q "$PROJECT_DIR/.tools/android-tools.zip" -d "$TEMP_DIR"
  mv "$TEMP_DIR/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rmdir "$TEMP_DIR"
  SDK_MANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
fi
echo 'Read and accept Android SDK licenses interactively if requested.'
"$SDK_MANAGER" --sdk_root="$SDK_DIR" --licenses
"$SDK_MANAGER" --sdk_root="$SDK_DIR" 'platform-tools' 'platforms;android-36' \
  'platforms;android-34' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
echo "SDK installed in $SDK_DIR. Set ANDROID_HOME to this path before Gradle/TDLib commands."
