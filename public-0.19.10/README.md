# TG Photos 0.19.10 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

Photo/PDF/Details/private-preview reads have independent process-only, source-bound cancellation owners. Closing a viewer cancels its cloud reads and rejects late opens; one descriptor closing does not cancel sibling readers. Existing cryptographic authentication, account/Vault binding, expiry/epoch checks and private RAM-only handling remain. No token contains a key or grants access. Explicit external share grants and backup/upload jobs keep their existing separate lifetime.

Abandoned decoder/descriptor/bitmap cleanup stays off the UI thread. Size/read callback cancellation or revoked access becomes a file error rather than an unchecked callback exception. Bounded native decode memory failures use the existing error/retry/fallback UI. Details uses stored cloud capture facts first; device/missing facts have a cancellable IO probe. Private reader master copies are wiped on early failures too. English stays default; model packs and Private Vault AI exclusion are unchanged.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.10-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/e70f70569de465b45cb9b7774bd33dfccc88d04d)

389 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88077047. SHA-256: 499bf9bf594e0587c91434273a795577b5ad4cee1c42b2cf820b53877bc44ddc. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
