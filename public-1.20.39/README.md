# TG Photos 1.20.39 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

TG Photos1.20.39 brings the library ahead of secondary controls. Photos, device folders and Files expose one compact View options entry; existing filters, Clean view, Movies, sorting, layout, folder backup and Memories remain in an explicitly opened sheet. The scroll viewport does not resize when that sheet opens. Active category/search summaries remain visible. Albums uses a device-only rounded square grid: primary collections first, More albums next, names and counts below covers. Ordinary phone sizes use three columns; small windows/large text use two. Cloud-only albums remain in Photos/Files rather than repeating in the device Albums tab; backed-up phone media is kept in its actual phone folders.

Automatic intelligence is queued before optional remote sync can stall it, retriggered when the encrypted index becomes ready and when interactive viewing releases the analysis pause. Face/OCR/subject preparation has independent bounded attempts; an unavailable engine does not silently finish the other engines' job. OCR uses installed verified packs, with downloads owned by the existing dedicated worker. Deferred engines retry, and cloud-only originals wait on an unmetered-network work constraint rather than a polling loop. Settings shows the current preparation/pause/retry status and People restore notices. Existing encrypted People restore must still complete before grouping, protecting confirmed names and privacy exclusions; this release never bypasses that restore or pretends a scan occurred.

Hidden ordinary viewer chrome remains hidden across photo/document page changes, including after auto-hide. Shorts still starts each new reel hidden and held controls stay available. Photo/video Edit launches Android ACTION_EDIT with the actual MIME and a temporary read grant through the existing session-bound provider; original write access is not granted. Installed editors determine compatibility and where edited copies are saved. No in-app photo/video editor option is offered; audio editing remains.

Public Universal1.20.39/code51, same public signer/package, Android8+, all4ABIs. No new dependency, model, runtime permission, data reset or owner-account operation. Prior player/stream/backup/encryption/Room1/session paths/models/private exclusions and all old artifacts are retained. This is scoped source/host verification; no runnable Android target/emulator/KVM exists here. Native layout/gesture/FPS/model/codec/biometric/OEM/account acceptance and an independent security audit remain unverified. No claim of pixel-identical Vivo behavior, every Vivo feature,100percent recognition or permanent no-crash/zero-load operation.


**Phone acceptance pending:** large-tile visual quality, scroll behavior and the owner’s exact native crashes have not been reproduced on Android here. There is no attached device/emulator/KVM. Host coroutine/geometry stress checks do not establish real-device pixel quality, smoothness, Compose/native stability or a permanent fix for every possible crash.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-1.20.39-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/fb52630d926f7906a8d816b4dcbca869123e71c0)

586 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88301430. SHA-256: c34f572cc786bf6f1b5c1388e6039e6dae93c5aaf099d6cdbeee668d1b6fb4b3. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
