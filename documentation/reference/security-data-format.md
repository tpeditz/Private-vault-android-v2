# PRIVATE VAULT V2 - CRYPTOGRAPHIC SPECIFICATION & DATA FORMAT

> **Product:** Private Vault V2 (`2.0.4`, Version Code `5`)  
> **Application ID:** `com.tpeditz.privatevault.v2`  
> **Developer:** Developed by Tp Editz (@Tp_Editz / @tp_ediitz)  
> **Repository:** `tpeditz/Private-vault-android-v2`

---

## 1. Key Derivation Function (Argon2id v1.3)

All password-based key derivation in Private Vault V2 uses **Argon2id v1.3** (RFC 9106) implemented via BouncyCastle (`bcprov-jdk18on`). Argon2id provides state-of-the-art resistance against both GPU/ASIC parallel brute-force attacks and side-channel timing attacks.

### KDF Parameters

| Parameter | Standard Profile | Constrained Profile | Bounds Enforced |
| :--- | :--- | :--- | :--- |
| **Algorithm** | `ARGON2ID_V13` | `ARGON2ID_V13` | Only Argon2id v1.3 accepted |
| **Memory ($m$)** | $32\,768\text{ KiB}$ ($32\text{ MiB}$) | $16\,384\text{ KiB}$ ($16\text{ MiB}$) | $[4\,096\text{ KiB} \dots 131\,072\text{ KiB}]$ |
| **Iterations ($t$)** | $3$ | $4$ | $[1 \dots 12]$ |
| **Parallelism ($p$)**| $2$ | $2$ | $[1 \dots 4]$ |
| **Salt Length** | $16\text{ bytes}$ ($128\text{ bits}$) | $16\text{ bytes}$ | CSPRNG `SecureRandom` |
| **Derived Key Length** | $32\text{ bytes}$ ($256\text{ bits}$) | $32\text{ bytes}$ | Single 256-bit AES Master KEK |

### Password Strength & Legacy Compatibility Policy (`PasswordPolicy`)
- **Minimum New Password Length:** All newly created vault master passwords, decoy passwords, password changes, local backup passwords, and automatic cloud recovery passwords require **12+ characters** (allowing spaces/passphrases and Unicode characters, with 14+ recommended).
- **Zero-Breakage Legacy Support:** Pre-existing `2.0.0` vaults unlocked with shorter legacy passwords, existing `.pvault` backups encrypted with shorter passwords, and previously saved Keystore automatic cloud recovery passwords continue to unlock, restore, and run normally (with an optional upgrade option inside Cloud Backup Settings).

### Unicode NFC Normalization & Legacy Fallback
- Standard derivation automatically normalizes passwords into Unicode **NFC** (Normalization Form Canonical Composition) prior to UTF-8 encoding.
- To guarantee 100% backward compatibility with legacy versions that hashed raw UTF-8 character sequences without NFC normalization, if an unlock attempt fails and `Normalizer.isNormalized(password, Form.NFC)` indicates a distinct non-NFC byte sequence exists, the engine transparently retries derivation with the raw non-NFC byte sequence before reporting an invalid password.

---

## 2. Key Envelope Architecture (`PVE1`)

Both **Slot 0 (Real Vault)** and **Slot 1 (Decoy Vault)** use an identical **72-byte key envelope** stored in SQLite table `vault_envelopes`.

### Inner Envelope Payload Layout (56 Bytes Plaintext)
```text
+-----------------------+-------------------------------------------------------+
| Byte Range            | Content & Type                                        |
+-----------------------+-------------------------------------------------------+
| [0..3] (4 bytes)      | Magic: ASCII "PVE1" (0x50, 0x56, 0x45, 0x31)          |
| [4] (1 byte)          | Slot Role Flag (0x01 = Active Vault, 0x00 = Dummy)    |
| [5] (1 byte)          | Decoy Policy Flag (0x01 = Active, 0x00 = Disabled)    |
| [6..7] (2 bytes)      | Reserved Flags (0x0000)                               |
| [8..15] (8 bytes)     | createdAtEpochMs (Long, Big-Endian)                   |
| [16..23] (8 bytes)    | rotatedAtEpochMs (Long, Big-Endian)                   |
| [24..55] (32 bytes)   | 256-bit Data Encryption Key (DEK)                     |
+-----------------------+-------------------------------------------------------+
```

### Outer Wrapped Envelope (72 Bytes Ciphertext)
- The 56-byte inner envelope is encrypted with **AES-256-GCM** using the derived 256-bit Master Key Encryption Key (`KEK`).
- **Nonce:** 12-byte CSPRNG IV stored in the SQLite column `envelope_nonce`.
- **Authenticated Additional Data (AAD):**
  $$\text{AAD}_{\text{envelope}} = \text{"PV2\_ENVELOPE\_V1:SLOT\_"} + \text{slotId}$$
  - For Slot 0: `PV2_ENVELOPE_V1:SLOT_0`
  - For Slot 1: `PV2_ENVELOPE_V1:SLOT_1`
