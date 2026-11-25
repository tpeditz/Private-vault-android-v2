# PRIVATE VAULT V2 (Android)

> **Zero-Knowledge, Offline-First Cryptographic Vault & Password Manager for Android**  
> **Version:** `2.0.0` (Version Code: `1`)  
> **Application ID:** `com.tpeditz.privatevault.v2`  
> **Developed by:** **Tp Editz**  
> **Official Contact:** Telegram [@Tp_Editz](https://t.me/Tp_Editz) | Instagram [@tp_ediitz](https://www.instagram.com/tp_ediitz/)  
> **Official GitHub Repository:** [https://github.com/tpeditz/Private-vault-android-v2](https://github.com/tpeditz/Private-vault-android-v2) (Branch: `main`)

---

## 1. Overview & Architecture

**Private Vault** is a native Android application built with **Jetpack Compose**, **Material 3**, and modern local-first cryptography. Engineered for complete offline-first sovereignty, Private Vault never transmits unencrypted data, contains zero third-party analytics trackers, and operates with zero telemetry.

### Core Architecture Highlights
- **Jetpack Compose & Material 3:** Modern declarative UI with fluid navigation, Light/Dark/OLED Black themes, and edge-to-edge support.
- **Argon2id v1.3 (RFC 9106) Key Derivation:** Memory-hard password hashing (`32 MiB` standard / `16 MiB` constrained) via BouncyCastle, with Unicode NFC normalization and legacy fallback.
- **Unified 72-Byte Key Envelopes (`PVE1`):** Slot 0 (Real Vault) and Slot 1 (Decoy Vault) share an identical 72-byte ciphertext footprint in SQLite with zero plaintext flags, guaranteeing plausible deniability.
- **Streaming Media & Document Encryption (`PVM1` / `PVT1`):** Chunked 256 KiB AES-256-GCM encryption for photos, videos, thumbnails, and documents, with a custom seekable `MediaDataSource` that streams plaintext directly in memory.
- **PVAULTB2 Streaming Backup & Crash-Safe Restore:** Supports up to 2 GiB containers with bounded streaming I/O, per-file SHA-256 + `thumbSha256` verification, file-first durable disk writes (`fd.sync()`), atomic Room transactions, and cross-slot crash recovery.
- **Legacy V1 Backward Compatibility:** `LegacyV1BackupReader` restores original `PVAULTB1` containers, legacy Unicode passwords, and embedded media payloads.
- **Strict Android Autofill Service:** Native `AutofillService` with strict exact/subdomain host matching and noise-filtered package token matching (`AutofillHelper`), zero fallback to unrelated credentials, and API 24+ graceful handling.
- **Encrypted Cloud Backup (Optional):** Zero-knowledge Google Drive `appDataFolder` sync (`multipart/related`), hardware AndroidKeyStore-protected recovery passwords (`AutoCloudBackupKeyStore`) for WorkManager scheduling, and plain-English controls.
- **Local Authenticator (2FA TOTP) & Password Audit:** RFC 6238 / RFC 4226 TOTP engine with 30-second countdown, offline QR scanning, CSPRNG password/passphrase generator, and offline reuse/strength auditing.
- **Cryptographic OTA Updates & CI/CD:** Checks `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json`, verifying SHA-256 checksums, version monotonicity, `ApkVerifier` v1/v2/v3 signing certificates, and automated signed releases via `.github/workflows/`.

---

## 2. Toolchain & Requirements

| Component | Specification |
| :--- | :--- |
| **Language** | Kotlin `2.2.10` |
| **Android Gradle Plugin** | AGP `9.1.1` |
| **Gradle** | Gradle `9.3.1` (wrapper included) |
| **Java Development Kit** | JDK `17`, `21`, or `25` |
| **Minimum SDK** | Android 7.0 (`API 24`) |
| **Target SDK** | Android (`API 36`) |
| **Database** | AndroidX Room `2.7.0` (Schema version `5`) |
| **Cryptography** | BouncyCastle `1.80` (`bcprov-jdk18on`) & Android Keystore |

---

## 3. Project Directory Structure

```text
Private-vault-android-v2/
├── .github/workflows/
│   ├── ci.yml                                 # Push/PR unit tests, lint, debug & androidTest compile
│   └── publish-production-apk.yml             # Manual test/publish signed release & update.json pipeline
├── scripts/
│   └── generate_ota_manifest.py               # Schema v1 update.json generator
├── app/
│   ├── build.gradle.kts                       # App build script, flavors/signing env wiring
│   ├── proguard-rules.pro                     # Proguard/R8 shrinking rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/tpeditz/privatevault/v2/
│       │   │   ├── MainActivity.kt
│       │   │   ├── autofill/
│       │   │   │   ├── AutofillAuthActivity.kt
│       │   │   │   ├── AutofillHelper.kt
│       │   │   │   └── PrivateVaultAutofillService.kt
│       │   │   ├── data/
│       │   │   │   ├── AppPreferencesRepository.kt
│       │   │   │   ├── EncryptedBackupV2Engine.kt
│       │   │   │   ├── LegacyV1BackupReader.kt
│       │   │   │   ├── PortableBackupModels.kt
│       │   │   │   ├── PrivateVaultDatabase.kt
│       │   │   │   ├── VaultDao.kt
│       │   │   │   ├── VaultDomainModels.kt
│       │   │   │   ├── VaultEntities.kt
│       │   │   │   ├── VaultRecordCodec.kt
│       │   │   │   └── VaultRepository.kt
│       │   │   ├── ota/
│       │   │   │   ├── OtaModels.kt
│       │   │   │   └── OtaUpdateManager.kt
│       │   │   ├── security/
│       │   │   │   ├── BiometricKeystoreManager.kt
│       │   │   │   ├── CryptoEngine.kt
│       │   │   │   ├── OfflinePasswordStrengthEstimator.kt
│       │   │   │   ├── PasswordGeneratorEngine.kt
│       │   │   │   ├── SafeVaultLogger.kt
│       │   │   │   ├── SensitiveClipboardManager.kt
│       │   │   │   ├── StreamingMediaCryptoEngine.kt
│       │   │   │   ├── TotpEngine.kt
│       │   │   │   └── VaultSessionManager.kt
│       │   │   ├── sync/
│       │   │   │   ├── AutoCloudBackupKeyStore.kt
│       │   │   │   ├── CloudBackupScheduler.kt
│       │   │   │   ├── CloudBackupWorker.kt
│       │   │   │   ├── GoogleDriveAuthHelper.kt
│       │   │   │   └── GoogleDriveSyncManager.kt
│       │   │   └── ui/
│       │   │       ├── VaultNavigation.kt
│       │   │       ├── VaultUiState.kt
│       │   │       ├── VaultViewModel.kt
│       │   │       ├── screens/
│       │   │       └── theme/
│       │   └── res/
│       ├── androidTest/java/com/tpeditz/privatevault/v2/  # Room, DataStore & Compose instrumentation tests
│       └── test/java/com/tpeditz/privatevault/v2/         # 13 JVM unit & regression test suites (81 tests)
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── DEFECT_RESOLUTION.md
├── FEATURE_CHECKLIST.md
├── GOOGLE_SETUP.md
├── OTA_CONTRACT.md
├── RELEASE_SETUP.md
├── SECURITY_AND_DATA_FORMAT.md
└── TEST_REPORT.md
```

---

## 4. Build & Verification Commands

```bash
# Run all 13 unit & repository regression suites (81 tests)
./gradlew :app:testDebugUnitTest

# Run Android Lint verification (0 errors)
./gradlew :app:lintDebug

# Compile Compose & Room AndroidX instrumentation tests
./gradlew :app:compileDebugAndroidTestKotlin

# Build Debug APK
./gradlew :app:assembleDebug

# Build Release APK (signed automatically when signing env vars or GitHub Secrets are provided)
./gradlew :app:assembleRelease
```

For non-programmer step-by-step GitHub publishing instructions (`test` dry-run vs `publish` release mode), read [RELEASE_SETUP.md](RELEASE_SETUP.md).

---

## 5. Author & Support

- **Developer:** **Tp Editz**
- **Telegram:** [@Tp_Editz](https://t.me/Tp_Editz)
- **Instagram:** [@tp_ediitz](https://www.instagram.com/tp_ediitz/)
- **Repository:** [https://github.com/tpeditz/Private-vault-android-v2](https://github.com/tpeditz/Private-vault-android-v2)
