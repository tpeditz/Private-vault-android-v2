# Private Vault Android — Backup Format & Compatibility Report (`V2.0.4`)

## 1. Supported Formats & Compatibility Matrix

Private Vault `2.0.4` writes privacy-enhanced **`PVAULTB3`** by default for all newly exported local and Google Drive backups, while preserving non-destructive restore compatibility for older **`PVAULTB2`** and **`PVAULTB1`** backups without automatically rewriting user backup files.

> **Version Compatibility Warning:** `PVAULTB3` backups require a compatible Private Vault version (`2.0.4`+) and **must not be assumed readable by earlier releases (`2.0.0`–`2.0.3`)** that predate `PVAULTB3`. Keep pre-existing backups untouched if compatibility with an older build is needed.

| Format | Schema | Export Support | Restore Support | Outer Header | Encrypted Payload Layout |
| :--- | :---: | :--- | :--- | :---: | :--- |
| **`PVAULTB3`** | `3` | **Default** for local `.pvault` and Google Drive backups | Full inspect/stage/restore | **40 B** | Continuous virtual byte stream `[4B manifestLen][manifestJson][assets...]` encrypted as sequential up-to-256 KiB AES-256-GCM frames with `PV3-CHK` AAD binding index, finality, plaintext length, and KDF parameters |
| **`PVAULTB2`** | `2` | Optional legacy writer flag | Full inspect/stage/restore | **56 B** | AES-256-GCM manifest (`manifestCiphertextLength` at `[52..55]`) + sequential `PVM1`/`PVT` payloads |
| **`PVAULTB1`** | `1` | Legacy V1 only | Full inspect/stage/restore (`LegacyV1BackupReader`) | **52 B** | AES-256-GCM JSON container with inline Base64 `mediaBlobs` (`<= 32 MiB`) |

---

## 2. Complete Asset Authenticity Standard on Export & Restore (`R1`)

- Every authoritative export (`VaultRepository.createAuthoritativeStreamingBackup`, covering local `.pvault`, manual cloud upload, foreground auto-stage, and `CloudBackupWorker`) validates:
  1. Database completeness (`getAllItemsForBackupOrThrow()`, rejecting any undecryptable record via `CorruptedVaultRecordsException`).
  2. Authoritative file state (`IMPORT_STATUS_COMMITTED`, file existence, exact byte length, `chunkCount`, `originalSizeBytes`).
  3. **Full AEAD Chunk Authentication:** `StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(...)` verifies every chunk of every `MediaRecord` media file and every `DocumentRecord` file under `(itemId, vaultSlot, fileKey)`.
  4. **Full Thumbnail Authentication:** `StreamingMediaCryptoEngine.verifyEncryptedThumbnailFile(...)` decrypts and authenticates every media thumbnail tag under `(mediaId, vaultSlot, thumbnailKeyBase64)` (for both compact AES-GCM thumbnails and `PVM1`-wrapped thumbnails) and wipes decrypted bytes immediately.
  5. Live-streamed SHA-256 confirmation during write (`feedVerifiedFile` / `streamFileWithLiveShaVerification`) ensuring exported bytes match the authenticated source bytes.

---

## 3. Restore Scope Policy (`R2` & `R3`)

### Explicit Scope Classification
- **Verified Full Backup (`isExportScopeKnown == true && exportScope == ALL_DATA`):**
  - Displays `"Backup Scope: Full vault backup"`.
  - Both non-destructive **Merge (Keep Newest)** and explicitly confirmed **Replace Active** are enabled.
- **Category / Partial Backup (`isExportScopeKnown == true && exportScope != ALL_DATA`):**
  - Displays the category label (`"Notes Only"`, `"Passwords Only"`, `"Documents Only"`, etc.).
  - Strictly blocks `REPLACE_ACTIVE_VAULT` at both the UI (`"Replace Active"` disabled) and repository boundary (`VaultRepository.commitRestore` / `initializeVaultAndCommitRestore`), defaulting to non-destructive merge so unrelated categories and folders are never lost.
  - `DOCUMENTS_ONLY` exports preserve `VaultFolder` entries with `FolderScope.DOCUMENTS` cleanly alongside `DocumentRecord`s; `NOTES_PASSWORDS_LINKS` keeps `NOTES` + `CREDENTIALS` folders without leaking `DOCUMENTS` folders; missing historical folder references never drop restored documents.
- **Unknown-Scope / Legacy Backups (`isExportScopeKnown == false`):**
  - Older `PVAULTB1` files and `PVAULTB2` backups without an explicit `"exportScope"` field are never assumed to be verified full backups.
  - Displays `"Backup Scope: Backup scope unknown"`, recommends **Merge (Recommended)** as the primary action, and keeps explicit replacement behind a reinforced full-erasure warning dialog.

---

## 4. Truthful Finalization, First-Launch Coherency & Privacy Boundaries (`R4`, `R5`, `PV-14`, `PV-16`)

- **First-Launch Recovery (`initializeVaultAndCommitRestore`):** Asset validation, key derivation, and `fd.sync()` durable file staging complete BEFORE a single atomic Room transaction creates the Slot 0/1 envelopes, default folders, and restored items; session unlock happens only after commit.
- **Truthful Outcomes & Prior Backups:** Pending cancellation aborts before final chunk flush / stream close (`outputStream.use`), cleans incomplete SAF targets where provider permissions allow (`cleanupIncompleteDocumentExport`), never deletes a committed backup if a later notification/preference update fails, and keeps pre-existing local/cloud backups untouched on error or cancel.
- **Privacy & RAM Honesty:** `PVAULTB3` hides manifest length, item/asset counts, asset boundaries, and internal `PVM1` headers inside ciphertext, while total encrypted container byte size (and public KDF header profile) remains observable. File I/O buffers remain bounded (`64–256 KiB`), alongside Argon2id KDF working memory (`16–32 MiB`) and manifest JSON (`<= 32 MiB`).
