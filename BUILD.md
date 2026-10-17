# 0.19.3 CPU-specific APK outputs

The normal app is built from one shared source for personal-update and public distributions. ABI splitting emits ARM64, ARMv7, x86 and x86_64 APKs plus a universal local output. Read app/build/outputs/apk/hardened/output-metadata.json or release/output-metadata.json for exact filenames. Use ARM64 for most current phones; other CPU choices have identical DEX/features/version/signing identity. Install the personal update over the existing personal app without uninstalling/clearing data. The public distribution retains its separate established package and signer.

Public downloads use per-CPU APKs to stay below GitHub's ordinary file limit; a universal APK is also built/verified and retained locally. Source contains all four pinned TDLib runtimes, offline People models, vendored LiteRT/Tesseract dependencies and the owned OCR JNI bridge. Model/runtime integrity checks are in scripts/check-local.sh. The small OCR bridge can be rebuilt with scripts/build-ocr-script.py --ndk /path/to/ndk28; no Telegram native runtime is modified. OCR language weights are optional verified28.5MB downloads, not bundled photo data. Supply private Telegram configuration/signing credentials separately; they are never included in source exports.

Existing build and dependency instructions follow. Older illustrative output filenames may predate ABI splitting; use output-metadata.json for current paths.

# Build TG Photos

Use JDK17, Gradle Wrapper8.13, Android SDK36/build-tools36.0.0, minSDK26. Exact dependencies are in gradle/libs.versions.toml, app/core Gradle files and docs/DEPENDENCIES.txt. Native source pins, licenses and all four pinned native runtime libraries are included. Rebuild instructions: scripts/bootstrap-tdlib.sh and scripts/build-tdlib-abi.sh.

Supply your own TELEGRAM_API_ID and TELEGRAM_API_HASH through environment variables or a private ignored local.properties. Never commit these source inputs, sessions, keystores or real user data.

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :core:domain:test :core:crypto:test :core:telegram:testDebugUnitTest :app:testDebugUnitTest :app:ktlintCheck :app:lintDebug
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -Ptelevault.applicationId=app.televault.sandbox :app:assembleDebug
scripts/check-local.sh
```

The sandbox package avoids replacing an installed official/personal app. An official release build needs TG_PHOTOS_RELEASE_KEYSTORE, TG_PHOTOS_RELEASE_STORE_PASSWORD, TG_PHOTOS_RELEASE_KEY_ALIAS and TG_PHOTOS_RELEASE_KEY_PASSWORD. Production checks pin the official certificate and reject development identities; the private key is intentionally absent. A fork needs its own separate signing/update identity and must not impersonate the official distribution.

PUBLIC_SOURCE_MANIFEST.json lists executable source/native hashes and the source checkpoint embedded in the published APK. Public documentation is curated separately from the private development tree. Fresh local builds embed their own Git HEAD. Signing protects compatible official updates, not complete reverse-engineering resistance. Build/test reports are evidence of their stated scope, not Android hardware acceptance or an independent crypto audit.

For the single public Universal APK: `./gradlew :app:assembleRelease -PtgUniversalOnly=true`. Standard compressed JNI packaging includes every supported ABI; Android extracts the matching native libraries at installation. Existing release signing configuration is still required.
