# TG Photos 1.20.34 — public Universal APK

One signed APK contains ARM64, ARMv7, x86 and x86_64. Android8+. Same public package/signing identity; install over the prior public app without clearing data. No personal APK is included.

Settings → Player separates default-on Resume last location from Return to the viewed item. The first controls reopening a normal tab/folder; Off starts at Photos next launch without moving the current screen. The second controls centring/highlighting after closing photo, video, audio, document and file viewers, including Search. Both preferences are read before the first bookmark is applied. Saved activity location fields cannot bypass the disabled startup option. Private Vault never reopens.

Return scrolling finishes with a soft220–300ms native glide (before the existing app/system animation scale) and a gentler2.6-second highlight. Distant lists and tall mosaics animate at most80percent of one viewport rather than traversing thousands of items. A manual drag wins; missing/unmeasured targets retain the normal folder fallback without a false highlight. This does not eliminate provider/network/Keystore cold-start latency.

Fresh backup setup asks Limited data or Full backup with an explicit unlimited-internet choice. Limited is selected by default, with Camera selected after photo/video permission:250MB/day of mobile photo and limited Camera-video uploads, photos before queued automatic videos, and Camera videos up to2minutes on mobile data. Wi-Fi keeps full-length automatic video backup. Settings allow another allowance/duration/priority with an explicit Save/Saving/Saved confirmation. Existing saved settings/folder grants stay unchanged. Other automatic folders retain their previous video/Wi-Fi choices. Explicit manual Upload ignores app data/duration restrictions for every file type; authentication, grants and transport errors still apply.

Each Allow extra today action adds200MB, accumulates for the local day, and resets at the next local day. Allowance changes wake the existing bounded queue once instead of creating one worker per blocked file. Pending automatic work has a one-off next-day wake rather than repeated polling. Shared attempted-send accounting covers limited Camera videos as well as photos and checks transport changes between encrypted pieces. Explicit Videos on mobile data keeps unlimited video size/duration/data behavior after acknowledgment; photos keep their separate allowance. The separate default-off No limit setting removes daily and Camera-duration limits after unlimited-internet confirmation, and can be disabled again. Chosen folders, Android permissions, Data Saver, battery25/15percent rules and existing automatic-video notices remain. Unlimited mode does not select every device folder.

Fully viewed Memories subtly dim their cover, retain readable text and show Viewed. A story must actually display every entry; opening/skipping the last page is insufficient. Small bounded normal-vault-authenticated device-local watch receipts survive restarts; changing story membership/content makes it new again. Private Vault remains excluded. No images are blurred or sent to an AI server for this status.

Shared Glass surfaces receive more distinct directional rims, size-cached lens lighting and short interactive light/press transitions. Floating chrome stays translucent and thumbnail-strip gaps stay transparent. Material You and Light/Dark/System continue through the shared roles. No backdrop screenshot, bitmap blur, idle animation loop or Apple private renderer is introduced; no pixel-perfect iPhone equivalence is claimed.

Compatible public Universal1.20.34/code46 only; same package/signer, Android8+, all4ABIs. Preserve all previous features, artifacts, ciphers, keys, Room1, session paths and pinned models. No owner account operations, data reset or uninstall. Native Android/OEM/biometric/gesture/provider/frame acceptance remains unavailable in this runner; host tests and artifact checks are not phone acceptance or a permanent no-crash guarantee.


First-use feature tour precedes the separate Telegram-file warning. Ten animated, scrollable bilingual cards explain the actual supported cloud/encryption/Vault/backup/on-device text/People/Memories/search/player/file/theme features without claiming Google parity. Each Next/Continue consumes at least one real foreground second; fast double taps, stale page callbacks and background time cannot skip cards. Existing warning acknowledgements migrate without forcing a new tour; Help offers replay. Save failures stay recoverable. The later gallery/setup prompt explicitly asks whether to enable Camera backup before showing its plan choices and requesting Android permissions. Browsing alone never enables backup.

The Backup status card offers Turn on when automatic backup is off, using the same permission/plan flow. Its enabled state points to Settings → Backup & Sync to manage/turn off. Download/export code has no new daily data allowance: these changes govern automatic uploads only, with explicit manual Upload exempt. Existing rate/space/network/permission/platform constraints remain.

The first tour points to Next with a small finite animated hand. Supported repeated failed gestures/permission requests/audio edits/tab Back actions reuse the existing two-failure guide admission,30-second cooldown, at most three hints per topic/Drive,6.5-second dismissal and Settings opt-out. Hand motion is finite and respects app/system animation scaling. It observes known failures without intercepting valid gestures, logging arbitrary taps or inferring every possible user mistake.


**Phone acceptance pending:** large-tile visual quality, scroll behavior and the owner’s exact native crashes have not been reproduced on Android here. There is no attached device/emulator/KVM. Host coroutine/geometry stress checks do not establish real-device pixel quality, smoothness, Compose/native stability or a permanent fix for every possible crash.

**Affected-phone check still required:** the owner's repeated-OTP event was not reproduced here. A TDLib request for new sign-in is never bypassed by a local preference. Local diagnostics show auth stage, presence of session files and Android exit reason without phone numbers, OTPs or keys. No data reset or new authorization request is performed automatically.

[Download Universal APK](TG-Photos-1.20.34-universal.apk) · [Editable source](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/62ee718b667433234e379308f65701384559bdac)

527 host JVM tests, eight local checks and lint with zero errors pass. Signature, all four native ABIs and frozen source checkpoint verified. Real phone/OEM/gesture/model/account testing remains pending; these checks are not production certification or a Google model accuracy claim. Source visibility cannot prevent all modifications; Android signatures protect official compatible updates.

Bytes: 88338966. SHA-256: 5b5ad33f00f09728113526068dc7df7ebe7aeb5a9f32827a424c8283af1804bc. Signer: 3e7c1029cb5e2da80026758abd483b5bd7f0551b6a56bee6b21640b8ae774559.

Keep your Telegram Saved Messages backup/recovery/catalog attachments and passwords. Do not delete those messages. No private signing/recovery/session/encryption keys or private workspace history are published here.
