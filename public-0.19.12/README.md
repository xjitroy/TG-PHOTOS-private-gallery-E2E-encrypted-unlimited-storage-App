# TG Photos 0.19.12 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

The filmstrip container has no colored background or Surface. Its gaps and edge padding show the media underneath. Drag/fling changes transient RAM/cached/thumbnail previews; the real pager changes once on settlement, avoiding a decoder/player cycle at every intermediate position. Main swipes still center the strip. The list is a passive renderer with a single drag owner; automatic following cannot drive a competing fling or pager request. Starting another gesture cancels pending movement; stale/failed navigation cannot finish a newer gesture.

Separate two-permit gates bound thumbnail and original-native work. Waiting requests cancel before entering native I/O, and a running native call holds its permit until it returns. Transient thumbnail memory errors retain cached pixels/placeholder. The previous stable grid quality and fast viewer exit remain.

Library/Room/cache setup and startup recovery run on IO behind one shared initialization task. The first Activity frame shows an opening screen instead of waiting for that storage work. Failed startup can retry without resetting anything; slow/error startup offers an explicitly copied, allowlisted diagnostic report with app/device/exit/exception class and frame facts, excluding exception messages, paths, media names, phone numbers and keys. Security callbacks remain synchronous, including worker-only processes; unused Private Vault state is not instantiated merely to lock it. Same auth/session/key/cipher/Room1 paths; English default and Private Vault AI exclusion remain.

**Phone acceptance pending:** the owner's exact filmstrip crash and logo hang have not been reproduced on Android here. There is no attached device/emulator/KVM. Host coroutine/geometry stress checks do not establish real-device Compose/native stability or a permanent fix for every possible crash.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.12-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/a46980a3ca9b5ba7509013bba2eb8c7b9a8ef34d)

417 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88088927. SHA-256: bdcd84dc1dcfda78f5492394ef39d0d4614ebb57da56c2584cb9fcbf1c531a25. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
