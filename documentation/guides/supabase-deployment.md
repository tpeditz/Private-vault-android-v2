# Private Vault V2.1.0 — Beginner-Friendly Deployment Guide

> **Important:** Nothing has been published or pushed yet. Follow these steps in order only after you have reviewed the repaired code and test results.

---

## What You Need Before Starting

1. A free **GitHub** account logged into your repository: `https://github.com/tpeditz/Private-vault-android-v2`.
2. Your **Supabase Dashboard** login for project `lszjacrrqthhdapzvjok`.
3. Your computer with **Node.js** installed (to rebuild the admin website later) and **Supabase CLI** installed if you deploy the server functions from your PC.

---

## Part 1: Verify Your Supabase Project Settings (5 Minutes)

Because your foundation database migration (`20261126120731`) is **already applied**, do **not** run that first file again.

1. Open [Supabase Dashboard](https://supabase.com/dashboard) and select project **`lszjacrrqthhdapzvjok`**.
2. Go to **Authentication -> Providers -> Email**:
   - Confirm **Allow new users to sign up** is **OFF** (disabled). Only accounts you manually add can log in.
3. Go to **Authentication -> Multi-Factor Authentication**:
   - Confirm **TOTP (App Authenticator)** is **Enabled**.
4. Go to **Authentication -> URL Configuration**:
   - Under **Redirect URLs**, add:
     `https://tpeditz.github.io/Private-vault-android-v2/admin/`
5. Go to **Table Editor -> `admin_roles`**:
   - Confirm your admin user's `user_id` is present with `role = owner` and `active = true`.

---

## Part 2: Apply the Two Follow-Up Database Files (Only When Ready)

1. In Supabase Dashboard, click **SQL Editor** in the left menu.
2. Open the file `supabase/migrations/20261126130000_v210_release_control_and_feedback_rpc.sql` from this project on your computer, copy all of its text, paste it into the SQL Editor, and click **Run**.
   - Check that it says **Success. No rows returned**.
3. Now open the second file `supabase/migrations/20261126140000_v210_corrective_rpc_bridges_and_audit_attribution.sql`, copy all of its text, paste it into a new query in the SQL Editor, and click **Run**.
   - Check that it says **Success**.

*(Alternative for terminal users: `supabase link --project-ref lszjacrrqthhdapzvjok` followed by `supabase db push`.)*

---

## Part 3: Deploy the Server Functions (When Enabling Publish / Pause / Feedback)

1. Open Command Prompt / PowerShell inside this project folder.
2. Log in to Supabase CLI:
   ```powershell
   supabase login
   supabase link --project-ref lszjacrrqthhdapzvjok
   ```
3. Set your allowed website addresses so only your official Admin site can call release controls:
   ```powershell
   supabase secrets set ADMIN_ALLOWED_ORIGINS="https://tpeditz.github.io,http://localhost:5173"
   ```
4. Deploy the release-control server function (requires valid admin login):
   ```powershell
   supabase functions deploy release-control --no-verify-jwt=false
   ```
5. *(Optional — only if you want feedback enabled later)*:
   ```powershell
   supabase secrets set FEEDBACK_RATE_PEPPER="choose-a-long-random-secret-phrase"
   supabase functions deploy submit-feedback --no-verify-jwt=true
   ```
   Also remember: Android's app build flag `FEEDBACK_SUBMISSION_ENABLED` in `app/build.gradle.kts` is currently `false` on purpose so users never see errors before you deploy and turn on `feedback_enabled` in Feature Flags.

---

## Part 4: Host the Admin Control Center on GitHub Pages

The ready-to-host website files are already inside `docs/admin/`.

1. **Before deploying `release-control`:** Leave `admin-web` built as-is (`docs/admin/` already disables live Publish/Pause/Withdraw buttons safely until Part 3 is done).
2. **After completing Part 3:** Enable the live Publish/Pause/Withdraw buttons in the admin website:
   ```powershell
   cd admin-web
   $env:VITE_RELEASE_CONTROL_ENABLED="true"
   npm run build
   Remove-Item Env:VITE_RELEASE_CONTROL_ENABLED
   ```
3. Commit and push the project to GitHub (`main` branch). GitHub Pages will automatically serve your admin website at:
   - `https://tpeditz.github.io/Private-vault-android-v2/admin/`

---

## Part 5: Build & Sign Private Vault 2.1.0 for Users

1. Open your GitHub repository **Actions -> Publish Signed Production APK** workflow.
2. Run it in **`test`** mode first to verify the signed `private-vault.apk` and `update.json` manifest are built properly.
3. Publish the **V2.1.0** GitHub release (tag `v2.1.0`) containing both `private-vault.apk` and `update.json`.
4. Open the **Admin Control Center** website -> sign in -> complete your 6-digit MFA code -> create a release draft for `2.1.0` (version code `6`) -> click **Publish** (starts at your chosen rollout %, e.g. `10%`, then increase to `50%` and `100%` when ready).
