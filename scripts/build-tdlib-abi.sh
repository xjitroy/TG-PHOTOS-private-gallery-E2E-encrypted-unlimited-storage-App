#!/usr/bin/env bash
# Pinned official source; builds one Android ABI without altering other native outputs.
# PHP is used by upstream's generated Java API, which JSONJava does not need.
set -euo pipefail
ABI=${1:-arm64-v8a}
case "$ABI" in
  arm64-v8a) OPENSSL_TARGET=android-arm64 ;;
  armeabi-v7a) OPENSSL_TARGET=android-arm ;;
  x86) OPENSSL_TARGET=android-x86 ;;
  x86_64) OPENSSL_TARGET=android-x86_64 ;;
  *) echo "Unsupported Android ABI"; exit 1 ;;
esac
PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
SDK_DIR="${ANDROID_HOME:-$PROJECT_DIR/.tools/android-sdk}"
TD_DIR="$PROJECT_DIR/external/tdlib-src"
OPENSSL_PIN=45e844fa2a14ec92d146bd8f5778ac130b6625fb
export JAVA_HOME="${JAVA_HOME:-$PROJECT_DIR/.tools/jdk17}"
export ANDROID_NDK_ROOT="$SDK_DIR/ndk/28.2.13676358"
export PATH="$SDK_DIR/cmake/3.22.1/bin:$PROJECT_DIR/.tools/native-host/usr/bin:$JAVA_HOME/bin:$PATH"
[[ $(git -C "$TD_DIR" rev-parse HEAD) == 42e6a5259551178d1dab54a22ad96d14bd906e20 ]]
python3 "$PROJECT_DIR/scripts/verify-tdlib-schema.py" "$TD_DIR/td/generate/scheme/td_api.tl"
OPENSSL_DIR="$PROJECT_DIR/external/openssl-$ABI-$OPENSSL_PIN"
SOURCE_DIR="$PROJECT_DIR/external/openssl-source-$ABI-$OPENSSL_PIN"
if [[ ! -f "$OPENSSL_DIR/.complete" ]]; then
  mkdir -p "$SOURCE_DIR"
  if [[ ! -f "$PROJECT_DIR/external/openssl-source.tar.gz" ]]; then
    curl --fail --location --connect-timeout 15 --max-time 300 \
      "https://github.com/openssl/openssl/archive/$OPENSSL_PIN.tar.gz" -o "$PROJECT_DIR/external/openssl-source.tar.gz"
  fi
  tar -xzf "$PROJECT_DIR/external/openssl-source.tar.gz" -C "$SOURCE_DIR" --strip-components=1
  cd "$SOURCE_DIR"
  PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH" \
    LDFLAGS=-Wl,-z,max-page-size=16384 ./Configure "$OPENSSL_TARGET" no-shared \
    -U__ANDROID_API__ -D__ANDROID_API__=26 -ffunction-sections -fdata-sections
  PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH" make -j2
  mkdir -p "$OPENSSL_DIR/lib"
  cp libcrypto.a libssl.a "$OPENSSL_DIR/lib/"
  cp -r include "$OPENSSL_DIR/"
  printf '%s\n' "$OPENSSL_PIN" > "$OPENSSL_DIR/.complete"
fi
[[ $(cat "$OPENSSL_DIR/.complete") == "$OPENSSL_PIN" ]]
[[ -f "$TD_DIR/example/android/build-native-JSONJava/build.ninja" ]] || { echo "Run bootstrap-tdlib.sh first for the pinned host code generators"; exit 1; }
BUILD_DIR="$TD_DIR/example/android/build-$ABI-JSONJava"
cmake -S "$TD_DIR/example/android" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DOPENSSL_ROOT_DIR="$OPENSSL_DIR" -DCMAKE_BUILD_TYPE=RelWithDebInfo -GNinja \
  -DANDROID_ABI="$ABI" -DANDROID_STL=c++_static -DANDROID_PLATFORM=android-26 \
  -DTD_ANDROID_JSON_JAVA=ON
cmake --build "$BUILD_DIR" --target tdjni --parallel 2
cmp "$PROJECT_DIR/core/telegram/src/main/java/org/drinkless/tdlib/JsonClient.java" \
  "$TD_DIR/example/java/org/drinkless/tdlib/JsonClient.java"
DEST="$PROJECT_DIR/core/telegram/src/main/jniLibs/$ABI"
mkdir -p "$DEST"
cp "$BUILD_DIR/libtdjsonjava.so" "$DEST/"
sha256sum "$DEST/libtdjsonjava.so" > "$PROJECT_DIR/external/tdlib-native-$ABI-sha256.txt"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -l "$DEST/libtdjsonjava.so" \
  > "$PROJECT_DIR/external/tdlib-$ABI-program-headers.txt"
echo "Built pinned TDLib JSONJava $ABI"
