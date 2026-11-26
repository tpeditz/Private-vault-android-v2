# Private Vault V2.1.0 — Owner Android Device Verification Checklist

Run these manual tests on a physical Android device after signing `v2.1.0` (`versionCode = 6`) with your permanent release key (`PrivateVault` keystore).

## 1. Upgrade Continuity from V2.0.4 -> V2.1.0
- [ ] Install signed **V2.0.4** (`versionCode = 5`) on the test device and create: encrypted notes, passwords (with password history), TOTP entry, media + album, PDF document, decoy vault (if used), and local `.pvault` backup.
- [ ] Upgrade directly to signed **V2.1.0** (`versionCode = 6`) over V2.0.4 (do **not** uninstall first).
- [ ] Unlock with V2.0.4 master password (and verify biometrics if enrolled).
- [ ] Confirm ALL notes, credentials, media, documents, TOTP codes, and settings survived intact.

## 2. Offline Vault & Fail-Closed OTA Behavior
- [ ] Turn on **Airplane Mode** (no internet).
- [ ] Unlock vault -> Confirm notes, passwords, search, TOTP codes, and local encrypted backup/restore work at full speed with zero network dependency.
- [ ] Open **Settings -> App Updates -> Check for Updates** while offline -> Confirm it safely defers with **"Unable to verify the update right now. Please try again later."** and never crashes or falls back to unapproved downloads.

## 3. Supabase Policy, Staged Rollouts, Pause & Withdraw (Signed Test Release `v2.1.1+`)
- [ ] Connect to internet. Confirm **App Updates** displays **`Beta release channel (opt-in)`** toggled OFF (or disabled notice while `beta_enrollment_enabled = false`).
- [ ] Publish a test higher signed version (`versionCode = 7`) in Supabase Admin Control Center at **`100%` rollout**:
  - [ ] Tap **Check for Updates** -> Confirm update prompt displays channel (`Stable`) and priority badge (`Recommended` / `Critical`) cleanly.
  - [ ] Start download -> Verify progress bar, download, SHA-256, and `ApkVerifier` checks complete.
  - [ ] If Unknown Sources permission is off: tap **Open Settings**, grant permission, press Back -> Confirm app returns cleanly to **Ready to Install** without re-downloading.
- [ ] **Pause Handling:** While APK is verified on disk (or before download), mark that release **`paused`** in Admin Control Center -> Tap **Check for Updates** / **Install Now** -> Confirm installation blocks safely with **"This update is temporarily unavailable."** and keeps the downloaded APK intact until resumed.

## 4. Announcements & Remote Feature Flags
- [ ] Publish a **`banner`** announcement targeting `all` (`repeat_mode = once`) -> Unlock vault -> Confirm banner appears on Home above Vault Health; dismiss it, lock & re-unlock -> Confirm it stays dismissed.
- [ ] Publish a **`dialog`** announcement (`repeat_mode = repeat`) -> Unlock vault -> Confirm AlertDialog displays plain text safely (no HTML execution); dismiss, lock & re-unlock -> Confirm it re-appears per `repeat` policy.
- [ ] Toggle `online_maintenance = true` in Admin Control Center -> Confirm Home, **App Updates**, and **Google Drive backup** display the maintenance notice, while offline vault unlock, editing, and local backup remain 100% functional.

## 5. Feedback Screen Verification
- [ ] Open **Settings -> Send Feedback** while disabled -> Confirm banner explains **"Feedback submission is disabled pending server endpoint deployment."** and Send remains disabled.
- [ ] Enter category, message, check explicit consent, and tap **Preview** -> Confirm only category, version, message, and optional contact appear (zero vault items, zero keys, zero hardware IDs).

## 6. Post-Install Security Sanity
- [ ] Verify **Android Settings -> Apps -> Private Vault -> Autofill** shows NO Autofill service offered (confirming V2.0.4 removal preserved).
- [ ] Verify Screen-Off Auto-Lock and Screenshot Protection remain active after upgrade.