- **Output:** $56\text{ bytes ciphertext} + 16\text{ bytes GCM tag} = 72\text{ bytes}$.
- **Plausible Deniability:** Both slots occupy the exact same byte length. Without the master password, an observer cannot know whether Slot 1 contains real decoy keys or random dummy noise.

---

## 3. Streaming Media Encryption Format (`PVM1`)

To support large media files (up to several gigabytes) without memory exhaustion or writing unencrypted temporary files to flash storage, Private Vault V2 implements the `PVM1` streaming chunked encryption container.

### Binary Header (32 Bytes)
```text
+-----------------------+-------------------------------------------------------+
| Byte Offset           | Field                                                 |
+-----------------------+-------------------------------------------------------+
| [0..3] (4 bytes)      | ASCII "PVM1" (0x50, 0x56, 0x4D, 0x31)                 |
| [4..5] (2 bytes)      | Format Version (Short, 0x0001)                        |
| [6..7] (2 bytes)      | Flags (Short, 0x0000)                                 |
| [8..11] (4 bytes)     | Nominal Plaintext Chunk Size (Int, 262,144 = 256 KiB) |
| [12..15] (4 bytes)    | Total Chunk Count N (Int, >= 1)                       |
| [16..23] (8 bytes)    | Total Original Plaintext Bytes (Long, >= 1)           |
| [24..31] (8 bytes)    | Reserved (8 zero bytes)                               |
+-----------------------+-------------------------------------------------------+
```

### Chunk Frame Layout
Each chunk $i$ ($0 \le i < N$) is written sequentially:
```text
+-------------------------------+-----------------------------------------------+
| Field                         | Length & Format                               |
+-------------------------------+-----------------------------------------------+
| Plaintext Chunk Length L      | 4 bytes (Int, Big-Endian, 1 <= L <= 262,144)  |
| Chunk Nonce                   | 12 bytes (CSPRNG 96-bit IV)                   |
| Chunk Ciphertext + GCM Tag    | L + 16 bytes (AES-256-GCM)                    |
+-------------------------------+-----------------------------------------------+
```
- **Chunk AAD:**
  $$\text{AAD}_{\text{chunk}} = \text{"PVM1\_CHUNK:idx="} + i + \text{":media="} + \text{mediaId}$$
  Every chunk binds cryptographically to its index and media ID, preventing chunk reordering, truncation, or cross-media chunk splicing.
- **Seekable MediaDataSource:** Android `MediaPlayer` and video decoders seek directly by computing the chunk offset:
  $$\text{chunkIndex} = \lfloor \text{position} / 262\,144 \rfloor$$
  Only the required 256 KiB chunk is read and decrypted into RAM. Zero plaintext touches the disk.

---

## 4. Streaming Backup Containers (`PVAULTB3` & `PVAULTB2`)

Private Vault V2 supports backups up to **2 GiB** (`2,147,483,648` bytes), `50,000` records, and `32 MiB` manifest ciphertext. File payload I/O streams through bounded **$64\text{ KiB}$** (`PVAULTB2`) / **$256\text{ KiB}$** (`PVAULTB3`) buffers so multi-hundred-megabyte media files never need to be loaded into heap all at once. Total process memory during backup/restore still includes the Argon2id KDF working set ($16\text{–}32\text{ MiB}$ during key derivation), the encrypted/decoded manifest JSON (up to $32\text{ MiB}$), and decoded runtime record objects (PV-16).

### 4.1 Privacy-Enhanced Streaming Format (`PVAULTB3`, Default for New Exports)
`PVAULTB3` eliminates unencrypted manifest lengths, item-count/size side-channels, and internal `PVM1` file headers from the container envelope while retaining full streaming bounds and backward compatibility (`PVAULTB1`/`PVAULTB2` backups remain readable without rewriting old user files).

#### Public Binary Header Layout (40 Bytes)
```text
+-----------------------+-------------------------------------------------------+
| Byte Range            | Content                                               |
+-----------------------+-------------------------------------------------------+
| [0..7] (8 bytes)      | Magic: ASCII "PVAULTB3"                               |
| [8..11] (4 bytes)     | backupSchemaVersion (Int, 3)                          |
| [12..15] (4 bytes)    | kdfMemoryKb (Int)                                     |
| [16..19] (4 bytes)    | kdfIterations (Int)                                   |
| [20..23] (4 bytes)    | kdfParallelism (Int)                                  |
| [24..39] (16 bytes)   | backupKdfSalt (16 bytes)                              |
+-----------------------+-------------------------------------------------------+
```

