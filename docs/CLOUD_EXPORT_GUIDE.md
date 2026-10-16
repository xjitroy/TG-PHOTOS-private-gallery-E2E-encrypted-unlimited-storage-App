# 0.18.9 export safety update

Completion-notification taps open Export Cloud library/history after normal authentication/readiness and close existing viewer/profile overlays. Explicit cancellation stops the writer then retries cleanup of the exact app-created unpublished partial whose ownership is sealed with the export. Filename alone never authorizes adoption/truncation/deletion; completed and unrelated files remain. Interrupted exports retain resume state. A provider crash between creation and proof persistence can leave an unclaimed orphan that is intentionally preserved. Full feasible testing is authorized; native provider/process/notification acceptance still needs a usable runner. Earlier export instructions follow.

# Export Cloud library / ক্লাউড লাইব্রেরি এক্সপোর্ট

1. Open Settings → Export Cloud library (also available in the main overflow).
2. Choose Everything, Camera photos, Screenshots or Videos only; refine with multiple file types and Categories if needed. Multiple types/categories are OR choices within each group, and type + category + date must all match.
3. Choose All time or From → To, then Original date or Backup date. Choose dates has Start and End fields plus Last 7 days, Last 30 days and This month presets. Tap either field for the phone’s native single-date calendar, then Apply the complete range; Cancel preserves the previous selection. Both selected local calendar days are included. Backup date means when the backup completed, not when the photo was taken. Unknown older completion dates are included only in All time.
4. Leave the default Downloads / TG Photos destination on Android 10+. No broad storage permission or folder picker is required. On Android 8/9, choose a writable local Downloads/phone folder once; the app reuses its valid grant. Choose another folder is optional. Keep sufficient free phone storage for originals and ZIP overhead.
5. Tap Start background export, review the normal unencrypted ZIP disclosure and confirm. You may leave the app while Android permits background work. Preparation refreshes the Cloud index before freezing the final selection, so the on-screen count is a preview. Do not force-stop the app; network, unlock and Android battery/foreground restrictions can delay work.

বাংলা: Settings → ক্লাউড লাইব্রেরি এক্সপোর্ট খুলে কী এক্সপোর্ট করবেন, সময়সীমা ও সেভ করার জায়গা বাছুন। এরপর ব্যাকগ্রাউন্ড এক্সপোর্ট শুরু করে ZIP কপির সম্মতি দিন। Android 10+ ফোনে যাচাই শেষে Downloads/TG Photos-এ ZIP নিজে সেভ হবে। পুরোনো Android-এ একবার ফোনের ফোল্ডারের অনুমতি দিতে হবে। অ্যাপের মূল ক্লাউড ব্যাকআপ অক্ষত থাকবে।

## Last successful export and history

The top card shows the last successfully saved export, its completion date/time, selected types/categories/date axis and From/To window, actual known file coverage and file count/original bytes. All time shows actual known coverage when available. Older history created before coverage was recorded can still show its selected range. Unknown dates are not invented. Use these filters copies the selection; it does not automatically create another export.

Export history keeps all requests, including queued/paused/failed/cancelled ones. Expand a record for the exact ZIP name, requested/saved times, known coverage/unknown-date count and destination. Open ZIP uses a phone ZIP app; Retry resumes after complete-file checkpoints. Cancel removes only app-owned unpublished Downloads output; a custom-folder .partial.zip may need manual cleanup. Already published ZIPs and Cloud originals are not removed by Cancel.

Completion creates an Android notification when allowed, and an in-app Cloud popup when the Cloud UI is visible. Notification denial does not prevent saving or the history/popup.

## ZIP contents and names

Example: TG-Photos_Cloud_20261015_070809Z_Photos_Cam_original-20261001-20261015_123files_456789B_12345678.zip

- UTC request/export-start date and time: 2026-10-15 07:08:09Z. The actual saved/completed time is in history.
- Types and category tokens: Photos, Videos, Audio, Files; Cam=Camera, Docs=Documents, Shots=Screenshots, ScreenRec=Screen recordings, IG=Instagram, WA=WhatsApp, TG=Telegram, FB=Facebook, Downloads, PNG, Other; All means no restriction in that group.
- Original/backup date axis and inclusive From/To calendar dates use the timezone saved with your selection. All-time names also show known coverage; unknown times stay unknown.
- File count, total original bytes (B, before ZIP overhead) and unique suffix. All combinations fit common filename limits without dropping categories.

Original quality and embedded metadata are unchanged. _TG_Photos/export.json preserves exact millisecond original/backup/device times, sizes/hashes/names/album and known media/location metadata, request/preparation times, selected filters/timezone and actual coverage. Numbered subfolders keep duplicate original names separate. ZIP64 permits large files/archives; your phone storage and ZIP reader must support the resulting size.

This exports current normal Cloud originals, including hidden/Clean-filtered files. Pending uploads, Trash and independent Private Vault data are excluded. Normal ZIP is a decrypted copy, readable by anyone with access; keep it private. Private Vault retains its separate password-confirmed encrypted ZIP export. TG Photos verifies each original and the saved ZIP before marking completion. Native owner-phone/provider/process/notification acceptance and Full Verification are still pending; this development preview is not a production verification claim.
