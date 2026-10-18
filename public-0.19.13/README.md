# TG Photos 0.19.13 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

The shared normal-viewer filmstrip now uses sampled screen-fit pixels from device originals or hash-verified device copies of cloud files, then an authenticated cloud reader when needed. It observes cache upgrades, preserves already sharper frames through neighbor eviction and renders all EXIF rotations/reflections. The selected photo loads first; at most four nearby photos warm at half the screen-fit pixel area within the source-resolution/RAM budget. One dedicated decoder plus the existing original-work gate bound native activity, and replacement/close revokes old source-bound reads. The real pager/player still commits once at settlement, and the strip container remains transparent. Publication failure after a shared cache accepts pixels cannot recycle renderer-owned bitmaps. No plaintext preview file is added.

New photo backups carry an oriented1024px JPEG preview, capped512KiB and encrypted in the existing catalogue format. Existing smaller backups remain readable and upgrade from originals; no original or old catalogue is reuploaded automatically. Thumbnail tier reuse does not satisfy large requests with tiny cached images or pin screen-sized bitmaps in every strip tile. Decoded memory remains bounded, including low-memory devices. Network/provider latency, offline uncached cloud photos, source resolution and low-memory limits can still delay or limit detail; there is no instant50%-quality or measured-FPS guarantee.

Normal cloud Trash/restore and recoverable device-file removals update presentation while the existing authorized operation runs, with an explicit updating status and rollback for unchanged files on failure/cancellation and ownership-safe overlapping requests. Actual device confirmations immediately update the index and invalidate older scans. Queued permanent cloud removals use the existing encrypted resumable jobs and Transfers status; failed/cancelled/cooldown-blocked jobs reveal retained rows. Password/fingerprint/Android consent, verified recoverable copies, encryption and24-hour protection remain. Large-file copying/verification and Telegram confirmation still take real time; presentation never claims premature permanent completion.

Smart animation durations use0.8x, page entrance150ms and strip settling120ms before user/system scaling. Explicit1x-4x, reduced motion and system scale remain respected, while playback and security timers stay unchanged. English default, Private Vault AI exclusion, models, keys/ciphers/Room1/session paths, public package/signer and prior startup/zoom/biometric safeguards remain. No private APK, owner-account command or data reset.


**Phone acceptance pending:** the owner’s exact drag quality, deletion timing, gestures and reported crashes have not been reproduced on Android here. There is no attached device/emulator/KVM. Host coroutine/geometry stress checks do not establish real-device pixel quality, smoothness, Compose/native stability or a permanent fix for every possible crash.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.13-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/bd02ac6ef6fa9ceef0b3b0a52ae6a4a499ad9dbb)

434 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88097931. SHA-256: 842083673e6658282515a6a8dd271c5b3736706098c7cec600fbaacf9c8ec348. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
