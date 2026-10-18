# TG Photos 0.19.7 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

Photos-style scroll-away title/status/filter/search controls now apply to Albums, Files/folders and the Private Vault gallery. Reverse user scrolling reveals compact controls; a true top/new route restores the full introduction. Folder filters keep their current folder. Security errors/selection actions stay visible.

People now uses licensed OpenCV YuNet + SFace with ONNX Runtime CPU. Open Albums → People → Download & start for the one-time39MB verified model download. Inference starts only after hash/size verification; interrupted downloads can resume. Old bundled TFLite inference is removed. Legacy references migrate without comparing embeddings from different models. Stable IDs, names, reviewed tags, own-face selection and encrypted recovery remain.

Strong automatic duplicates consolidate while shared-photo faces, explicit different-person answers and competing names block merges. Uncertain single-photo groups are labelled unsorted. Reviews start only when requested. Highly confident diverse examples improve references; neural weights are not trained. Private Vault stays excluded from People/OCR/Lens. Existing OCR and three-second text selection remain.

English is the default UI/formatting language, preserving device region and respecting explicit Android per-app locale choices. System-owned dialogs follow Android settings.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-0.19.7-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/d7016b0c1342fb9c59fc2351f4cf75e1414f42eb)

355 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88063863. SHA-256: 1115a0ae7c6b5a2a765cd078afbd4174ba8b828e7ec305085e2475021006027c. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
