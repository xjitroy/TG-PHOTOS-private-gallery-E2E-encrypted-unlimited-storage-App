#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
TD_COMMIT=42e6a5259551178d1dab54a22ad96d14bd906e20
TD_DIR="$PROJECT_DIR/external/tdlib-src"
SDK_DIR="${ANDROID_HOME:-$PROJECT_DIR/.tools/android-sdk}"
mkdir -p "$PROJECT_DIR/external"
if [[ ! -d "$TD_DIR/.git" ]]; then
  git clone --filter=blob:none https://github.com/tdlib/td.git "$TD_DIR"
fi
[[ $(git -C "$TD_DIR" remote get-url origin) == https://github.com/tdlib/td.git ]] || { echo 'Unexpected TDLib origin'; exit 1; }
[[ -z $(git -C "$TD_DIR" status --porcelain --untracked-files=no) ]] || { echo 'Modified TDLib source; refusing overwrite'; exit 1; }
git -C "$TD_DIR" fetch origin "$TD_COMMIT"
git -C "$TD_DIR" checkout --detach "$TD_COMMIT"
[[ $(git -C "$TD_DIR" rev-parse HEAD) == "$TD_COMMIT" ]]
python3 "$PROJECT_DIR/scripts/verify-tdlib-schema.py" "$TD_DIR/td/generate/scheme/td_api.tl"
if [[ ${1:-} == --verify-only ]]; then
  echo 'Pinned official TDLib checkout and auth schema verified; native build not requested.'
  exit 0
fi
exec bash "$PROJECT_DIR/scripts/build-tdlib-arm64.sh"
