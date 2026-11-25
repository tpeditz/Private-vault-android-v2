# GOOGLE DRIVE CLOUD SYNC SETUP GUIDE

> **Product:** Private Vault V2  
> **Target Scope:** Google Drive `appDataFolder` (Zero-Knowledge Encrypted Snapshots)  
> **Application ID:** `com.tpeditz.privatevault.v2`  
> **Developer:** Developed by Tp Editz

---

## 1. Zero-Knowledge Cloud Storage Overview

Private Vault V2 supports optional cloud backup to your personal Google Drive account. 
Here is how your privacy is guaranteed:
- **Hidden App Data Folder:** Backups are stored in Google Drive's hidden `appDataFolder` directory. This directory is completely invisible in your normal Google Drive file list and cannot be accessed or viewed by any other third-party apps or services.
- **Client-Side AES-256-GCM Encryption:** Every snapshot uploaded to Google Drive is a standard `PVAULTB2` container encrypted on your device using **Argon2id v1.3** and **AES-256-GCM**.
- **Zero Knowledge:** Google never receives your master password, your encryption keys, or your unencrypted vault records. Even if someone obtains access to your Google account, they cannot open or read your vault without your master password.

---

## 2. Step-by-Step Google Cloud Console Setup

To enable Google Sign-In and Drive cloud backup in your build of Private Vault V2, follow these simple steps:

### Step 1: Open Google Cloud Console
1. Navigate to [Google Cloud Console](https://console.cloud.google.com/).
2. Sign in with your Google account.
3. Click the project dropdown at the top of the page and click **New Project**.
4. Name your project (for example: `Private-Vault-Sync`) and click **Create**.

### Step 2: Enable the Google Drive API
1. In the left navigation menu, go to **APIs & Services** > **Library**.
2. In the search box, type `Google Drive API`.
3. Click on **Google Drive API** and click the **Enable** button.

### Step 3: Configure the OAuth Consent Screen
1. In the left navigation menu, go to **APIs & Services** > **OAuth consent screen**.
2. Select **External** as the User Type and click **Create**.
3. Fill in the required fields:
   - **App name:** `Private Vault`
   - **User support email:** Select your email address.
   - **Developer contact information:** Enter your email address.
4. Click **Save and Continue**.
5. Under **Scopes**, click **Add or Remove Scopes**.
   - Search for `drive.appdata` (Description: `View and manage its own configuration data in your Google Drive`).
   - Check the box for `https://www.googleapis.com/auth/drive.appdata`.
   - Click **Update** and then **Save and Continue**.
6. Under **Test Users**, click **Add Users** and add your own Google email address (the account you will use on your test Android device).
7. Click **Save and Continue** and complete the consent screen configuration.

### Step 4: Configure OAuth 2.0 Client IDs
Private Vault uses **AndroidX Credential Manager** (`GetSignInWithGoogleOption` / `GoogleIdTokenCredential`) for Google identity selection and **Google Identity Services `AuthorizationClient`** (`Identity.getAuthorizationClient`) for `drive.appdata` OAuth consent. You will configure two OAuth client IDs in Google Cloud Console:

#### A. Web Client ID (Required in code)
1. Go to **APIs & Services** > **Credentials**.
2. Click **+ Create Credentials** > **OAuth client ID**.
3. Set **Application type** to **Web application**.
4. Name it `Private Vault Web Client`.
5. Click **Create**.
6. A dialog will appear displaying your **Client ID** (it looks like `1234567890-abcdefghijklmn.apps.googleusercontent.com`).
7. **Copy this Web Client ID string.**

#### B. Android Client ID (Links your signing key)
1. In **APIs & Services** > **Credentials**, click **+ Create Credentials** > **OAuth client ID**.
2. Set **Application type** to **Android**.
3. Name it `Private Vault Android Client`.
4. In **Package name**, enter:
   ```text
   com.tpeditz.privatevault.v2
   ```
5. In **SHA-1 certificate fingerprint**, enter the official release signing SHA-1 fingerprint:
   ```text
   32:19:54:C3:81:B4:A7:9D:76:B8:90:FF:05:A3:18:D0:21:FB:AD:FC
   ```
   *(Note: If you run a debug build, you can also add your debug keystore SHA-1).*
6. Click **Create**.

---

## 3. Configuring the Web Client ID in the Code

In `app/build.gradle.kts`, the official registered Google Web OAuth Client ID is pre-configured by default:
```kotlin
val googleWebClientId: String = (project.findProperty("GOOGLE_WEB_CLIENT_ID") as? String)
    ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
    ?: "789457169216-dgnbo4u4vp835s2thkj6aa440tvhfcpt.apps.googleusercontent.com"
```

To override this with a custom or personal Web Client ID, you can either:
1. Pass `-PGOOGLE_WEB_CLIENT_ID="<your-client-id>"` during Gradle build.
2. Set the `GOOGLE_WEB_CLIENT_ID` environment variable.
3. Edit the fallback string directly in `app/build.gradle.kts`.

Then rebuild the application:
```bash
./gradlew assembleDebug
```

---

## 4. Behavior When Unconfigured

If `GOOGLE_WEB_CLIENT_ID` remains unconfigured:
- The app operates in complete offline mode.
- In **Settings** > **Cloud Backup**, the user sees an informative card explaining:
  > *"Cloud sync requires setting your Google Cloud Web Client ID in `app/build.gradle.kts`. Offline local backups and streaming `.pvault` exports remain fully operational."*
- Zero network calls are made to Google servers, and the application remains 100% functional for all local vault features.
