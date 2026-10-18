# TG Photos 0.19.11 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

The shared viewer has a centered, draggable thumbnail filmstrip across Photos, Albums, Search and file selections. Drag/fling changes sequential previews; first and last thumbnails can center too. Main-page swipes track the strip continuously. The existing video suggestions list remains; its separate short-video strip is replaced by the shared strip in selection order, including the current media. During scrubbing, previews are used and heavy original reads/playback/prefetch wait until the strip settles. No photo loading spinner is introduced. Gallery scrolling/selection no longer forces96px previews or waits180ms to restore detail. Larger cached/displayed thumbnails are retained; local fallback sampling preserves requested detail.

Viewer exit keeps a110ms fade/scale, removes the second platform dialog animation and cancels superseded thumbnail navigation. Existing cancellation, bounded decoding/zoom, encryption/session paths, default English and Private Vault AI exclusion are retained. The single-item Private Vault preview keeps its separate secure viewer; this update does not add adjacent private-item navigation.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.11-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/6f4a7a2ab94587f5582a7b17e3a46860e64e1f8e)

403 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88082947. SHA-256: 47eefc390bca3e73aaf270bd03c56e4647df88c72d32b58f94a7511db6f760f5. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
