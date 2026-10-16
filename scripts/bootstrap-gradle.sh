#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$PROJECT_DIR/.tools"
ARCHIVE="$PROJECT_DIR/.tools/gradle-8.13-bin.zip"
curl --fail --location --connect-timeout 15 --max-time 300 \
  https://services.gradle.org/distributions/gradle-8.13-bin.zip -o "$ARCHIVE"
curl --fail --location --connect-timeout 15 --max-time 30 \
  https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 -o "$ARCHIVE.sha256"
python3 - "$ARCHIVE" <<'PY'
import hashlib, pathlib, re, sys
archive = pathlib.Path(sys.argv[1])
expected = pathlib.Path(str(archive) + '.sha256').read_text().strip()
assert re.fullmatch('[0-9a-f]{64}', expected), 'Invalid Gradle checksum response'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected, 'Gradle checksum mismatch'
print('Official Gradle checksum verified')
PY
unzip -q -o "$ARCHIVE" -d "$PROJECT_DIR/.tools"
