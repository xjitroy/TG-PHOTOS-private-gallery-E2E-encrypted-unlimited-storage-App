# 0.18 additional pinned runtime components

Media3 Transformer, Effect and Muxer1.8.0 are published Apache2 AndroidX dependencies using the same pinned Media3 family. The original app FD muxer adapter writes only the user's explicit seekable output and introduces no new native codec binary. Zip4j2.11.5 is the vendored Apache2 AES-ZIP runtime with pinned digest/notice below. No GPL/AGPL reference source or branded graphics are copied. All six approved gallery/file/vault projects remain behavior/design references only unless an explicit retained notice states otherwise.

# Third-party source and dependency inventory — 0.17

No complete upstream application was imported. New UI/editors/Private Vault are original native Kotlin/Compose implementations using this project's existing crypto and Android/Media3 APIs. No Ente AGPL or Material Files GPL source was copied; their URLs in the supplied master plan are reference-only. Aves/Photok likewise remain concept references with no vendored source.

| Included component | Exact source/version | License and notices |
|---|---|---|
| TDLib official JSON Java | https://github.com/tdlib/td/tree/42e6a5259551178d1dab54a22ad96d14bd906e20 ; official JsonClient.java blob ec632d0282b99d042ed02e991e90ef6fa376ace6 | Boost 1.0, licenses/TDLib-Boost-1.0.txt |
| Four TDLib native ABIs | Same TDLib pin, reproducible scripts/build-tdlib-abi.sh | Same Boost; native digests in artifact/build report |
| OpenSSL native static linkage | https://github.com/openssl/openssl/tree/45e844fa2a14ec92d146bd8f5778ac130b6625fb | Apache 2.0, licenses/OpenSSL-Apache-2.0.txt |
| AndroidX/Media3 | https://github.com/androidx/media ; published artifacts 1.8.0; other exact catalog/app versions | Apache 2.0, licenses/AndroidX-Apache-2.0.txt |
| Bouncy Castle | org.bouncycastle:bcprov-jdk18on:1.83, https://www.bouncycastle.org/ | Bouncy Castle license in licenses/BouncyCastle-0.txt |
| Zip4j AES ZIP | net.lingala.zip4j:zip4j:2.11.5, https://github.com/srikanth-lingala/zip4j/tree/v2.11.5 ; pinned local JAR SHA256 fee9a941f286166d4a19719d818f20e9f3addb3cbd7291c08b67f0e0ce75a9fd; Central SHA1 matched 4214e396e3891cb19eded3ce5e584cba9032fdc3 | Apache 2.0, licenses/Zip4j-Apache-2.0.txt and bundled notice. No runtime download. |
| Gradle Wrapper | Gradle 8.13 official generated wrapper and SHA-pinned distribution | Apache 2.0 plus bundled third-party notices, licenses/Gradle-* |
| Kotlin/Coroutines, Jetpack Compose/Room/WorkManager, build tools | Exact direct coordinates in build files; resolved runtime graph exported to docs/DEPENDENCIES.txt | Upstream published artifact licenses; no copied app branding |

The editable source export includes these four native runtime libraries, not an Android SDK/NDK or build cache. Rebuild instructions and upstream references remain included. Dependency graph is a resolved inventory, not an independent vulnerability audit. Project source is published for inspection. No additional redistribution license for project-authored code has been selected; third-party licenses remain applicable. Original THIRD_PARTY_NOTICES.md and historical notices remain.
