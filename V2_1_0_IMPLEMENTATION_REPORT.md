# Private Vault V2.1.0 — Implementation Report

**Baseline:** GitHub `main` commit `e66746984fae69b0931ac548741045378386df7b` (`v2.0.4`, `versionCode = 5`)  
**Target Version:** `versionName = "2.1.0"`, `versionCode = 6`, `applicationId = "com.tpeditz.privatevault.v2"` (debug suffix `.debug`), `minSdk = 24`, `targetSdk = 36`

---

## 1. Delivered Components & Architecture

### A. Supabase Schema, Migrations & Edge Functions (`supabase/`)
- **Foundation Migration Snapshot (Reference Copy — Not Re-Applied Live):**
  - `supabase/migrations/20261126120731_private_vault_v210_control_center_foundation.sql`
  - Captures the already-applied V2.1.0 control-plane tables (`admin_roles`, `client_policy_state`, `releases`, `announcements`, `feature_flags`, `feedback`, `admin_audit_log`), AAL2/role RLS rules, official repo & permanent signing certificate constraints, and helper functions cleanly for version control.
- **Reviewable Follow-Up Migration (`20261126130000`):**
  - `supabase/migrations/20261126130000_v210_release_control_and_feedback_rpc.sql`
  - Adds `pv_private.admin_transition_release(...)` for atomic release publishing, pausing, resuming, withdrawing, and rollout percentage updates (syncing `client_policy_state` and writing attributed entries into `admin_audit_log`), plus `pv_private.feedback_rate_limits` and `pv_private.submit_verified_feedback(...)` granted strictly to `service_role`.
- **Supabase Edge Functions & Deno Tests:**
  - `supabase/config.toml`
  - `supabase/functions/_shared/releaseValidation.ts`
  - `supabase/functions/release-control/index.ts` (enforces AAL2 JWT, `admin_roles`, origin policy, and GitHub tag manifest validation before transitioning release state)
  - `supabase/functions/submit-feedback/index.ts` (enforces `client_policy_state.feedback_enabled`, explicit consent, bounded input lengths, and hashed rate limiting)
  - `supabase/functions/tests/edge_functions_test.ts` (5 Deno unit tests covering URL/manifest validation, JWT AAL decoding, and feedback validation)

### B. Admin Control Center Web UI (`admin-web/` -> `docs/admin/`)
- **Framework & Build:** React 18 + TypeScript + Vite 5 (`base: './'`, sourcemaps disabled, bundled to static assets in `docs/admin/` for GitHub Pages hosting).
- **Zero-Secret Configuration:** `admin-web/src/lib/supabaseClient.ts` connects strictly with the public Supabase URL (`https://lszjacrrqthhdapzvjok.supabase.co`) and public publishable key (`sb_publishable_-9CyCkZpssMM_PJt2q3PYw_ybXtYnA_`). Public sign-up is absent.
- **Strict AAL2 MFA Enforcement:** `App.tsx` and `SecurityView.tsx` gate all admin views behind Supabase Auth email/password sign-in, `admin_roles` verification, and TOTP MFA enrollment/challenge (`QrCodeSvg.tsx`) until session assurance reaches `aal2`.
- **Admin Views Implemented (`admin-web/src/views/`):**
  - `OverviewView.tsx`: Active stable/beta policy summary, pending draft count, published announcements, and feature flag status.
  - `ReleasesView.tsx`: Draft creation, editing, and deletion directly via AAL2 RLS, with live `publish` / `pause` / `resume` / `withdraw` / `rollout %` controls cleanly disabled (`VITE_RELEASE_CONTROL_ENABLED !== "true"`) until the `release-control` Edge Function is deployed.
  - `AnnouncementsView.tsx`: Create/edit/publish/unpublish/delete plain-text announcements with channel, min/max version code, `starts_at`/`ends_at`, `once`/`repeat` display mode, confirmation dialogs, and live plain-text preview.
  - `FeatureFlagsView.tsx`: Strict 4-key allowlist (`ota_checks_enabled`, `ota_check_interval_hours` clamped `6..72`, `beta_enrollment_enabled`, `online_maintenance`) with publish toggles and confirmation dialogs.
  - `FeedbackView.tsx`: Read-only triage/archive moderation table respecting default `feedback_enabled = false`.
  - `AuditLogView.tsx`: Read-only paginated/chronological audit trail (`admin_audit_log`).
  - `SecurityView.tsx`: AAL status, role verification, TOTP factor management, and sign-out.
