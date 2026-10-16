# Production release readiness — TG Photos

Current development: **0.18.9-preview / code 28**. Native Kotlin/Compose login, encrypted backup/recovery, media playback, Files, Private Vault, configurable temporary storage and Cloud ZIP exports are implemented. This is not a production certification or an independent cryptographic audit. A successful build alone does not establish production readiness.

The owner now authorizes full feasible local/emulator/native verification. Earlier separate Full Verification approval holds no longer apply to this work. Missing device/account evidence is an availability and acceptance gap, not an assumed permission refusal. Use synthetic files and isolated test profiles; never destroy real owner data or log recovery secrets. Existing private GitHub delivery is authorized; Play Console submission is not.

## Signing and safe upgrades

- Keep `app.televault`, existing vault/file formats, Room schema, retained keys and installed-app signing identity compatible. Do not uninstall or clear user data to resolve a signer mismatch.
- `hardened` is the compatible development preview: non-debuggable, minified/shrunk, with readable crash locations and the existing private developer signer. Its certificate is not silently promoted to production.
- `release` uses production R8 obfuscation and a separate production signing configuration. `validateReleaseInputs` fails closed when Telegram client configuration or required production keystore/alias/password inputs are missing or the known development certificate is supplied. Secrets stay outside Git/source/chat/exports.
- No production signing credentials, key-custody plan, Play App Signing enrollment or installed-preview migration decision have been supplied. A different signer cannot simply replace the existing installation. Resolve the upgrade/signing plan before a production package is distributed.

## Evidence required before production approval

1. Record the final source commit, exact dependency/native provenance, mandatory JVM/compile/ktlint/lint checks, release signing validation and APK/AAB manifest/signature/ABI/16 KB alignment checks. Review current dependency/CVE/license findings; pinned artifacts and hashes are not a complete vulnerability audit.
2. Exercise isolated Android targets across the supported API 26–36 range, small/large screens, dark/light Glass and Material You, font scaling, cutouts, rotation, permission denial and upgrade paths. Measure startup, scrolling, playback and memory rather than promising all-device speed.
3. Validate actual authentication/lock revocation, provider/grant lifetimes, password/private-key recovery and restore, tampered/wrong-key rejection, byte-identical cloud round trips, multi-device state conflicts and verified-before-delete behavior. Use a dedicated test Telegram account for account integration; no destructive owner-account tests.
4. Test worker/process death, offline/mobile/Wi-Fi transitions, device lock, reboot, Android/OEM background limits, foreground transfer timeouts, low storage and large files. Verify export retry/cancel/publication/history and cache eviction while readers/uploads are active.
5. Review crypto/Keystore/recovery independently where required by the release risk assessment. Separate source/JVM/static artifact evidence from phone/provider/account acceptance; publish unresolved gates with the candidate.

## Store and legal work

Provide the Play developer account, support contact, legal/controller identity, hosted privacy policy, accurate Data Safety answers, screenshots, listing text and reviewer access instructions. Review Telegram API Terms, retention/service limits, licenses and affiliation wording. Do not advertise unlimited retention, guaranteed secure erasure or recovery by OTP.

Review current Play policy for the Files browser's `MANAGE_EXTERNAL_STORAGE` use and media/notification/foreground-service permissions. Prefer Photo Picker/SAF where appropriate; document the actual all-files browser use and request only permissions needed for the chosen operation. Implementation of a permission does not establish store approval.

Disclose direct transfers through the user's Telegram account, encrypted local state and temporary ciphertext, deliberate offline copies, normal plaintext exports, password-encrypted Vault ZIPs, deletion/retention limits and recovery responsibilities. Keep development source/workspace backups private; they are not a backup of the user's Telegram media or recovery credentials.
