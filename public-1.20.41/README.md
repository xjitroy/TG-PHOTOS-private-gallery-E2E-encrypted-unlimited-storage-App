# TG Photos1.20.41 — public Universal APK

[Download signed Universal APK](TG-Photos-1.20.41-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/v1.20.41-source)

TG Photos1.20.41 adds four selectable on-device AI models: SmolVLM2-256M (~235MB), SmolVLM2-500M (~546MB), Qwen3.5-0.8B (~738MB), and full text/vision/audio EmbeddingGemma2 (~485MB). Nominal8–12GB phones default to EmbeddingGemma2; smaller RAM tiers use Small/Balanced and over12GiB uses Qwen. Explicit choice persists, with model-specific safety fallback to Small.

The selected large model and enabled small face/OCR/subject packs download automatically after setup. Pause/Off stays paused/off; alternatives download only when selected. Exact size/SHA verification and real initialization precede durable activation and old-model/compiler-cache cleanup. Downloads do not hold the working inference lane. Inference uses a non-exported child process without vault keys. Existing face identities/names, eight-language exact OCR, encrypted per-content reuse, Private Vault exclusions and E2E result recovery remain.

Smart search adds bounded256D quantized semantic ranking under existing person/type/date filters, with Bengali/Hindi/English calendar phrases. Generated descriptions can hallucinate; they do not replace identity embeddings or exact OCR. Video analysis currently uses representative previews; complete video-moment and audio-library search UI are not claimed. First-run/compiler caches need extra storage. New large inference requires ARM64; existing Android8+ four-ABI gallery/player/editor/backup features remain.

622 distinct JVM tests (domain348/crypto64/Telegram8/app202), zero failures/errors/skips, all ktlint and nine local checks pass. Runtime/Java API derivatives reproduce byte-for-byte. Synthetic host model smoke tests are not Android RAM, JNI, recognition, frame, codec, OEM or account acceptance. No Android target/emulator/KVM is available here; no perfect-accuracy or permanent crash-free claim.

Hashless large device videos also retain bounded local encrypted AI results by source, stamp and model revision. This avoids repeated analysis without hashing an entire movie solely for AI; metadata is never used as proof of a cloud backup.


Same public package/signing identity, Android8+, ARM64/ARMv7/x86/x86_64 gallery engines. New large-model inference requires ARM64. Install over the previous public APK without uninstalling or clearing data. Model downloads and compiler caches need extra storage; only the selected large model is installed. First-run choice can be changed under Settings → People & text search → On-device AI.

Scoped checks: 622 host JVM tests, nine local checks, all ktlint and release lint with zero errors. Static signature/ZIP/frozen-source/four-ABI/JNI/worker/API26 checks are evidence of their stated scope. Native recognition/RAM/layout/codec/OEM/background/account acceptance has not been exercised on Android here. No perfect-accuracy, every-feature or permanent crash-free guarantee.

APK bytes: 101260804. SHA256: f35ac8e6e8b9681befbc8e8d31ccae056fac9e9108d78beca7b40f04da71d75a. Certificate SHA256: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Preserve Telegram Saved Messages encrypted backup/recovery/catalog attachments, passwords and recovery keys. No private APK, signing material, owner session, original media or private workspace history is published.
