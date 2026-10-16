# Privacy and data storage

TG Photos runs on Android and uses your Telegram account's Saved Messages to store application-encrypted backup attachments and authenticated encrypted catalogs. Telegram authentication and transport require account/session information under Telegram's own service policies. TG Photos does not operate an additional hosted storage backend. No advertising/analytics SDK is included in the declared app dependencies.

Photos, videos, audio and documents are read only through Android permissions, selected-file grants or explicitly enabled shared-storage access. Automatic backup applies to folders you enable. Names, original dates, media facts and available embedded metadata are preserved inside encrypted metadata/files. Clean/category filters change the view, not backup eligibility or originals.

Normal cloud-only temporary media remains ciphertext on disk with a configurable size ceiling and adaptive expiry. Device originals open directly. Intentional offline downloads and explicit exports/shares are separate. Export/share grants can create readable copies outside the app; choose recipients and destinations carefully. Android/OEM scheduling and free space affect physical cleanup timing.

Private Vault has a separate password/recovery key, nested cloud encryption and ephemeral viewing. Optional biometric access stores a per-use hardware-authentication-bound encrypted key wrap only on this phone. Biometric enrollment changes, reinstall or phone changes require password/private-key recovery. Sensitive file actions retain fresh password/private-key requirements. No fingerprint image is collected or stored by TG Photos.

**Keep TG Photos attachments, recovery/catalog messages and Saved Messages in Telegram.** Deleting them there can permanently lose data. App-managed deletion targets app-owned Telegram copies; physical retention inside Telegram or copies created elsewhere cannot be guaranteed erased by this app. Keep recovery material privately. Clearing Android app data/uninstalling removes local keys/convenience state and can make unrecoverable data inaccessible.

Source and artifacts here do not include owner media, sessions, signing or user encryption keys. A functioning Telegram client APK necessarily contains its client API identity, which is different from account or encryption secrets. For issues, never upload personal media, session databases, recovery phrases or full sensitive logs.
