# Third-party notices

The vendored `core/telegram/src/main/java/org/drinkless/tdlib/JsonClient.java` is unmodified official TDLib source from commit `42e6a5259551178d1dab54a22ad96d14bd906e20`, Git blob `ec632d0282b99d042ed02e991e90ef6fa376ace6`. Copyright Aliaksei Levin and Arseny Smirnov, distributed under Boost Software License 1.0. Full license is included at `licenses/TDLib-Boost-1.0.txt`.

TDLib auth schema excerpt is from the same commit, complete schema Git blob `d96a259c6f932b9961bb5e025afb60fe17ccc29e`. Upstream build scripts are downloaded/generated, not vendored. Native TDLib and OpenSSL licensing/notices must be included in distributable artifacts when built.

AndroidX, Kotlin, Coroutines, Gradle, JUnit and other tools retain their respective upstream licenses. Produce a resolved dependency license inventory and app About notices before release. OpenSSL baseline is 3.5.9; review license/security updates and compatibility before distribution. No proprietary Telegram logo or official affiliation is claimed.

No distribution license has been selected for owner-authored TeleVault code. The repository is private; public/open-source publication requires the owner's licensing decision.

Vault recovery uses org.bouncycastle:bcprov-jdk18on:1.83 (Bouncy Castle license). Include its resolved license notice in the final distribution inventory alongside AndroidX/Media3/WorkManager and native OpenSSL/TDLib notices.


People (0.19.7, optional on-device CPU):
- YuNet 2023mar detector, MIT: https://github.com/opencv/opencv_zoo/tree/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_detection_yunet
- SFace 2021dec recognizer, Apache-2.0: https://github.com/opencv/opencv_zoo/tree/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_recognition_sface
- ONNX Runtime Android1.22.0, MIT, vendored unmodified Maven AAR: https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/
The models require a user-requested39MB verified HTTPS download, then inference stays on-device. Immutable URLs, sizes and SHA256 are in app/src/main/assets/people-models.json; the manifest/runtime pins are in config/people-models.json. The64-bit runtime libraries support16KB pages;32-bit JNI wrappers use4KB alignment on32-bit devices. License texts are bundled in assets/third-party. Private Vault is excluded. Host recognition smoke is limited evidence, not an Android/device accuracy benchmark. Older model license notices remain for historical provenance; the old TFLite models/runtime are no longer included.



Optional eight-language OCR (0.19.3): Tesseract4Android4.9.0 (Apache-2.0) from https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0 and https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar ; includes Tesseract5.5.1 (Apache-2.0), Leptonica1.85.0 (BSD), IJG JPEG and libpng licenses. Exact bundled versions/source/build files are under that immutable upstream tag. License texts are in licenses/ and app assets/third-party. Language data is from pinned tessdata_fast commit87416418657359cb625c412a48b6e1d6d41c29bd, Apache-2.0; config/ocr-models.json records all nine file sizes/URLs/SHA256 and pinned runtime/owned bridge hashes. Only public models download; recognition and encrypted derived text remain local. Owned JNI source: app/src/main/cpp/ocr_script.c, rebuild: scripts/build-ocr-script.py --ndk /path/to/ndk28.
