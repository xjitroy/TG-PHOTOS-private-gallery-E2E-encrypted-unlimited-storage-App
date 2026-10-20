# TG Photos 1.20.37 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

TG Photos1.20.37 keeps timeline date/header geometry unchanged when compact controls appear. Date labels fade over180ms instead of removing and reinserting sticky rows, so reverse scrolling does not change the height of every photo/document group.

Compact controls are eligible only after the introduction has left the viewport. The same scroll policy covers Photos, Albums, file lists and the unlocked Vault gallery; returning to the introduction suppresses the duplicate overlay. Reverse controls use a short fade/slide without resizing the list viewport.

Categories, Clean view and Movies wrap within the available width instead of clipping inside a horizontal strip. All/Photos/Videos/Favorites fills its available width with equal touch targets and an animated selected color; narrow windows or large text use two rows. Existing category selection, hidden categories, Clean view, Movies threshold and all filter actions remain.

Compatible public Universal1.20.37/code49, Android8+, all4ABIs and the existing public signer. No feature, data, key, cipher, Room schema, session path, model pack, backup policy or prior artifact is removed or reset. No new model is installed. Existing1.20.36 playback compatibility, bounded5/10GiB reads and authenticated backup matching remain.

Verification uses host regression tests, source review and signed artifact checks. There is no runnable Android target/emulator/KVM here; exact rendered animation, small-screen/large-font layout, frame timing, affected-phone crashes, codecs and OEM behavior remain unverified. No permanent crash-free or zero-latency claim.

The right-edge draggable fast-scroll handle appears for scrollable photo/video grids with at least100 entries, including Photos, Albums and shared search results. It shows the target date while dragging, maps real lazy indices including mosaic blocks/date headings, uses a conflated latest-target channel and bounded immediate seeks rather than animating through an entire large library. A48dp thumb target leaves the rest of the gallery gestures available, and accessibility supports set-progress. Selection hides the handle; direct user scrubbing interrupts an outstanding initial-position restore. No extra polling/network/model work is introduced. Host mapping tests exercise one million lazy items and10001 seek positions; native gesture/frame acceptance remains pending.

The handle waits until the introduction leaves the viewport, respects measured compact-bar/system safe areas, and cancels pending return-to-item centering on deliberate scrubbing so a late restore cannot pull the user back. Existing user/system animation-duration settings remain respected through AppMotion.


**Phone acceptance pending:** large-tile visual quality, scroll behavior and the owner’s exact native crashes have not been reproduced on Android here. There is no attached device/emulator/KVM. Host coroutine/geometry stress checks do not establish real-device pixel quality, smoothness, Compose/native stability or a permanent fix for every possible crash.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-1.20.37-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/189c23d8ea6130b934a9d439302c088fed137895)

575 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88376302. SHA-256: b9f1f4fb52f57106bcd2608f51e32889bbca21b8278b4afd24895312344a3b7b. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
