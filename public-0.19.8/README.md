# TG Photos 0.19.8 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

Opaque Glass full-screen Search/People pages and inset areas prevent the underlying screen bleeding through. Opaque navigation/compact surfaces keep text readable. Compact controls fade without being clipped, and date pills remain hidden throughout their entry/exit, preserving media viewport geometry.

Search results use the same Photos gallery grid, date grouping, pinch/zoom and mosaic controls, with category/Clean/Favorites/type/date/person/alone/together filters. Everyone clears person-only restrictions; Clear all removes all criteria. Documents/audio keep filenames and no unrelated person-add icon. Source progress is coalesced instead of repeatedly cancelling a search. A4MiB session-only OCR text cache speeds repeated matching without plaintext disk files and is invalidated on lock/scope/clear/private cleanup.

Person profiles have explicit Edit name/Save with pending/error feedback and close only after local encrypted persistence. Telegram sync follows. Settings → People & text search and People settings offer confirmed Reset face detection, retaining names/manual tags/decisions by default. Explicit full reset uses authenticated encrypted group deletion markers; originals, OCR and model files remain. Other devices must sync.

Face/OCR indexing uses one serialized WorkManager pipeline, foreground notification and bounded8minute batches/retry, device originals first, existing encrypted receipts and unmetered cloud access. New items and completed model downloads request work; periodic15minute checks recover eligible work. Individual bad/inaccessible files are isolated. It requires unlocked Photos and the current account/epoch; lock, force-stop, power saver or Private Vault viewing pauses analysis, without new key storage or automatic unlocking. Android/OEM timing is not guaranteed. Private Vault never enters face/OCR/Lens. Existing YuNet/SFace/OCR weights remain; no new accuracy benchmark is claimed. English stays default.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.8-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/cb6e81ef055a0718c8ad2f996a1b2d9bd7d1a0d6)

360 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88070599. SHA-256: 8a34225fd576a0d7cedfa6e83789a4274c3d6eaa0a6ceee298162aa39dcb2a8f. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
