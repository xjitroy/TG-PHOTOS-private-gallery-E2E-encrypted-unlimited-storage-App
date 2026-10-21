#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
java "$PROJECT_DIR/scripts/LocalChecks.java" "$PROJECT_DIR"
python3 "$PROJECT_DIR/scripts/verify-tdlib-schema.py"
python3 "$PROJECT_DIR/scripts/verify-vault-zip.py"
python3 "$PROJECT_DIR/scripts/verify-people-models.py"
python3 "$PROJECT_DIR/scripts/verify-ocr-runtime.py"
python3 "$PROJECT_DIR/scripts/verify-smart-models.py"
python3 "$PROJECT_DIR/scripts/verify-no-secrets.py"
python3 "$PROJECT_DIR/scripts/check-resources.py"
for SCRIPT in "$PROJECT_DIR"/scripts/*.sh "$PROJECT_DIR/gradlew"; do bash -n "$SCRIPT"; done
echo 'PASS: shell syntax'
