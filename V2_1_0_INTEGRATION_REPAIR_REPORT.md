# Private Vault V2.1.0 — Integration Repair Report

---

## 1. Executive Summary

Following the independent code review of the initial V2.1.0 implementation, all critical Android-to-Supabase schema mismatches, backend RPC invocation boundary issues, release artifact verification gaps, fresh-vs-cached policy reliability bugs, announcement dialog/repeat gaps, and feedback submission gaps have been repaired and verified locally without touching the live production database or pushing/publishing anything.

---

## 2. Defects Discovered, Root Causes & Corrections

### Defect 1 — Android Supabase Schema Incompatibility (Release Blocker)
- **Root Cause:** `SupabasePolicyClient.kt`, `ControlPolicyModels.kt`, `PolicyCacheStore.kt`, and `PolicyEvaluator.kt` previously queried non-existent columns (`client_policy_state.id`, `latest_stable_version_code`, `latest_beta_version_code`, `active_flag_version`; `releases.apk_url`, `min_supported_version_code`, `starts_at`, `ends_at`, `paused_at`, `withdrawn_at`, `priority = normal/important`; `announcements.body`, `severity`, `display_mode`, `is_published`; `feature_flags.ota_checks_enabled`, `ota_check_interval_hours`) against a foundation schema that defines exact different column names and constraints.
- **Correction:**
  - Inspected authoritative foundation SQL ([20261126120731_private_vault_v210_control_center_foundation.sql](file:///supabase/migrations/20261126120731_private_vault_v210_control_center_foundation.sql)) and aligned 100% of models, REST queries, JSON serialization, and UI state:
    - `client_policy_state`: `singleton`, `revision`, `updated_at`
    - `releases`: `id`, `version_name`, `version_code`, `channel`, `priority` (`optional` | `recommended` | `critical`), `state` (`draft` | `published` | `paused` | `withdrawn`), `rollout_percent`, `release_notes`, `manifest_url`, `apk_sha256`, `apk_size_bytes`, `signing_cert_sha256`, `verified_at`, `available_from`, `available_until`, `created_at`, `updated_at`
    - `announcements`: `id`, `title`, `message`, `presentation` (`banner` | `dialog`), `target_channel` (`all` | `stable` | `beta`), `min_version_code`, `max_version_code`, `state` (`draft` | `published` | `archived`), `repeat_mode` (`once` | `repeat`), `starts_at`, `ends_at`, `created_at`, `updated_at`
    - `feature_flags`: exact 4 keys `beta_enrollment_enabled` (default `false`), `feedback_enabled` (default `false`), `online_maintenance` (default `false`), `update_check_interval_hours` (default `12`, clamped `6..72`)
  - Files modified: `ControlPolicyModels.kt`, `SupabasePolicyClient.kt`, `PolicyCacheStore.kt`, `PolicyEvaluator.kt`, `AppPreferencesRepository.kt`, `OtaModels.kt`, `VaultUiState.kt`, `VaultViewModel.kt`, `OtaCheckWorker.kt`.

### Defect 2 — Backend PostgREST RPC Invocation & Audit Attribution Mismatch (Release Blocker)
- **Root Cause:** Edge Functions (`release-control` and `submit-feedback`) called `adminClient.rpc("admin_transition_release", ...)` and `adminClient.rpc("submit_verified_feedback", ...)`, which query PostgREST's `public` schema, while both SQL functions resided solely inside `pv_private` (which stays unexposed in Supabase PostgREST settings).
- **Correction:**
  - Added corrective migration [20261126140000_v210_corrective_rpc_bridges_and_audit_attribution.sql](file:///supabase/migrations/20261126140000_v210_corrective_rpc_bridges_and_audit_attribution.sql):
    - Creates `public.admin_transition_release` and `public.submit_verified_feedback` `SECURITY DEFINER` bridges delegating to `pv_private`, with execution **revoked from `PUBLIC`, `anon`, and `authenticated`** and **granted strictly to `service_role`**.
    - Updates `pv_private.admin_transition_release` to set both `request.jwt.claim.sub` and `request.jwt.claims` so `admin_audit_log.actor_user_id` attributes cleanly across PostgreSQL/PostgREST versions, enforces fresh artifact re-verification on `resume`, and preserves `available_until` unless `p_clear_available_until` is explicitly true.
    - Updated `release-control/index.ts` to validate the session via `userClient.auth.getUser(token)` before trusting MFA assurance (`getAuthenticatorAssuranceLevel()` / AAL2).

### Defect 3 — Backend Release Artifact Verification Insufficient
- **Root Cause:** `release-control/index.ts` previously performed only an HTTP `HEAD` check on `apkUrl` and read `Content-Length`, without digesting the real APK bytes or checking archive/signing structure.
- **Correction:**
  - Implemented `StreamingSha256` and `verifyApkBinaryStream` in `supabase/functions/_shared/releaseValidation.ts`, wired into both `publish` and `resume` inside `release-control/index.ts`.
  - Streams the actual GitHub release APK asset over verified HTTPS hops, computes the exact full-body SHA-256 digest and exact byte length in constant memory, verifies ZIP container header magic (`PK\x03\x04`) and APK Signature Scheme v2/v3 signing block marker (`APK Sig Block 42`), and reconciles them against `update.json` and `releases`.
  - Android-side ([OtaUpdateManager.kt](file:///app/src/main/java/com/tpeditz/privatevault/v2/ota/OtaUpdateManager.kt)) enforces 4-gate Supabase-vs-GitHub metadata parity (`verifyManifestMatchesApprovedRelease`) across update checks, download start, Unknown-Sources permission recovery, and PackageInstaller handoff (matching `versionName`, `versionCode`, `apk_sha256`, `apk_size_bytes`, `signing_cert_sha256`, and tagged asset URL), clears `withdrawn` candidates via `handlePolicyAuthorizationFailure` while retaining `paused` APKs safely, and runs local `ApkVerifier` v1/v2/v3 X.509 certificate verification and `PackageManager` archive validation before installer handoff.
  - **Known Limitation:** Edge runtime does not parse X.509 RSA/ECDSA ASN.1 signatures or binary `AndroidManifest.xml` locally; cryptographic signature verification against the permanent release certificate happens during GitHub Actions staging (`publish-production-apk.yml`) and on-device inside Android `OtaUpdateManager`.

### Defect 4 — Cached Policy Used as Fresh Release Authorization (OTA Reliability)
- **Root Cause:** `SupabaseOtaPolicyGate.syncPolicySnapshot` previously fell back to returning 24-hour cached policy data when network refresh failed, allowing offline/cached data to authorize release checks or pending installs.
- **Correction:**
  - Replaced with `PolicySyncResult` (`Fresh`, `NetworkUnavailable`, `ServiceUnavailable`).
  - `resolveEligibleCheckTarget()` and `authorizeDownloadOrInstall()` strictly require `PolicySyncResult.Fresh`. On failed refresh or server error, `OtaUpdateManager` defers cleanly to `OtaState.PolicyDeferred` displaying *"Unable to verify the update right now. Please try again later."* without deleting a previously verified local APK or falling back to `/releases/latest/`.
  - Offline vault unlock, notes, passwords, media, TOTP, search, and local backup/restore remain completely independent.

### Defect 5 — Announcements Dialog Presentation, Target `all` & Feedback Client Completion
- **Root Cause:** Android handled only banner-style announcements (`HomeScreen`) and lacked `AlertDialog` rendering for `presentation = 'dialog'`, and `FeedbackScreen` only had a preview without `SupabaseFeedbackClient`.
- **Correction:**
  - `VaultUiState` and `VaultViewModel` now filter published announcements with `all`/`stable`/`beta`, `min_version_code`/`max_version_code`, `starts_at`/`ends_at`, and `once`/`repeat` dismissal semantics, splitting `activeAnnouncements` (banners in `HomeScreen`) and `activeDialogAnnouncement` (dialog in `MainActivity`).
  - Added [SupabaseFeedbackClient.kt](file:///app/src/main/java/com/tpeditz/privatevault/v2/policy/SupabaseFeedbackClient.kt) with consent validation, minimal `{ category, message, contact?, app_version, consent }` payload, double-submit prevention, loading state, `"Saved successfully. Thank you for your feedback."`, and plain-English errors (`429`, network failures), while keeping `FEEDBACK_SUBMISSION_ENABLED = false` and server `feedback_enabled = false` in production config until Edge Functions are deployed.

### Defect 6 — Admin Control Center Polish
- Added `toFriendlyError` mapping in `admin-web/src/lib/controlCenterApi.ts`, `"Rollout %"` modal control in `ReleasesView.tsx`, `"Edit"` button on announcement rows in `AnnouncementsView.tsx`, and expanded `App.test.tsx`.

---

## 3. Feature Preservation & Contracts Status

- **Android <-> Supabase Contract:** Verified 1:1 field compatibility in [SUPABASE_SCHEMA_COMPATIBILITY_REPORT.md](file:///SUPABASE_SCHEMA_COMPATIBILITY_REPORT.md) and automated `SupabaseSchemaContractTest`.
- **V2.0.4 Continuity:** All 130 baseline unit tests (Argon2id/crypto, `PVAULTB2`, `LegacyV1BackupReader`, password policy, TOTP, Autofill removal, Drive sync, ViewModel error mapping) continue passing without schema/crypto breaking changes.
- **Owner Deployment Prerequisites:** Review and apply `20261126130000` + `20261126140000`, deploy Edge Functions, and perform device verification using [OWNER_ANDROID_DEVICE_TEST_CHECKLIST.md](file:///OWNER_ANDROID_DEVICE_TEST_CHECKLIST.md) and [ADMIN_CONTROL_CENTER_TEST_CHECKLIST.md](file:///ADMIN_CONTROL_CENTER_TEST_CHECKLIST.md).
