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
