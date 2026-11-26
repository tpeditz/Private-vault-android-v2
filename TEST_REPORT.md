# Private Vault Android V2.0.4 — Verification & Test Report

**Build Target:** `com.tpeditz.privatevault.v2` (`versionName = 2.0.4`, `versionCode = 5`, `minSdk = 24`, `targetSdk = 36`)  
**Workspace:** `Private-vault-android-v2-0-4-candidate` (built on `Private-vault-android-v2.0.4-backup-privacy-repair-source.zip`, SHA-256 `5e1580a4a403b9d73f955edeb570c0b844a8dbdd932f65bb22c325d2160ca0fe`)  
**Environment:** Windows, Java `openjdk version "25.0.5.1" 2026-11-17 LTS`, Gradle `9.3.1` (`--no-daemon`), Android `compileSdk = 36`

---

## 1. Command Execution Summary

Executed cleanly after all R1–R6 source, documentation, and test edits:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease --no-daemon
```

- **Build Result:** `BUILD SUCCESSFUL in 10m 11s` (`112` actionable tasks: `31` executed, `81` up-to-date)
- **JVM Unit Tests (`:app:testDebugUnitTest`):** `21` suites, `126` tests run, `126` passed, `0` failed, `0` errors, `0` skipped (`121` carried forward + `5` new R1–R5 tests; zero tests weakened or removed).
- **Android Lint (`:app:lintDebug`):** `0` errors / fatals, `74` warnings, `0` info (`app/build/reports/lint-results-debug.html` / `.xml`).
- **Debug APK (`:app:assembleDebug`):** Built (`25,974,609` bytes).
- **Release APK (`:app:assembleRelease`):** Built with R8 minification (`4,414,877` bytes, **unsigned** ready for CI/private keystore signing).
- **Android Instrumentation Compilation (`:app:compileDebugAndroidTestKotlin`):** Compiled cleanly.
- **Physical Device / Emulator Instrumentation (`connectedDebugAndroidTest`):** **Pending — NOT executed locally** (`adb.exe devices -l` reported `0` attached devices). Live device UI runs, SAF document providers, Google Drive live network upload/download, and hardware BiometricPrompt unlock remain to be executed on a real phone/emulator.

---

## 2. JVM Unit Test Suite Breakdown (126 / 126 Passing)

| Test Suite | Tests | Passed | Failed | Skipped | Time |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `BackupPrivacyAndIntegrityV3RegressionTest` *(4 prior + 5 R1–R5 tests)* | 9 | 9 | 0 | 0 | 4.127s |
| `EncryptedBackupV2EngineTest` | 4 | 4 | 0 | 0 | 0.423s |
| `LegacyV1BackupReaderTest` | 5 | 5 | 0 | 0 | 2.932s |
| `LocalBackupAndRestoreIntegrationTest` | 7 | 7 | 0 | 0 | 0.717s |
| `V204HardeningRegressionTest` | 8 | 8 | 0 | 0 | 2.114s |
| `VaultFileSafetyTest` | 2 | 2 | 0 | 0 | 0.029s |
| `VaultRecordCodecTest` | 9 | 9 | 0 | 0 | 0.028s |
| `VaultRepositoryRestoreRegressionTest` | 11 | 11 | 0 | 0 | 0.982s |
| `VaultRepositorySafetyHardeningTest` | 2 | 2 | 0 | 0 | 0.121s |
| `OtaManifestTest` | 10 | 10 | 0 | 0 | 1.072s |
| `CryptoEngineTest` | 12 | 12 | 0 | 0 | 0.241s |
| `OfflinePasswordStrengthEstimatorTest` | 6 | 6 | 0 | 0 | 0.011s |
| `PasswordGeneratorEngineTest` | 5 | 5 | 0 | 0 | 0.141s |
| `PasswordPolicyTest` | 2 | 2 | 0 | 0 | 0.139s |
| `StreamingMediaCryptoEngineTest` | 7 | 7 | 0 | 0 | 0.791s |
| `TotpEngineTest` | 9 | 9 | 0 | 0 | 0.052s |
| `VaultSessionPolicyTest` | 2 | 2 | 0 | 0 | 0.008s |
| `CloudBackupSchedulerAndSyncTest` *(incl. Drive 308 regressions)* | 9 | 9 | 0 | 0 | 2.107s |
| `AutofillRemovalAndPasswordManagerRegressionTest` | 2 | 2 | 0 | 0 | 0.100s |
| `DocumentExportCleanupTest` *(preserved)* | 3 | 3 | 0 | 0 | 0.008s |
| `VaultErrorMapperTest` | 2 | 2 | 0 | 0 | 0.028s |
| **TOTAL (21 Suites)** | **126** | **126** | **0** | **0** | — |

---

## 3. Compiled Instrumentation Tests (Pending Device Execution)

Compiled cleanly via `:app:compileDebugAndroidTestKotlin` (not yet run on Android hardware):
- `PrivacyAndSecurityUiAndroidTest.kt`: now includes Notes explicit-save, pending checklist input preservation across Save & Unsaved-Changes Dialog, `BackupRestoreScreen` partial-backup Replace disablement, and unknown-scope `"Merge (Recommended)"` verification in addition to prior About/Settings/Cloud/PasswordHistory/No-Autofill tests.
- `RoomVaultRepositoryRecoveryAndroidTest.kt`
- `PreferencesPersistenceAndroidTest.kt`
- `ShareToVaultAndScreensAndroidTest.kt`

---

## 4. Build Artifacts & Static Verification

- **Debug APK (`app-debug.apk`):**
  - Path: `app/build/outputs/apk/debug/app-debug.apk`
  - Size: `25,974,609` bytes
  - Signing: Android debug signing certificate
  - SHA-256: `6847603025f0cafb625a33903b5102888c9615058c5e0c24b227c1abe82f4018`
- **Release APK (`app-release-unsigned.apk`):**
  - Path: `app/build/outputs/apk/release/app-release-unsigned.apk`
  - Size: `4,414,877` bytes
  - Signing: **Unsigned** (no production keystore used locally; intended for CI/owner keystore signing)
  - SHA-256: `df7dcb6a4b6e6e56a28a25ef5fc4da18c184672e8bfb836fe3b8bee2ba78feaf`
- **Badging (`aapt2 dump badging` on Release APK):**
  - `package: name='com.tpeditz.privatevault.v2' versionCode='5' versionName='2.0.4' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'`
  - `targetSdkVersion:'36'`
- **Autofill Removal Check (`app/src/main`):** `0` references to `AutofillService` or `android.service.autofill`.
