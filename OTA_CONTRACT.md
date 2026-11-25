# PRIVATE VAULT V2 - IN-APP OTA UPDATE SPECIFICATION & CONTRACT

> **Product:** Private Vault  
> **Schema Version:** `1`  
> **Application ID:** `com.tpeditz.privatevault.v2`  
> **Official Repository:** `tpeditz/Private-vault-android-v2`  
> **Developer:** Developed by Tp Editz (`@Tp_Editz` / `@tp_ediitz`)

---

## 1. Architecture & Canonical Endpoints

Private Vault features a built-in cryptographic Over-The-Air (OTA) updater designed for zero-infrastructure distribution via GitHub Releases over HTTPS.

### Official Update Endpoints
- **Canonical Latest Update Manifest URL (`BuildConfig.UPDATE_MANIFEST_URL`):**  
  `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json`
- **Canonical Latest APK URL:**  
  `https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/private-vault.apk`
- **Immutable Versioned Release APK URL (`apkUrl` inside `update.json`):**  
  `https://github.com/tpeditz/Private-vault-android-v2/releases/download/v<versionName>/private-vault.apk`

GitHub redirects `releases/latest/download/...` and `releases/download/...` to `objects.githubusercontent.com` / `release-assets.githubusercontent.com`. Both `OtaUpdateManager` HTTPS redirect rules and the GitHub Actions workflow enforce these trusted hosts exclusively.

---

## 2. Schema Version 1 Manifest Contract (`update.json`)

The release workflow generates `update.json` using [`scripts/generate_ota_manifest.py`](file:///C:/Users/hp/.gemini/antigravity/scratch/Private-vault-android-v2/scripts/generate_ota_manifest.py) directly from the verified signed `private-vault.apk`:

```json
{
  "schemaVersion": 1,
  "applicationId": "com.tpeditz.privatevault.v2",
  "versionCode": 2,
  "versionName": "2.0.1",
  "apkUrl": "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.0.1/private-vault.apk",
  "sha256": "3bee0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e",
  "sizeBytes": 16777216,
  "publishedAt": "2026-11-25T14:30:00Z",
  "releaseNotes": "Security, backup reliability, and stability improvements.",
  "signingCertSha256": "3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70"
}
```

### Manifest Field Specifications

| Field | Type | Requirement | Description |
| :--- | :--- | :--- | :--- |
| `schemaVersion` | Integer | Mandatory | Must equal `1`. Unsupported versions are rejected. |
| `applicationId` | String | Mandatory | Must strictly equal `com.tpeditz.privatevault.v2`. Prevents package spoofing. |
| `versionCode` | Integer | Mandatory | Strictly increasing positive integer. Must exceed the installed `versionCode` and match the APK manifest. |
| `versionName` | String | Mandatory | Non-empty semantic version (e.g., `2.0.1`), matching the APK manifest. |
| `apkUrl` | String | Mandatory | Must use `https://` on an allowlisted GitHub host with no embedded credentials. |
| `sha256` | String | Mandatory | 64-character lowercase hex SHA-256 digest of `private-vault.apk`. |
| `sizeBytes` | Long | Optional/Recommended | Exact APK byte length verified before signature check. |
| `publishedAt` | String | Optional | ISO-8601 UTC timestamp generated during release staging. |
| `releaseNotes` | String | Optional | Plain-text release changelog shown to the user before download. |
| `signingCertSha256` | String | Mandatory | Must match the permanent Private Vault release certificate fingerprint. |

---

## 3. Cryptographic Verification Pipeline (`OtaUpdateManager`)

Before handing any downloaded APK to Android's `PackageInstaller`, `OtaUpdateManager` validates:

1. **Trusted HTTPS Transport & Host Pinning:** Rejects HTTP, URL credentials, non-GitHub hosts, and redirect loops (> 3 hops on manifest / > 4 hops on download). Graceful handling of `404` before the first release is published (`"No Release Published Yet"`) and network errors.
2. **File Length & SHA-256 Digest:** Verifies `sizeBytes` (if > 0) and exact SHA-256 match against `manifest.sha256`.
3. **APK Signature (`ApkVerifier`):** Verifies Android v1/v2/v3 APK signatures and checks that the signer certificate SHA-256 matches both `manifest.signingCertSha256` and the trusted Private Vault certificate (plus installed signer certificates).
4. **Package Metadata & Anti-Rollback:** Uses `PackageManager` archive parsing to verify `packageName == com.tpeditz.privatevault.v2`, `apkVersionCode == manifest.versionCode`, matching `versionName`, and `apkVersionCode > installedVersionCode`.
5. **Install Permission (Android 8.0+):** Checks `canRequestPackageInstalls()` and guides the user cleanly via `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` if not yet permitted.

---

## 4. Official Signing Certificate Fingerprints

Every production release APK is verified against these immutable fingerprints inside `.github/workflows/publish-production-apk.yml` and at runtime inside `OtaUpdateManager`:

- **Key Alias:** `privatevault`
- **Certificate SHA-1:** `32:19:54:C3:81:B4:A7:9D:76:B8:90:FF:05:A3:18:D0:21:FB:AD:FC`
- **Certificate SHA-256:** `3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70`