#### Authenticated Chunked Inner Stream (`[40..EOF]`)
Everything after byte `40` is packaged into one continuous virtual byte stream `[4B manifestLen][manifestJson][asset0][asset1]...` and encrypted sequentially in uniform up-to-$256\text{ KiB}$ AES-256-GCM frames:
```text
+-------------------------------+-----------------------------------------------+
| Field                         | Length & Format                               |
+-------------------------------+-----------------------------------------------+
| Plaintext Length L            | 4 bytes (Int, 1 <= L <= 262,144)              |
| IsFinal Flag                  | 1 byte (0x01 only on final frame, else 0x00)  |
| Frame Nonce                   | 12 bytes (CSPRNG 96-bit IV)                   |
| Ciphertext + GCM Tag          | L + 16 bytes (AES-256-GCM)                    |
+-------------------------------+-----------------------------------------------+
```
- **Frame AAD:** `"PV3-CHK|ver=3|idx=<chunkIndex>|last=<0|1>|len=<plainLen>|m=<kdfMemoryKb>|t=<kdfIterations>|p=<kdfParallelism>"` — binds every chunk to its sequence position, finality, exact plaintext length, and header KDF profile, preventing chunk reordering, insertion, replay, or truncation.
- **What `PVAULTB3` hides:** Encrypted manifest length, record counts, individual asset counts/sizes, payload boundaries, and internal `PVM1` media/document headers.
- **Publicly observable metadata:** Container magic `"PVAULTB3"`, schema version `3`, public KDF header parameters (`kdfMemoryKb`, `kdfIterations`, `kdfParallelism`, 16-byte salt), and approximate total container size rounded up by per-frame framing overhead (`33` bytes per frame).

### 4.2 Legacy Streaming Format (`PVAULTB2`, Readable & Compatible)
For backups produced prior to V2.0.4 `PVAULTB3`:
- **Header (56 Bytes):** Magic `"PVAULTB2"`, `backupSchemaVersion = 2`, KDF parameters, 16-byte salt, 12-byte manifest nonce, and 4-byte `manifestCiphertextLength`.
- **Stream:** AES-256-GCM manifest (`PV2_BACKUP_V2:MANIFEST`) followed sequentially by authenticated `PVM1` / `PVT1` payloads validated against manifest sizes and SHA-256 digests during staging before the atomic Room transaction executes.

---

## 5. Cloud Recovery Password & Automatic Backup Boundary (`AutoCloudBackupKeyStore`)
- Manual and scheduled Google Drive backups encrypt the exact same zero-knowledge `PVAULTB3` container before any bytes leave the device (`multipart/related` upload to `appDataFolder`), while preserving restore support for existing `PVAULTB2` and `PVAULTB1` cloud backups.
- Scheduled WorkManager backups (`CloudBackupWorker`) NEVER invent ephemeral/unrecoverable passwords and NEVER decrypt vault contents while locked. They require a user-chosen Cloud Recovery Password wrapped in `AndroidKeyStore` (`AES/GCM/NoPadding`) and stage encrypted `.pvault` snapshots only during an active unlocked Slot 0 session.

---

## 6. Legacy V1 Backup Compatibility (`PVAULTB1`)

The built-in `LegacyV1BackupReader` reads backups produced by Private Vault 1.0.x:
- **Header (52 bytes):** Magic `"PVAULTB1"`, schema version 1, KDF parameters, 16B salt, 12B nonce.
- **AAD:** `PV1_PORTABLE_BACKUP:SCHEMA_1`.
- **Payload:** JSON document containing records and Base64-encoded `mediaBlobs`.
- Automatically maps legacy notes and credentials into V2 domain entities and restores media files into `PVM1` storage.

---

## 7. Threat Model & Boundaries

| Threat Vector | Mitigation in Private Vault V2 |
| :--- | :--- |
| **Physical Device Seizure** | Database is encrypted with AES-256-GCM. Without the master password, SQLite entries appear as pseudorandom noise. Memory keys are wiped upon lock. |
| **Coercion / Duress** | User can enter the Decoy Master Password. The app unlocks Slot 1 and shows only benign decoy data, leaving Slot 0 completely hidden. |
| **System Cache / Task Switcher** | `FLAG_SECURE` is active by default, blocking Android OS screenshots, recents screen previews, and display mirroring. |
| **Malicious Background Apps** | Sensitive clipboard content is marked with Android 13+ `EXTRA_IS_SENSITIVE` and automatically purged after 30 seconds. |
| **Malicious OTA Interception** | OTA manifest downloads require HTTPS, reject downgrade version codes, and mandate signature verification via `ApkVerifier` matching release certificate SHA-256. |
| **CSV Formula Injection** | CSV imports sanitize formula characters (`=`, `+`, `-`, `@`, `\t`, `\r`) with single-quote escaping before saving. |
| **Cloud Telemetry Leaks** | Zero analytics libraries, zero crashlytics, zero ad networks. Google Drive sync operates strictly in client-side zero-knowledge mode. |
