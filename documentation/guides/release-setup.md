# Release Setup & App Publishing Guide (Step-by-Step)

This guide explains in plain English how **Private Vault** is built, signed, verified, and published on GitHub — without requiring Android Studio on your computer.

---

## Before You Start

Your project includes two automatic GitHub workflows inside `.github/workflows/`:

1. **Android CI Verification** (`ci.yml`) — runs automatically whenever code is pushed or opened in a pull request. It compiles the app, checks code quality, and runs the unit test suite.
2. **Build & Publish Production APK** (`publish-production-apk.yml`) — builds the signed release app (`private-vault.apk`) and creates `update.json` for in-app updates. This workflow **only runs when you start it manually** on GitHub.

Your repository should already have these four **Actions secrets** saved in **GitHub Repository Settings → Secrets and variables → Actions**:
- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

> [!IMPORTANT]
> Never paste passwords, keystore files, or personal access tokens into code, issues, or chat messages. Keep those four values inside GitHub Actions Secrets only.

---

## Part 1 — Do a Safe Dry-Run First (`test` mode)

Always run **`test` mode** before publishing to users. It builds the signed APK, verifies your certificate, creates `update.json`, and saves the files privately inside the workflow run — **without** creating a public GitHub Release.

1. Open your repository on GitHub (`tpeditz/Private-vault-android-v2`).
2. Click the **Actions** tab near the top of the page.
3. On the left side, click **Build & Publish Production APK**.
4. Click the **Run workflow** dropdown button on the right.
5. Make sure:
   - **Use workflow from:** `Branch: main`
   - **mode:** `test`
   - **release_notes:** leave default or type a short note
6. Click the green **Run workflow** button.
7. Wait for the run to finish with a green checkmark.
8. Open the finished run, scroll down to **Artifacts**, and download `private-vault-release-staging-...` to inspect `private-vault.apk` and `update.json` if you want to test the APK on your own phone first.

---

## Part 2 — Publish a Real Update for Users (`publish` mode)

### Step 1: Bump the version number (for any release after `2.0.0`)
In-app updates only trigger when the new app's `versionCode` is **strictly higher** than the version currently installed on users' phones (or currently published on GitHub).

Before publishing an update after your first `2.0.0` release, open `app/build.gradle.kts` and increase both numbers:
- `versionCode` (for example, `1` → `2`)
- `versionName` (for example, `"2.0.0"` → `"2.0.1"`)

*(The workflow checks the currently published `update.json` and automatically blocks accidental duplicate or lower version numbers.)*

### Step 2: Run the workflow in `publish` mode
1. Open GitHub → **Actions** → **Build & Publish Production APK**.
2. Click **Run workflow**.
3. Set:
   - **mode:** `publish`
   - **release_notes:** write plain-English bullet points explaining what improved in this update (users will see this inside **App Updates** in the app).
4. Click **Run workflow** and wait for the green checkmark.

---

## What the Workflow Checks Automatically

Before publishing, the workflow automatically refuses to proceed if any check fails:
- All unit tests (`:app:testDebugUnitTest`) and lint checks (`:app:lintDebug`) must pass.
- The signed APK (`private-vault.apk`) is verified using `apksigner` and checked against your permanent official signing certificate fingerprints:
  - **SHA-1:** `32:19:54:C3:81:B4:A7:9D:76:B8:90:FF:05:A3:18:D0:21:FB:AD:FC`
  - **SHA-256:** `3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70`
- **APK Build & Signature Checks:** `apksigner` verifies both the permanent SHA-1 (`32:19:54:...`) and SHA-256 (`3B:EE:FF:...`) release signing certificates.
- **Metadata & Non-Debuggable Enforcement:** `aapt2` and `apkanalyzer` unconditionally verify `applicationId = com.tpeditz.privatevault.v2`, exact `versionCode`/`versionName`, and **`debuggable == false`**.
- **Monotonic Release Guard:** Distinguishes initial `404` (when zero GitHub Releases exist) from API/network errors, refusing to publish if `versionCode` is not strictly higher than the currently live release.
- **Draft-Staged Promotion & Live Verification:** Uploads `private-vault.apk` and `update.json` to a draft release first, verifies both assets exist, promotes the release publicly, downloads the live full APK + `update.json`, and verifies exact byte length, SHA-256, and `apksigner` certificate pinning.
- Your temporary decoded keystore file is wiped cleanly from the runner before the job ends (`if: always()`).

> [!NOTE]
> **Play Store vs. GitHub Direct OTA Notice:** Self-updating `REQUEST_INSTALL_PACKAGES` OTA behavior applies strictly to direct APK distribution via GitHub Releases (`tpeditz/Private-vault-android-v2`). Any future Google Play Store track must ship a Play flavor omitting `REQUEST_INSTALL_PACKAGES` and GitHub self-update prompts per Google Play policies.

### Ready-to-Paste V2.0.1 Release Notes
```text
Private Vault 2.0.1
- Crash-safe backup & restore hardening with fail-closed recovery and friendly retry dialogs
- 12+ character passphrase guidance for new passwords (full backward compatibility kept for existing vaults and backups)
- Upgraded Google Drive sign-in (Credential Manager + AuthorizationClient) and safer automatic snapshot staging
- Stricter Autofill domain/app matching with manual vault picker fallback
- Share-to-Vault support for saving text/links, smoother OTA permission resume flow, and 31% smaller release APK
```

---

## After Publishing — Confirm Everything Works

1. Open the **Releases** page on GitHub (`tpeditz/Private-vault-android-v2/releases`).
2. Confirm the latest release shows exactly two downloadable files:
   - `private-vault.apk`
   - `update.json`
3. Confirm both direct URLs open in a browser without errors:
   - `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json`
   - `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/private-vault.apk`
4. Open **Private Vault** on your phone → **Settings** → **App Updates** → **Check for Updates**. Users on an older `versionCode` will see the new version, release notes, and verified **Download & Install** prompt.
