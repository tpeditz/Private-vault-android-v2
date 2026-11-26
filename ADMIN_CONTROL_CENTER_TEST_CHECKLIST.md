# Private Vault V2.1.0 — Admin Control Center Verification Checklist

Complete these manual checks against your live Supabase project and hosted GitHub Pages (`docs/admin/`) before relying on production release transitions.

## 1. Access & Authentication Security
- [ ] Open the Admin Control Center while logged out -> Confirm only **Admin Authentication & MFA Security** appears and no protected tabs data is loaded.
- [ ] Confirm there is NO public "Sign Up" or "Create Account" button anywhere on the login screen.
- [ ] Attempt login with an unprivileged / non-admin email -> Confirm authorization is denied (`Not in admin_roles`) and operational data remains inaccessible.
- [ ] Log in with your `owner` account -> Confirm dashboard remains locked in **AAL1** until 6-digit TOTP verification completes.
- [ ] Enroll TOTP authenticator if not already enrolled (scan QR in Private Vault Authenticator or another TOTP app) and enter valid 6-digit code -> Confirm badge switches to **`OWNER · AAL2`** and dashboard tabs load.
- [ ] Reload the browser tab -> Confirm AAL2 session restores cleanly without leaking data.
- [ ] Click **Sign Out** -> Confirm all release/feedback/audit state clears from the page.

## 2. Releases Management (`Releases` Tab)
- [ ] Confirm that before setting `VITE_RELEASE_CONTROL_ENABLED=true`, the **Publish**, **Pause**, **Withdraw**, and **Rollout %** buttons remain disabled with the warning banner visible.
- [ ] Create a release draft (`2.1.0`, code `6`, `stable`, priority `recommended`, rollout `20%`) -> Confirm it saves cleanly to `public.releases` with `state = 'draft'`.
- [ ] Edit the draft notes and schedule -> Confirm changes persist.
- [ ] Delete a test draft -> Confirm it removes cleanly.
- [ ] After deploying Edge Functions (`release-control`) and enabling `VITE_RELEASE_CONTROL_ENABLED=true`:
  - [ ] Publish a real tagged release whose GitHub `v2.1.0` release contains both `update.json` and signed `private-vault.apk` -> Confirm it succeeds and shows **`Verified APK`** SHA-256 snippet.
  - [ ] Try publishing a fake/non-existent version -> Confirm server returns HTTP 422 without changing DB state.
  - [ ] Adjust **Rollout %** (e.g., `20%` -> `50%`), test **Pause**, **Resume**, and **Withdraw**.

## 3. Announcements (`Announcements` Tab)
- [ ] Create a draft banner announcement targeting `all`, repeating `once`, and click **Edit** -> Confirm all fields reload accurately into the form.
- [ ] Test HTML/script string `<script>alert(1)</script>` -> Confirm **Preview** and table display literal escaped text with zero script execution.
- [ ] Publish an announcement and archive it -> Confirm state transitions work and only non-published announcements can be deleted.

## 4. Feature Flags (`Feature Flags` Tab)
- [ ] Confirm exactly 4 flags exist: `beta_enrollment_enabled`, `feedback_enabled`, `online_maintenance`, `update_check_interval_hours`.
- [ ] Try saving `update_check_interval_hours` outside `6..72` (e.g., `3` or `90`) -> Confirm UI rejects invalid bounds cleanly.
- [ ] Toggle `online_maintenance` on/off with confirmation dialog -> Confirm update succeeds and persists across page refresh.

## 5. Feedback & Audit Log (`Feedback` & `Audit Log` Tabs)
- [ ] Confirm `Feedback` filters by status (`open`, `reviewing`, `resolved`), category (`bug`, `suggestion`, `other`), and app version.
- [ ] Confirm `Audit Log` is read-only and displays `actor_user_id`, `event`, `entity`, `before_state`, and `after_state` for every test change you made above.

## 6. Light / Dark Responsiveness
- [ ] Toggle Light / Dark theme icon in header -> Confirm readable contrast in both modes.
- [ ] Test desktop (`>= 1280px`) and mobile (`<= 480px`) viewports -> Confirm sidebar collapses cleanly behind the menu button and tables scroll horizontally without clipping.
