<div align="center">
  <img src="docs/logo.png" alt="Private Vault logo" width="104">

  <h1>Private Vault</h1>

  <p><strong>Your privacy, in your hands.</strong></p>
  <p>A native Android vault for passwords, notes, media and important files.</p>

  <a href="https://github.com/tpeditz/Private-vault-android-v2/releases/latest"><img src="https://img.shields.io/github/v/release/tpeditz/Private-vault-android-v2?style=flat-square&label=Release&color=555555" alt="Latest release"></a>
  <a href="https://github.com/tpeditz/Private-vault-android-v2/actions/workflows/ci.yml"><img src="https://github.com/tpeditz/Private-vault-android-v2/actions/workflows/ci.yml/badge.svg?branch=main" alt="Android build and test status"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-555555?style=flat-square" alt="Android 7.0 and newer">

  <p>
    <a href="https://github.com/tpeditz/Private-vault-android-v2/releases/latest"><strong>Download APK</strong></a>
    &nbsp;·&nbsp;
    <a href="https://tpeditz.github.io/Private-vault-android-v2/">Website</a>
    &nbsp;·&nbsp;
    <a href="https://tpeditz.github.io/Private-vault-android-v2/privacy.html">Privacy policy</a>
    &nbsp;·&nbsp;
    <a href="documentation/README.md">Documentation</a>
  </p>
</div>

---

## Overview

Private Vault is an offline-first Android application developed by **Tp Editz**. Organize personal information in a locally encrypted vault, with optional encrypted backup to your own Google Drive account. A Google account is not required for local vault use.

## Features

| Vault | Privacy & utilities |
| :--- | :--- |
| Passwords, private notes and folders | Local encrypted storage |
| Photos, videos, albums and documents | Biometric unlock and configurable app lock |
| Secure links and authenticator entries | Password generator and strength guidance |
| Search and record organization | Local backup and optional Google Drive backup |
| Backup restoration and recovery | Verified in-app update checks |

## Download

1. Open the [latest official release](https://github.com/tpeditz/Private-vault-android-v2/releases/latest).
2. Download `private-vault.apk` and follow Android's installation prompts.
3. Create your vault or restore an existing compatible encrypted backup.

**Android 7.0 (API 24) or newer.** Keep an independently verified encrypted backup before updating. Use only the official release page or the application's built-in verified update flow.

## Development

The Android application uses **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, and the Android Keystore. The separate administration frontend uses **React**, **TypeScript**, and **Vite**, backed by Supabase.

Use Android Studio, Android SDK, and JDK 21. The included Gradle wrapper makes the Android build reproducible.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Debug builds are for development and testing, not production distribution. See [release setup](documentation/guides/release-setup.md) for the existing production-signing procedure.

### Repository layout

| Directory | Purpose |
| :--- | :--- |
| [`app/`](app/) | Android application and automated tests |
| [`admin-web/`](admin-web/) | Administration frontend |
| [`supabase/`](supabase/) | Backend configuration, migrations and functions |
| [`docs/`](docs/) | Public website and compiled admin site |
| [`documentation/`](documentation/) | Technical guides, specifications and QA references |
| [`scripts/`](scripts/) | Release verification utilities |
| [`.github/workflows/`](.github/workflows/) | Continuous integration and release workflows |

## Documentation

- [Developer and setup guides](documentation/README.md#guides)
- [Security and data formats](documentation/reference/security-data-format.md)
- [Backup compatibility](documentation/reference/backup-compatibility.md)
- [OTA update contract](documentation/reference/ota-contract.md)
- [Quality assurance and verification](documentation/README.md#quality-assurance)

Automated test code remains with the modules it tests. Operational guides and verification records are organized separately from the application source.

## Privacy & support

Vault content is stored separately from remote operational policy metadata. Cloud backup is optional. Online services may handle standard network request information as described in the [Privacy Policy](https://tpeditz.github.io/Private-vault-android-v2/privacy.html). Feedback submission in the current stable app is disabled.

Developed by **Tp Editz**.

[Telegram](https://t.me/Tp_Editz) · [Instagram](https://www.instagram.com/tp_ediitz/) · [Official website](https://tpeditz.github.io/Private-vault-android-v2/)