- **Responsive Light/Dark Theme:** Implemented with CSS variables and clean media queries in `admin-web/src/styles/theme.css`.

### C. Android V2.1.0 Policy Control Plane & UI Integration
- **Build Configuration (`app/build.gradle.kts`):**
  - Updated `versionCode = 6`, `versionName = "2.1.0"`.
  - Added `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_PUBLISHABLE_KEY`, and `BuildConfig.FEEDBACK_SUBMISSION_ENABLED = false`, preserving canonical `UPDATE_MANIFEST_URL` and permanent `RELEASE_CERT_SHA256`.
- **Policy Layer (`app/src/main/java/com/tpeditz/privatevault/v2/policy/`):**
  - `ControlPolicyModels.kt`: Immutable release/announcement/flag/policy models, strict allowlisted defaults, plain-text sanitizer, feedback validator, and API-24–safe UTC ISO-8601 timestamp parser (`SimpleDateFormat`, zero `java.time.Instant` calls).
  - `SupabasePolicyClient.kt`: Anonymous HTTPS REST client with bounded payload size (`<= 512 KiB`), zero hardware/vault headers, repository URL pinning, certificate SHA-256 validation, and unknown-flag rejection.
  - `PolicyCacheStore.kt`: Atomic JSON cache persistence in `filesDir/ota_updates/control-policy-cache.json` with 24-hour TTL validation.
  - `PolicyEvaluator.kt`: Privacy-preserving `SecureRandom` rollout cohort (`0..99`), fail-closed `evaluateEligibleRelease(...)`, `evaluatePendingInstallAuthorization(...)`, and `selectEffectiveAnnouncements(...)`.
  - `SupabaseOtaPolicyGate.kt`: Fail-closed bridge integrated into `OtaUpdateManager` and `PrivateVaultApplication`.
  - `OtaCheckWorker.kt` & `OtaPolicyScheduler`: `WorkManager` periodic background check (`6..72h`, default `12h`) requiring `NetworkType.CONNECTED`, honoring user auto-check opt-out, never opening vault storage, and never auto-installing APKs.
- **OTA Update Manager Hardening (`OtaModels.kt`, `OtaUpdateManager.kt`, `AppPreferencesRepository.kt`):**
  - `checkForUpdates()`, `startDownload()`, `recheckInstallPermissionAndVerifyCandidate()`, and `launchPackageInstaller()` consult `SupabaseOtaPolicyGate`.
  - Missing/stale policy, pause/withdrawal, schedule bounds, disabled flags, maintenance mode, or rollout ineligibility transition cleanly to `OtaState.PolicyDeferred` with **zero fallback** to `/releases/latest/download/update.json`.
  - Already-downloaded & checksum/signature-verified APKs whose releases are later marked `paused` or `withdrawn` are blocked from installer launch while preserved safely in private app storage.
- **Screens & ViewModel (`VaultUiState.kt`, `VaultNavigation.kt`, `VaultViewModel.kt`, `HomeScreen.kt`, `OtaUpdatesScreen.kt`, `CloudBackupScreen.kt`, `SettingsScreen.kt`, `FeedbackScreen.kt`, `MainActivity.kt`):**
  - `HomeScreen`: Renders active plain-text announcement banners (`once` permanently dismissible, `repeat` dismissible per unlock session) plus optional online maintenance banner above Vault Health.
  - `OtaUpdatesScreen`: Adds Beta channel opt-in toggle (gated by `beta_enrollment_enabled`), release channel/priority badges, `OtaState.PolicyDeferred` card, background check interval label, and maintenance notice.
  - `CloudBackupScreen`: Surfaces non-blocking maintenance notice when `online_maintenance = true` without affecting local backups or offline vault operations.
  - `SettingsScreen` & `FeedbackScreen`: Adds **Send Feedback** entry with consent checkbox, category chips (`bug`/`suggestion`/`other`), message bounds (`1..3000`), optional contact, payload preview dialog, and explicit disabled status while `FEEDBACK_SUBMISSION_ENABLED = false`.

---

## 2. Privacy & Scope Boundaries

- Zero Autofill services or Autofill integrations exist (`0` references in `app/src/main`).
- Zero `service_role` keys or signed JWT secrets appear in Android, `admin-web/src/`, or `docs/admin/`.
- Live Supabase database (`lszjacrrqthhdapzvjok.supabase.co`) and live Edge Functions were **not** modified or deployed from this workspace, and nothing was committed, tagged, or pushed to GitHub.
