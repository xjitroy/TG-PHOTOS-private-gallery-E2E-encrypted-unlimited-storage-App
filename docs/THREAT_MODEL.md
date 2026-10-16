# Threat model and verification boundaries

TG Photos protects cloud payloads, sensitive catalog/local metadata and Private Vault data at rest against service-side inspection, passive interception and tampered disk/network content. Authenticated encryption, scoped access, key separation, bounded decoding and explicit export/delete consent are implementation controls. They do not make a compromised device or a stolen working key set trustworthy.

Current work preserves existing keys, cipher formats, Room schema, package identity and compatible preview signer. The owner authorizes full feasible local/emulator/native verification; earlier Full Verification approval holds are superseded. Evidence still has to be produced on available targets. Source review, JVM tests and signed artifact checks are not an independent security audit or production certification. No destructive real-owner-account tests or recovery-secret logging are allowed.

## Data, keys and access

- Media uses authenticated TVLT1 records; no record's plaintext is delivered before its tag verifies. Argon2id recovery parameters and envelope/file bounds are fixed to limit hostile input costs. Android Keystore wraps retained convenience/session keys; recovery credentials remain the portable recovery mechanism.
- Account/vault/access generations guard authenticated providers/readers and shares. Lock/screen-off revokes cloud playback capabilities and PiP surfaces; actual decoder/OEM behavior needs native evidence. Explicit recipients can retain plaintext already read; a device-original descriptor handed out after sharing consent cannot retract bytes the recipient already obtained.
- Telegram/session/catalog files, key wrappers and offline pins are private and excluded from Android backup/transfer. Development exports exclude these secrets. Losing the recovery credentials and retained keys can make data unrecoverable; OTP alone does not decrypt it.
- Telegram sees traffic/account metadata and opaque storage messages. The app cannot guarantee service retention, internal replica deletion or availability. Users must not delete the app's Telegram storage records unless intentionally removing the corresponding data.

## Temporary storage and explicit exports

Normal viewed media retains bounded ciphertext ranges with LRU/idle-photo and video-age policies. Cache maintenance is opportunistic/scheduled and protects active use; Android process/background scheduling can defer physical cleanup beyond the eligibility time. Original device media is opened directly when available. Catalogs, active upload staging, explicit offline pins, owner exports and Private Vault have separate lifetimes; the temporary-media quota is not a cap on all Android app data.

Normal Cloud export is an explicitly consented **plaintext ZIP** outside the app's encrypted boundary. It uses authenticated source reads, resumable entry checkpoints and saved-file verification before publication. Explicit cancellation retires only an app-created, unpublished URI/name with a matching sealed export ownership proof; published, renamed and unproven legacy files are preserved. Provider/process failure between external file creation and saving that proof can leave an unclaimed partial. If a provider changes the document URI during final rename and the process stops before saving it, a valid ZIP can remain while the job reports a missing tracked destination. Do not infer ownership from a matching filename, overwrite a replacement document or delete unrelated phone documents.

## Independent Private Vault

New Vault media has two independent authenticated record layers and two master-dependent metadata envelopes. Telegram sign-in, the matching Photos-root unlock and a separate Vault password/private key are required. Sensitive file removal/download/delete checks the Vault password; immediate known-key entire-Vault erasure checks the private recovery key. The explicitly acknowledged forgotten-password replacement route instead permits same-Telegram-account scoped erasure after at least24server-hours, with pre-START cancellation and a fresh server clock proof. It cannot decrypt the old vault, but account compromise can eventually authorize destruction without the encryption password. Opaque lifecycle IDs/states are management records, without media names/passwords/content keys. Complete authenticated server read-back precedes original removal, and a reopened, authenticated ZIP precedes Vault Remove. Unsupported or unapproved original deletions are retained.

Vault previews use ephemeral authenticated readers, without the normal app media/thumbnail/offline cache. Private encrypted root/catalog records and temporary TDLib ciphertext buffers remain distinct. App-owned Telegram deletion checks Saved Messages ownership, exact marker scope and expected sizes; full Vault erase also enumerates app-owned orphan parts. Only an acknowledged REVOKED transfer journal authorizes cross-device source-state retry; PREPARED is not permission to delete more data.

Vault downloads use a new random 192-bit ZIP password per export; Remove permits the user's ZIP password after fresh Vault authorization. AE-2 ZIP compatibility does not remove dictionary-attack risk for weak manual passwords. Protected windows/lifecycle locking reduce screenshot/Recents exposure, but cannot prevent a physical camera, hostile privileged input, compromised OS or externally retained exports/backups.

Copied Vault secrets are marked sensitive where supported. A process-managed one-minute expiry survives screen navigation and clears only the same owned clipboard token/content. Android background clipboard restrictions can defer a checked clear until the app resumes; process death prevents that timer. System/keyboard clipboard history and copies retained elsewhere are outside the app's erasure control.

## Remaining assurance and release work

Validate native auth/lock/provider behavior, fresh-device recovery, account round trips, multi-device conflicts, interrupted export/delete boundaries, worker/OEM behavior and memory/performance with synthetic fixtures and isolated accounts. Record actual target/version results and unresolved failures. Root/VPN/runtime signals are local policy safeguards, not antivirus, hardware attestation or a perfect root detector.

Flash wear-leveling, GPU/decoder memory, recipients, external backups, service replicas and a compromised runtime prevent absolute “no footprint” guarantees. Production signing is separate from the compatible preview; no production key/migration decision or independent certification is supplied. See [production release readiness](PLAY_STORE_RELEASE.md).
