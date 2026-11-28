# Private Vault V2.1.0 — Release Policy, Fail-Closed OTA & Zero-Telemetry Privacy Design

---

## 1. Zero-Telemetry & Vault Isolation Guarantee

Private Vault's Control Center integration strictly separates online update/announcement policy from user vault data:

- **What Android NEVER sends to Supabase:**
  - Zero vault entries, titles, passwords, notes, documents, media, checksums, TOTP secrets, or backups.
  - Zero device hardware IDs (`ANDROID_ID`, SSAID, IMEI, MAC, Build serial), zero advertising IDs, zero IP-fingerprinting tokens, and zero analytics/crash SDKs.
  - Zero user accounts or background tracking pings.
- **Anonymous Read-Only Policy Sync (`SupabasePolicyClient`):**
  - Public GET queries read only `client_policy_state`, non-draft `releases`, published `announcements`, and published `feature_flags` using the public publishable key (`sb_publishable_...`).
  - Staged rollout eligibility uses a strictly local `SecureRandom` integer cohort (`0..99`) stored only inside local Android `DataStore` (`ota_rollout_cohort_bucket`) and never transmitted to any server.
- **Vault Crypto & Local Backup Independence:**
  - `online_maintenance`, `ota_checks_enabled`, expired caches, or network errors **never** lock out or alter offline vault unlock, Argon2id/SQLCipher decryption, password/note/media CRUD, TOTP generation, or local encrypted `.pvault` backups.

---

## 2. Fail-Closed V2.1.0+ OTA Update Authorization

Starting in **V2.1.0**, `OtaUpdateManager` enforces `SupabaseOtaPolicyGate` before network fetches and package installation:

1. **No Uncontrolled `/releases/latest/` Fallback:**
   - When Supabase policy is missing offline (empty cache), expired (`> 24h` TTL), or returns HTTP failure without a fresh cache, `checkForUpdates()` returns `OtaState.PolicyDeferred` and makes **zero** fallback requests to GitHub `/releases/latest/download/update.json`.
2. **Exact Release Tag Pinning & Signing Certificate Verification:**
   - Eligible updates fetch only the approved release's immutable tag manifest: `https://github.com/tpeditz/Private-vault-android-v2/releases/download/v<versionName>/update.json`.
   - `SupabasePolicyClient` rejects any policy record whose `manifest_url` leaves the official repository or whose `signing_cert_sha256` differs from `3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70`.
   - After manifest fetch, `OtaUpdateManager` additionally verifies `manifest.versionCode == approvedRelease.versionCode`, `ApkVerifier` v2/v3 APK signature, and SHA-256 package digest.
3. **Staged Rollout, Schedule, Pause & Withdrawal:**
   - Eligibility requires: `state == published`, matching channel (`stable`, or `beta` only if both user `betaChannelOptIn` and server `beta_enrollment_enabled` are `true`), `versionCode > installed`, `currentVersionCode >= minSupportedVersionCode`, active `[starts_at, ends_at]` window, and `cohortBucket < rolloutPercent`.
   - `startDownload()`, `recheckInstallPermissionAndVerifyCandidate()`, and `launchPackageInstaller()` re-check authorization before proceeding. If a previously downloaded and verified APK's release is later set to **`paused`** or **`withdrawn`**, installation is deferred fail-closed (`OtaState.PolicyDeferred`) without corrupting or prematurely destroying valid local state if paused.

---

## 3. Legacy Client Limitation (`<= V2.0.4`)

- Clients running **V2.0.4 or earlier** query `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json` directly and do **not** read Supabase `releases`, `rollout_percent`, `paused`, `withdrawn`, `announcements`, or `feature_flags`.
- Operators should account for this when publishing releases immediately after V2.1.0 launch:
  - Marking a release `paused` or `5%` rollout in Supabase protects **V2.1.0+** clients immediately, whereas **<= V2.0.4** clients will see whichever release holds GitHub's `latest` tag until they upgrade to `V2.1.0+`.

---

## 4. Optional Feedback Privacy Guarantees

- Disabled at build time (`BuildConfig.FEEDBACK_SUBMISSION_ENABLED = false`) and server policy default (`feedback_enabled = false`) until the rate-limited Edge Function is explicitly reviewed, deployed, and enabled.
- Even when enabled in a future build, feedback requires explicit user opt-in consent, payload preview inspection, strict schema limits (`category`, `1..3000` char message, app version/channel, optional contact handle `<= 160` chars), and zero automatic device logs or vault identifiers.
