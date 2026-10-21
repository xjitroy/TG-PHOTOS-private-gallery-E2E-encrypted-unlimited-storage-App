# Public1.20.41 · on-device AI choices

Four selectable large models, one active at a time: SmolVLM2-256M, SmolVLM2-500M, Qwen3.5-0.8B and full EmbeddingGemma2. Advertised8–12GB phones select EmbeddingGemma2; low-RAM tiers use smaller models. Replacement waits for verification and real initialization before durable activation and old-model/cache cleanup. Face/OCR packs remain separate. New inference needs ARM64; existing Android8+ four-ABI gallery features stay. Java21 compiler is required to read the LiteRT-LM0.18 API; app Java/Kotlin bytecode targets17. Models download after setup; none are bundled. Working caches require additional storage.

Natural search uses encrypted per-photo cached results and a bounded semantic shortlist; generated descriptions can be wrong and are not verified identities or exact OCR. Text/photo matching has been exercised with synthetic host fixtures. Real Android model/RAM/frame/recognition/OEM acceptance is unavailable here. No perfect-accuracy claim.

# Public Universal1.20.40/code52

Free photo/video editing reuses bundled Android Graphics and Media3; no new SDK/model/dependency. Existing verified AI packs auto-download independently of backup and have no app battery/Wi-Fi-only gate. Preserve the public signer, keys/ciphers/Room1/sessions and old artifacts. Build `-PtgUniversalOnly=true :app:assembleRelease :app:lintRelease`. Native acceptance remains unverified.

# Public Universal1.20.39/code51

Use the existing public signing identity to install over the previous public build. No new models or dependencies are added. Build with `-PtgUniversalOnly=true :app:assembleRelease :app:lintRelease`; this does not establish real-phone recognition, rendering or gesture acceptance.

# Public Universal APK (1.20.31)

The public release is one signed Universal APK for ARM64, ARMv7, x86 and x86_64, Android8+. Build with `-PtgUniversalOnly=true` and use release/output-metadata.json for its exact filename. Standard compressed JNI packaging keeps the single file small; Android extracts only the device's matching ABI. Preserve the official signing identity for compatible public updates. Private/personal distributions are not included in this release.

Source includes all four pinned TDLib runtimes, the vendored ONNX Runtime/Tesseract dependencies and owned OCR JNI bridge. People uses a verified one-time39MB, size/SHA256-pinned YuNet/SFace model download, then runs on-device. OCR language weights are verified28.5MB downloads. Fresh setup automatically schedules existing AI packs; explicit disabled choices are preserved. Models never contain private photo data. Verify licenses/runtime/model manifests with scripts/check-local.sh. The OCR bridge can be rebuilt with scripts/build-ocr-script.py --ndk /path/to/ndk28; TDLib runtimes stay unchanged. Supply private Telegram/signing inputs separately.

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

0.19.11 adds a centered draggable thumbnail filmstrip to the shared normal viewer. Main swipes and strip scrubbing stay synchronized in selection order, including first/last centering and the active video. Heavy new original/playback work waits for strip settlement while already open photos retain their sharp source. Exit retains a110ms fade/scale without a second platform-window animation. Gallery scrolling/selection no longer forces96px thumbnails; larger cached images are reused and never replaced by a smaller completion. Local fallback sampling preserves the requested detail. Host checks do not measure real Android FPS or gesture/native acceptance.

0.19.12 repairs the filmstrip regression with a transparent, single-owner preview strip: drag/fling changes cached/thumbnail previews and commits the real pager once at settlement. Native/thumbnail work is separately bounded and cancelled waiting jobs do not start native work. Storage/service construction and startup recovery run on IO behind shared initialization; slow/error startup offers an allowlisted diagnostic-copy action and failed startup may retry without resetting data. Security callbacks, encryption/session paths, English, models and private AI exclusion remain. The reported exact phone crash/logo hang still needs affected-device trace/acceptance; host stress tests do not establish native UI stability.

1.20.31 preserves Room schema1, all encryption/session paths and public signing identity. MobileNetV2 weights are not bundled. memory-models.json pins the optional14.25MB Apache-2.0 weights and the bundled1000-class label hash; ONNX Runtime1.22.0 remains pinned for all4ABIs. See THIRD_PARTY_SOURCES/NOTICES. The new download worker identity must remain retained by R8.

1.20.32/code44 keeps all model, crypto, schema and signer pins. Tile crop detail reuses the bounded original decoder, source-bound read ownership and existing decoded cache. Host tests cover tall-cell geometry, crop/source/RAM bounds and exact50-photo cache-stage boundaries. Android visual/performance acceptance is still separate.
