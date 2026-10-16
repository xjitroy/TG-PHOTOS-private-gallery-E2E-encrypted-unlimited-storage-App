# Security

Verify official APK checksums and the signing certificate in config/public-signing-cert.sha256. Android rejects compatible updates signed by a different identity. Source publication and R8 obfuscation do not make an APK impossible to modify, copy or reverse engineer.

Cloud backup bytes and catalogs are encrypted/authenticated before upload. Private Vault adds an independent encryption layer. Streaming releases authenticated records only; a verified playback indicator refers to checked chunks, not an invented total security score or a complete-file audit. Password/private-key recovery remains essential. Rooted/compromised operating systems and recipient-created copies are outside any guarantee of complete protection.

Known-key immediate Vault erasure retains its recovery-key gate. The separate forgotten-password route allows the same Telegram account to request scoped deletion after at least 24 server-confirmed hours, with cancellation before START and a fresh server clock proof. It does not decrypt old data. Consequently, control of the Telegram account can eventually authorize destruction even without the encryption password. Malformed or unavailable lifecycle evidence blocks deletion; irreversible-operation and cross-device/native acceptance still need real-device testing.

Do not post encryption/recovery/signing keys, real session databases, passwords or personal files in issues. Prefer GitHub private vulnerability reporting when available; otherwise request a private contact without disclosing exploit details or data publicly. Build, package and host test evidence is scoped; independent key-lifecycle/cryptographic review and real device/account acceptance remain necessary.

The source manifest, pinned dependency/native hashes and bundled third-party notices support inspection. They do not substitute for a dependency vulnerability review. Play Store permission/policy acceptance is separate from direct APK availability.
