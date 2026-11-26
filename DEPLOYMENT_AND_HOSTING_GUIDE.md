# Private Vault V2.1.0 — Deployment & Hosting Guide

> **Note:** All deliverables in this workspace were prepared locally without modifying your live Supabase database, deploying live Edge Functions, or pushing to GitHub. Review every SQL migration and Edge Function carefully before applying them in your environment.

---

## 1. Repository Artifacts Overview

- **Supabase Foundation Snapshot (Already Applied Live — Reference Only):**
  - [20261126120731_private_vault_v210_control_center_foundation.sql](file:///supabase/migrations/20261126120731_private_vault_v210_control_center_foundation.sql)
  - Do **not** re-run against the live database where the V2.1.0 foundation has already been applied.
- **Reviewable Follow-Up Migration (Atomic Release Control & Feedback Rate Limiting):**
  - [20261126130000_v210_release_control_and_feedback_rpc.sql](file:///supabase/migrations/20261126130000_v210_release_control_and_feedback_rpc.sql)
- **Supabase Edge Functions:**
  - `supabase/functions/release-control/index.ts` (owner / `release_manager` AAL2 JWT + `admin_roles` verification, CORS validation, calls `pv_private.admin_transition_release`)
  - `supabase/functions/submit-feedback/index.ts` (rate-limited feedback submission via `pv_private.submit_verified_feedback`, gated by `client_policy_state.feedback_enabled`)
  - Shared validation helpers: `supabase/functions/_shared/releaseValidation.ts`
  - Unit tests: `supabase/functions/tests/edge_functions_test.ts`
- **Admin Control Center Frontend & Static Output:**
  - Source: `admin-web/` (React + TypeScript + Vite)
  - Pre-built static assets for GitHub Pages: `docs/admin/` (`base: './'`, sourcemaps disabled)

---

## 2. Hosting the Admin Control Center on GitHub Pages (`docs/admin/`)

`docs/admin/` already contains the built static bundle from `admin-web` (`npm run build`). Because `docs/` also hosts the public Private Vault marketing/privacy pages (`docs/index.html`, etc.):

1. Push the committed `docs/admin/` directory to your `main` branch (when ready after review).
2. GitHub Pages serves the Control Center at:
   - `https://tpeditz.github.io/Private-vault-android-v2/admin/`
3. Verify in **Supabase Dashboard -> Authentication -> URL Configuration** that:
   - Site URL / Redirect URLs permit `https://tpeditz.github.io/Private-vault-android-v2/admin/`
   - Public Sign-Up remains **Disabled** in Authentication settings so only invited/provisioned admins can authenticate.
4. Confirm in `admin-web/vite.config.ts` / `.env` whether live Edge Functions are deployed yet:
   - Leave `VITE_RELEASE_CONTROL_ENABLED` unset/`false` until Step 3 below is completed.
   - Once `release-control` is live, rebuild with `VITE_RELEASE_CONTROL_ENABLED=true npm run build` inside `admin-web/`.

---

## 3. Reviewing & Deploying Follow-Up Supabase Migration + Edge Functions

When ready to enable server-side release publishing/pausing/withdrawal:

1. **Link project & review SQL diff:**
   ```bash
   supabase link --project-ref lszjacrrqthhdapzvjok
   supabase db push --dry-run
   ```
   Confirm only `20261126130000_v210_release_control_and_feedback_rpc.sql` is pending.
2. **Apply follow-up migration:**
   ```bash
   supabase db push
   ```
3. **Configure Edge Function secrets (if using strict allowed origin lists):**
   ```bash
   supabase secrets set ADMIN_ALLOWED_ORIGINS="https://tpeditz.github.io,http://localhost:5173"
   ```
4. **Deploy Edge Functions:**
   ```bash
   supabase functions deploy release-control --no-verify-jwt=false
   ```
   *(Optional, only if you decide to enable feedback collection after review:)*
   ```bash
   supabase functions deploy submit-feedback --no-verify-jwt=true
   ```
5. **Re-enable live transition buttons in Admin Web:**
   ```bash
   cd admin-web
   VITE_RELEASE_CONTROL_ENABLED=true npm run build
   ```
   Commit the updated `docs/admin/` assets.

---

## 4. Android App (`V2.1.0`) Build & GitHub Release Checklist

1. Ensure GitHub Actions workflow has signing keystore secrets (`KEYSTORE_BASE64`, etc.) when cutting a production release tag `v2.1.0`.
2. Build signed `private-vault.apk` and signed `update.json` (matching permanent cert SHA-256 `3B:EE:FF:13:...`).
3. Create draft release in Admin Control Center (`versionCode = 6`, `versionName = "2.1.0"`, channel `stable`, rollout `100%`) and publish via `release-control` after uploading APK + `update.json` to GitHub Release `v2.1.0`.
