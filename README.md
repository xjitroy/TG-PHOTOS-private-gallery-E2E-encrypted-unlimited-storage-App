# TG Photos

Native Android photo gallery, encrypted Telegram backups, media player, file management and an independent Private Vault. Android8.0+; One Universal APK includes ARM64, ARMv7, x86 and x86_64 with the same app and signing identity.

[Download public APK](https://github.com/xjitroy/TG-PHOTOS-private-gallery-E2E-encrypted-unlimited-storage-App/tree/apk-downloads/public-0.19.10) · [Build source](BUILD.md) · [Privacy](PRIVACY.md) · [Security](SECURITY.md)

Photos, Albums, Files and Vault share a native Kotlin/Jetpack Compose interface with Glass/Material You options. Verified device originals open directly. Cloud media streams as authenticated encrypted ranges with bounded temporary storage. Background folder backup, manual-priority uploads, restore-safe content dedup, category/date ZIP export and media editing are included.

Private Vault uses a separate password/recovery key and nested cloud encryption. Optional fingerprint/strong-biometric access is enrolled with the Vault password and bound to this phone's Android Keystore. Password/private-key confirmations remain required for sensitive operations. Vault media and visible gallery artwork use ephemeral RAM and do not enter the normal disk cache.

**DO NOT DELETE the TG Photos attachments, recovery/catalog messages or Saved Messages chat in Telegram.** Those messages contain your backups. Use TG Photos to manage deletion. Keep your passwords and recovery keys privately; uninstalling or clearing data removes local recovery convenience and biometric enrollment.

The public distribution is `app.televault.photos`, version0.19.10, signed with the certificate pinned in config/public-signing-cert.sha256. Official future APK updates require the same private signing identity. Public source can be inspected/forked and APKs can be reverse engineered; code visibility/obfuscation does not prevent every modification. Other users cannot push to this repository merely because it is public. Verify the certificate/checksums before installation.

This repository contains a sanitized snapshot of the same app's current executable source, not personal development history or owner backups. No private signing/session/recovery/encryption keys or client source credential values are included. Telegram client API identity necessarily exists in a working APK and must not be treated as a server secret. Supply your own client configuration when building.

Automated host/build/artifact checks are reported with each release. Actual phone/OEM/biometric/provider/codec/background/account acceptance and independent security review are separate; this workspace has no usable Android target. This normal public APK is not a claim of independent production certification. See docs/PRODUCTION_READINESS.md.

Third-party source, notices and license terms are in docs/THIRD_PARTY_SOURCES.md and licenses/. Publishing project source does not select a new permissive redistribution license; preserve the applicable rights and notices. No upstream complete gallery application or branded UI was copied.

## Restore and vault management

Photos retain thumbnails without a loading icon, and short videos avoid buffering overlays. The newest verified cloud page appears first while a background worker restores the remaining catalog and embedded thumbnails, with real progress in the header. Selected photos take priority over speculative warming; encrypted cache limits and direct device-original playback remain.

Optional encrypted vault names and independent vault selection preserve existing data. A forgotten-password Private Vault replacement explicitly schedules deletion of its old vault after at least24server-hours. Photos offers optional delayed deletion. Cancellation must reach Telegram before START; it survives reinstall. This route cannot decrypt old content. Read PRIVACY.md and SECURITY.md for account-authorized deletion and remaining acceptance limits.

Photos includes phone-local date-based Memories cards and floating, scroll-aware controls. Private Vault artwork uses bounded mutable RAM images with renderer-safe cleanup; it never writes Vault thumbnails to disk. Optional face/text analysis runs on this device; no photo is sent to an AI analysis provider.

0.19.4 adds optional on-device People groups with encrypted named-reference recovery, reviewable matches and co-occurrence Memories; universal name/person/date/type search; and optional eight-language photo OCR with an encrypted local text/word index. The language pack downloads once (28.5MB). Hold a normal photo for three seconds to select words, or use Text to see/copy all text. Lens requires confirmation before external sharing. Private Vault excludes faces, OCR and Lens. Model inference accuracy/native gestures are not certified by host tests.

0.19.4 focuses on People review/merge/per-photo tags and selective photo text copying. Reviews update encrypted matching references and rejected-pair constraints; ordinary reopen reuses content-based scan results. Person profiles show a photo grid with alone/together filters. Public delivery is one signed universal APK containing all four ABIs.

0.19.5 repairs cold-start session presentation, guards unavailable OCR search/photo previews, bounds native inference concurrency and loads OCR language groups sequentially. Existing encrypted Telegram sessions/data are preserved. An affected-phone force-stop/OTP regression has not been reproduced here; session diagnostics are available without storing authentication secrets.

0.19.6 scans original device photos with overlapping face-detection windows, persists unchanged tags and retries older zero-face thumbnail scans once. Photo OCR combines page/sparse layouts and word confidence for small/mixed-script labels. Local photos can be indexed offline after language download; cloud analysis waits for unmetered access. Select “This is me” in a person’s profile to prioritize personal Memories. Names, references, membership hashes, review decisions and this preference sync as authenticated encrypted People metadata; Memories are rebuilt locally from these inputs and restored encrypted media dates. Original photos need not be reuploaded just to change a name. Private Vault stays excluded.

0.19.7 replaces People inference with YuNet + SFace and a one-time verified39MB in-app download (Albums → People → Download & start). Existing names/confirmed tags and encrypted recovery stay; legacy references are converted without mixing model spaces. Strong automatic duplicates consolidate, uncertain single-photo groups remain unsorted, and review dialogs start on demand. Reviews improve reference examples rather than model weights. Original photos are not reuploaded just to migrate face metadata.

Photos-style scroll-away introductions and reverse-scroll compact controls now cover Albums, Files/folders and the Private Vault gallery. Filter changes stay within the current folder. English is the default app UI language, including when Android uses Bengali; an explicitly chosen per-app OS locale is respected. Face/OCR/Lens remain excluded from Private Vault. Device/OEM/gesture/model accuracy acceptance remains pending.

0.19.8 fixes transparent Search/People pages and date/filter collisions. Search results use the Photos gallery grid with categories, date grouping and zoom; Everyone removes person-only constraints and Clear all removes every search filter. Names have an explicit Save dialog. Settings → People & text search provides a confirmed face reset, preserving names/manual decisions by default. Automatic encrypted face/OCR indexing uses background WorkManager batches while Photos is unlocked; lock/force-stop pauses it, with no new key storage or unlock bypass. Private Vault remains excluded. Actual Android rendering/background execution and the prior affected-phone OTP report still need phone acceptance.

0.19.9 separates immediate photo transforms from coalesced high-resolution decoding and uses layer-only normal video/Vault-photo zoom. Grid density changes only at column boundaries. Vault fingerprint completion now uses post-authentication AAD and one terminal callback, retaining the same hardware-bound keys/envelope and password fallback. Foreground locks remain. Actual phone zoom performance and biometric re-entry are unverified; no FPS guarantee.

0.19.10 gives photo/PDF/Details/private-preview reads independent process-only cancellation ownership. Closing a viewer cancels its cloud reads; stale opens and size/read callback cancellation fail as file errors. Abandoned decoder/descriptor/bitmap cleanup stays off the UI thread. Details reuses stored cloud capture facts before probing originals. Private Vault stays RAM-only, bound to its current authorized epoch and excluded from AI. The exact affected-phone crash/ANR remains unverified without Android target/logs.
