# Private Vault V2.1.0 — Admin Control Center Setup & Operations Guide

This guide covers operator setup, authentication, MFA, release publishing, announcements, feature flags, and feedback administration for the Private Vault Control Center (`admin-web/`, built to `docs/admin/`).

---

## 1. Security Model & Access Prerequisites

- **No Public Sign-Up:** The Control Center does **not** expose public registration (`signUp`), password-less magic links, or anonymous admin access.
- **Role-Based Access Control:** Admin membership is enforced at the database layer via `public.admin_roles` (`role IN ('owner', 'release_manager', 'moderator')`).
- **Hardware/Authenticator TOTP MFA (AAL2 Required):**
  - Logging in with email and password creates an `AAL1` session.
  - All RLS write policies and sensitive reads (`admin_audit_log`, `feedback`, `releases`, `announcements`, `feature_flags`) require Authenticator Assurance Level 2 (`aal2`) via enrolled TOTP MFA.
  - On first `AAL1` sign-in, the web dashboard automatically prompts you to enroll a TOTP factor (QR code + manual secret + 6-digit verification code) or verify an existing TOTP code before unlocking admin views.
- **Public Key Only in Client Code:** `admin-web` embeds only the public Supabase URL (`https://lszjacrrqthhdapzvjok.supabase.co`) and public publishable key (`sb_publishable_-9CyCkZpssMM_PJt2q3PYw_ybXtYnA_`). Never place `service_role` keys in client bundles, `.env` files committed to git, or Android source code.

---

## 2. Signing In & Completing AAL2 MFA

1. Open the hosted Control Center page (GitHub Pages at `https://tpeditz.github.io/Private-vault-android-v2/admin/` or local Vite preview).
2. Enter your pre-provisioned administrator email and password.
3. Complete TOTP MFA:
   - If you have not enrolled an authenticator app yet, scan the generated QR code (or copy the TOTP secret into a TOTP authenticator such as Private Vault's built-in TOTP Authenticator, Google Authenticator, or 1Password) and enter the 6-digit code.
   - Confirm the **Admin Security** screen displays **Session Assurance: AAL2 Verified** and your `admin_roles` role (`owner`, `release_manager`, or `moderator`).

---

## 3. Managing Releases & Staged Rollouts

- **In-Browser Draft Authoring (Working Immediately via RLS):**
  - Signed-in AAL2 `owner` or `release_manager` operators can create, edit, and delete **`draft`** releases directly in the **Releases** view.
  - Canonical `manifest_url` (`https://github.com/tpeditz/Private-vault-android-v2/releases/download/v<version>/update.json`) and permanent `signing_cert_sha256` (`3beeff138b716168b2f166983d42496b9d24f629b75ee25bdafd16676f06a770`) are enforced at both database constraint and client validation layers.
- **Publishing, Pausing, Resuming, Withdrawing, and Adjusting Live Rollouts:**
  - Direct client-side updates to non-draft releases are intentionally blocked by Postgres RLS so audit logging (`admin_audit_log`) and single-channel policy consistency (`client_policy_state`) are always atomic.
  - When `VITE_RELEASE_CONTROL_ENABLED` is `false` (default before deploying Edge Functions), live transition buttons are disabled in the UI with a clear banner explaining that `supabase/functions/release-control` must be deployed first.
  - Once `release-control` and migration `20261126130000_v210_release_control_and_feedback_rpc.sql` are deployed and the UI build flag `VITE_RELEASE_CONTROL_ENABLED=true` is enabled, operators can atomically:
    - **Publish** (`0–100%` rollout, `stable` or `beta` channel, `normal` / `important` / `critical` priority, optional `starts_at` / `ends_at`, `min_supported_version_code`)
    - **Adjust Rollout %** (`10% -> 25% -> 50% -> 100%`)
    - **Pause** or **Resume** rollout (clients retain already verified APKs safely while install authorization fails closed until resumed)
    - **Withdraw** a faulty release (clients block both download and install immediately)

---

## 4. Publishing Announcements

- **Allowed Format:** Plain text only (HTML/scripts are rejected/ignored on both web preview and Android Compose rendering).
- **Fields:**
  - `title` (3–120 chars), `body` (1–2000 chars)
  - `severity`: `info`, `warning`, `security`, `update`
  - `target_channel`: All channels, `stable`, or `beta`
  - Optional version range (`min_version_code`, `max_version_code`) and schedule (`starts_at`, `ends_at`)
  - `display_mode`: `once` (dismissible permanently per device) or `repeat` (re-appears on subsequent unlock sessions while active)
- Always use the built-in **Live Plain-Text Preview** before clicking Publish.

---

## 5. Safe Feature Flags (Strict 4-Key Allowlist)

Only four bounded operational keys exist:

| Key | Type | Safe Default | Bounds / Effect |
| --- | --- | --- | --- |
| `ota_checks_enabled` | boolean | `true` | Enables/pauses remote OTA policy checks without blocking offline vault use. |
| `ota_check_interval_hours` | integer | `12` | Background WorkManager check cadence, clamped strictly to `6..72` hours. |
| `beta_enrollment_enabled` | boolean | `true` | Allows/blocks Android clients from receiving `beta` channel updates and beta opt-in. |
| `online_maintenance` | boolean | `false` | Shows a subtle maintenance notice on online screens (`App Updates`, `Cloud Backup`, Home banner) and pauses remote OTA checks while keeping local vault unlock, editing, TOTP, and local backups untouched. |

Unlisted keys and out-of-range values are ignored by Android `SupabasePolicyClient`.

---

## 6. Feedback Moderation & Security Log

- **Feedback Collection Disabled by Default:** `client_policy_state.feedback_enabled` defaults to `false`, Android `BuildConfig.FEEDBACK_SUBMISSION_ENABLED` is `false`, and RLS prevents anonymous/authenticated direct `INSERT` into `public.feedback`. Enable only after reviewing and deploying `supabase/functions/submit-feedback`.
- **Audit Log:** `admin_audit_log` is read-only for AAL2 admins (`SELECT` only) and records actor UID, action type, entity ID, timestamp, and metadata diffs for accountability.
