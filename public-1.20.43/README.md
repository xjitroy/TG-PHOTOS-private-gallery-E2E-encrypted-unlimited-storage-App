# TG Photos1.20.43 — public Universal APK

[Download signed Universal APK](TG-Photos-1.20.43-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/v1.20.43-source)

TG Photos1.20.43 repairs encrypted AI metadata recovery. Private-exclusion, People and per-photo analysis attachments were incorrectly read through the original-media DATA-only gateway; exact authenticated metadata now has its own bounded, freshly validated read path. Ordinary media and Private Vault readers keep their original restrictions. Account/chat/message/caption/size, namespace and cancellation/lock checks precede each encrypted range; existing ciphers, keys and records are unchanged.

Inbound private exclusions now finish independently of pending outbound publication. Each authenticated operation checkpoints before continuing; failures or interrupted enumeration never mark an incomplete first restore ready. A later retry reuses verified checkpoints. Model fast-path success clears the stale “Verifying…” message, and cancellation shows paused status. Existing selected large weights and small face/OCR/subject engines are retained; no new model download or engine migration is introduced.

Settings → People & text search starts with AI progress: privacy restoration, specialist model download percentages, overall and per-engine saved-photo analysis counts/percentages, and encrypted result recovery. Attempts and unreadable photos never count as saved results. Matching saved results count once; changed/deleted candidates and scoped retries are handled. Installation/runtime testing has no measurable time percentage, so it remains explicitly in progress after download reaches100percent. Lock clears scoped progress. Search reports a clear waiting state and retries while authenticated private exclusions are restoring. Once privacy is ready, exact filename/OCR results remain independent of cold large-model startup. All existing OCR/private-exclusion guards remain.

Required scoped checks: all host JVM tests, ktlint, nine root/nine sanitized-source checks and zero-error release lint, plus signed-APK/package/certificate/worker/JNI/API26/pinned-native checks before publication. Runtime/model pins are unchanged from the byte-reproduced1.20.42 derivatives. Actual Android model installation, recognition/RAM/background/UI/account acceptance remains unverified because no usable Android target/KVM is available here. No perfect-accuracy claim.


Same public package/signing identity, Android8+, ARM64/ARMv7/x86/x86_64 gallery engines. New large-model inference requires ARM64. Install over the previous public APK without uninstalling or clearing data. Model downloads and compiler caches need extra storage; only the selected large model is installed. First-run choice can be changed under Settings → People & text search → On-device AI.

Scoped checks: 646 host JVM tests, nine local checks, all ktlint and release lint with zero errors. Static signature/ZIP/frozen-source/four-ABI/JNI/worker/API26 checks are evidence of their stated scope. Native recognition/RAM/layout/codec/OEM/background/account acceptance has not been exercised on Android here. No perfect-accuracy, every-feature or permanent crash-free guarantee.

APK bytes: 101281352. SHA256: 1848d0f482cc982856775d4b4252d47fe764be09e7251cd899676dc5bd0997fc. Certificate SHA256: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Preserve Telegram Saved Messages encrypted backup/recovery/catalog attachments, passwords and recovery keys. No private APK, signing material, owner session, original media or private workspace history is published.
