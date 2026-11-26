# PRIVATE VAULT V2 - IN-APP OTA UPDATE SPECIFICATION & CONTRACT

> **Product:** Private Vault (`V2.1.0`, Version Code `6`)  
> **Schema Version:** `1`  
> **Application ID:** `com.tpeditz.privatevault.v2`  
> **Official Repository:** `tpeditz/Private-vault-android-v2`  
> **Developer:** Developed by Tp Editz (`@Tp_Editz` / `@tp_ediitz`)

---

## 1. Architecture & Endpoints (`V2.1.0+` Control Plane + GitHub Releases)

Private Vault V2.1.0 pairs a fail-closed anonymous **Supabase Policy Control Plane** with immutable tagged **GitHub Releases** binaries over HTTPS.

### Policy Control Plane Endpoints (Supabase Public REST — Anonymous Read-Only)
- `GET /rest/v1/client_policy_state?select=singleton,revision,updated_at&singleton=eq.true`
- `GET /rest/v1/releases?select=id,version_name,version_code,channel,priority,state,rollout_percent,release_notes,manifest_url,apk_sha256,apk_size_bytes,signing_cert_sha256,verified_at,available_from,available_until,created_at,updated_at&order=version_code.desc`
- `GET /rest/v1/announcements?select=id,title,message,presentation,target_channel,min_version_code,max_version_code,state,repeat_mode,starts_at,ends_at,created_at,updated_at&state=eq.published&order=starts_at.desc`
- `GET /rest/v1/feature_flags?select=flag_key,boolean_value,integer_value,is_published,updated_at&is_published=eq.true`

Zero user credentials, vault entries, filenames, or hardware IDs are ever transmitted.

### GitHub Release Artifact Endpoints
- **Immutable Versioned Manifest URL (V2.1.0+ Approved Target):**  
  `https://github.com/tpeditz/Private-vault-android-v2/releases/download/v<versionName>/update.json`
- **Immutable Versioned APK URL (`apkUrl` inside `update.json`):**  
  `https://github.com/tpeditz/Private-vault-android-v2/releases/download/v<versionName>/private-vault.apk`
- **Legacy Canonical Endpoint (`<= V2.0.4` Clients Only):**  
  `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json` (`BuildConfig.UPDATE_MANIFEST_URL` preserved for backward build tests; V2.1.0+ never falls back to `/releases/latest/` when policy verification fails).

GitHub redirects are constrained strictly to `github.com`, `raw.githubusercontent.com`, `objects.githubusercontent.com`, and `release-assets.githubusercontent.com`.

---

## 2. Schema Version 1 Manifest Contract (`update.json`)

The release workflow generates `update.json` using [`scripts/generate_ota_manifest.py`](file:///scripts/generate_ota_manifest.py) directly from the verified signed `private-vault.apk`:

```json
{
  "schemaVersion": 1,
  "applicationId": "com.tpeditz.privatevault.v2",
  "versionCode": 6,
  "versionName": "2.1.0",
  "apkUrl": "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.0/private-vault.apk",
  "sha256": "3bee0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e",
  "sizeBytes": 16777216,
  "publishedAt": "2026-11-26T12:00:00Z",
  "releaseNotes": "Admin Control Center OTA policy integration and reliability hardening.",
  "signingCertSha256": "3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70"
}
```

### Manifest & Backend Parity Rules (V2.1.0+)

| Check | Backend (`release-control`) | Android (`SupabaseOtaPolicyGate` + `OtaUpdateManager`) |
| --- | --- | --- |
| Manifest URL | Exact `v<version_name>/update.json` | Exact match against Supabase `releases.manifest_url` |
| APK SHA-256 | Streamed full-binary SHA-256 vs `update.json` stored in `releases.apk_sha256` | Verified `update.json.sha256 == releases.apk_sha256` AND local file SHA-256 |
| APK Size | Exact streamed byte count stored in `releases.apk_size_bytes` | Verified `update.json.sizeBytes == releases.apk_size_bytes` AND local file size |
| Package Identity & Version | `applicationId == com.tpeditz.privatevault.v2`, exact `versionName` & `versionCode` | Cross-checked in `update.json` AND `PackageManager.getPackageArchiveInfo()` |
| Signing Certificate | Checked in `update.json` + APK Sig Block v2/v3 magic in binary stream | `ApkVerifier` v1/v2/v3 full cryptographic cert match vs permanent cert & installed signers |

---

## 3. Fail-Closed V2.1.0 Authorization & Fresh Policy Separation

1. **Fresh Sync Required for Release Actions:**
   - Offering updates (`checkForUpdates`), starting downloads (`startDownload`), re-checking pending updates (`recheckInstallPermissionAndVerifyCandidate`), and launching the Android installer (`launchPackageInstaller`) MUST receive a fresh HTTPS response (`PolicySyncResult.Fresh`) from Supabase.
   - Cached policy (`control-policy-cache.json`) is used only for offline UI display metadata (announcements, last known flags) and **never** authorizes release downloads or installations after a failed network refresh.
   - When network or policy service refresh fails, Android defers safely (`OtaState.PolicyDeferred`) with: *"Unable to verify the update right now. Please try again later."*
2. **Staged Rollout, Channel Opt-In, Schedule, Pause & Withdrawal:**
   - Eligible updates require `state == 'published'`, non-null `apk_sha256`/`apk_size_bytes`/`verified_at`, active `[available_from, available_until)` window, matching channel (`stable` always; `beta` only when both device `betaChannelOptIn` and server `beta_enrollment_enabled` are `true`), and `localCohortBucket (0..99) < rollout_percent`.
   - When a previously downloaded & verified APK's release is later marked `paused`, installation is blocked cleanly (*"This update is temporarily unavailable."*) while keeping the verified APK file intact in `filesDir/ota_updates/`.
3. **Offline Vault Independence:**
   - Policy failures, network outages, maintenance mode, or paused rollouts never interfere with offline vault unlock, encryption, search, TOTP generation, or local backups.

---

## 4. Official Signing Certificate Fingerprints (Permanent)

- **Key Alias:** `privatevault`
- **Certificate SHA-1:** `32:19:54:C3:81:B4:A7:9D:76:B8:90:FF:05:A3:18:D0:21:FB:AD:FC`
- **Certificate SHA-256:** `3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70`
