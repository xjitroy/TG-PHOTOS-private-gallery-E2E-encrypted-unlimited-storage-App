# TG Photos 0.19.9 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

Photo transforms reuse cached geometry and memory budgets, coalesce high-resolution tile decoding and avoid recycling buffers still used by the renderer. Pinch no longer also triggers drag/fling. Normal video and Private Vault photo zoom update their render layer; controls stay unscaled. Grid pinches change density only at column boundaries. Private Vault videos retain their existing native controls.

Private Vault fingerprint completion applies AES-GCM AAD after the hardware-authorized callback, accepts only one terminal result and cannot be discarded by an unrelated busy action. Existing wrappers, account/Vault binding, hardware authentication, password fallback and foreground locks remain. No automatic unlock, key reset or biometric-only sensitive operation. If Android has invalidated a key, enable fingerprint again with the Vault password.

English remains default. Existing encrypted face/OCR indexing, model downloads, gallery search and Private Vault AI exclusion stay. No model/recognition accuracy change is claimed.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.9-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/a8c3bf7d2e2916f7ad2e614e7e5606ea3420f84d)

381 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88075531. SHA-256: 011a3b418f8b39e89ea990ac01d99aea263240c096d46161aaeb1bcd2741c33c. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
