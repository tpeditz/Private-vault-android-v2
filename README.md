# Private Vault for Android

Private Vault is a native Android application by **Tp Editz** for organizing passwords, notes, photos, videos, documents, secure links, and authenticator entries in a locally encrypted vault.

[Download the latest release](https://github.com/tpeditz/Private-vault-android-v2/releases/latest) · [Official website](https://tpeditz.github.io/Private-vault-android-v2/) · [Privacy policy](https://tpeditz.github.io/Private-vault-android-v2/privacy.html)

| Current stable release | Platform | Package |
| --- | --- | --- |
| [2.1.0](https://github.com/tpeditz/Private-vault-android-v2/releases/tag/v2.1.0) (version code 6) | Android 7.0+ | `com.tpeditz.privatevault.v2` |

## Features

- **Personal encrypted vault:** passwords, private notes, media, documents, folders, and links.
- **Offline-first access:** local vault features work without an internet connection or a Google account.
- **Backup and restore:** encrypted local backups, optional Google Drive backup, automatic backup scheduling, and legacy V1 backup compatibility.
- **Security tools:** password generation and strength guidance, TOTP authenticator, biometric unlock, app locking, and privacy controls.
- **Verified updates:** production-signed APKs, checksum and certificate verification, and a Supabase policy control plane for compatible clients.
- **Remote administration:** authorized administrators can manage release policies, announcements, and feature flags through the separate web dashboard. Feedback submission is currently disabled.

Vault contents are stored separately from operational policy metadata. Online services may process standard network request information as described in the [privacy policy](https://tpeditz.github.io/Private-vault-android-v2/privacy.html). Keep verified backups of important data.

## Project structure

| Directory | Responsibility |
| --- | --- |
| `app/` | Kotlin, Jetpack Compose, local database, encryption, backup, and OTA |
| `admin-web/` | React and TypeScript Admin Control Center |
| `supabase/` | Database migrations and backend Edge Functions |
| `docs/` | Public website and compiled admin website |
| `scripts/` | OTA manifest generation and release verification |
| `.github/workflows/` | Android CI and signed release automation |

## Build and test

Use Android Studio or Java 21 with the included Gradle wrapper and Android SDK. Run from the repository root:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Production builds must use the existing release signing configuration; do not create or commit signing keys.

## Documentation

| Topic | Reference |
| --- | --- |
| Features and latest verification | [Feature checklist](FEATURE_CHECKLIST.md) · [Test report](TEST_REPORT.md) |
| Security and backup formats | [Cryptographic specification](SECURITY_AND_DATA_FORMAT.md) · [Backup compatibility](BACKUP_FORMAT_AND_COMPATIBILITY_REPORT.md) |
| Updates and publishing | [OTA contract](OTA_CONTRACT.md) · [Release setup](RELEASE_SETUP.md) · [Release policy and privacy](RELEASE_POLICY_AND_PRIVACY.md) |
| Google Drive | [Google setup](GOOGLE_SETUP.md) |
| Supabase and hosting | [Deployment guide](SUPABASE_DEPLOYMENT_GUIDE.md) · [Hosting guide](DEPLOYMENT_AND_HOSTING_GUIDE.md) · [Schema compatibility](SUPABASE_SCHEMA_COMPATIBILITY_REPORT.md) |
| Administrator operations and QA | [Admin setup](ADMIN_SETUP_GUIDE.md) · [Admin test checklist](ADMIN_CONTROL_CENTER_TEST_CHECKLIST.md) · [Device test checklist](OWNER_ANDROID_DEVICE_TEST_CHECKLIST.md) |

Superseded development reports are preserved in earlier Git commits. The current test report and the technical guides above are the maintained entry points.

## Developer and support

Developed by **Tp Editz** · [Telegram](https://t.me/Tp_Editz) · [Instagram](https://www.instagram.com/tp_ediitz/)
