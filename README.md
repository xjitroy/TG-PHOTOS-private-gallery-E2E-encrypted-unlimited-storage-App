# TG Photos

Native Android photo gallery, encrypted Telegram backups, media player, file management and an independent Private Vault. Android8.0+; universal APK for ARM64/ARMv7/x86/x86_64.

[Download public APK](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/apk-downloads/public-0.19.0) · [Build source](BUILD.md) · [Privacy](PRIVACY.md) · [Security](SECURITY.md)

Photos, Albums, Files and Vault share a native Kotlin/Jetpack Compose interface with Glass/Material You options. Verified device originals open directly. Cloud media streams as authenticated encrypted ranges with bounded temporary storage. Background folder backup, manual-priority uploads, restore-safe content dedup, category/date ZIP export and media editing are included.

Private Vault uses a separate password/recovery key and nested cloud encryption. Optional fingerprint/strong-biometric access is enrolled with the Vault password and bound to this phone's Android Keystore. Password/private-key confirmations remain required for sensitive operations. Vault media and visible gallery artwork use ephemeral RAM and do not enter the normal disk cache.

**DO NOT DELETE the TG Photos attachments, recovery/catalog messages or Saved Messages chat in Telegram.** Those messages contain your backups. Use TG Photos to manage deletion. Keep your passwords and recovery keys privately; uninstalling or clearing data removes local recovery convenience and biometric enrollment.

The public distribution is `app.televault.photos`, version0.19.0, signed with the certificate pinned in config/public-signing-cert.sha256. Official future APK updates require the same private signing identity. Public source can be inspected/forked and APKs can be reverse engineered; code visibility/obfuscation does not prevent every modification. Other users cannot push to this repository merely because it is public. Verify the certificate/checksums before installation.

This repository contains a sanitized snapshot of the same app's current executable source, not personal development history or owner backups. No private signing/session/recovery/encryption keys or client source credential values are included. Telegram client API identity necessarily exists in a working APK and must not be treated as a server secret. Supply your own client configuration when building.

Automated host/build/artifact checks are reported with each release. Actual phone/OEM/biometric/provider/codec/background/account acceptance and independent security review are separate; this workspace has no usable Android target. This normal public APK is not a claim of independent production certification. See docs/PRODUCTION_READINESS.md.

Third-party source, notices and license terms are in docs/THIRD_PARTY_SOURCES.md and licenses/. Publishing project source does not select a new permissive redistribution license; preserve the applicable rights and notices. No upstream complete gallery application or branded UI was copied.
