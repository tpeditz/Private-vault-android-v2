# Private Vault Android V2.0.4 — Final Targeted Reliability Repair Report (R1–R6)

- **Baseline Archive:** `Private-vault-android-v2.0.4-backup-privacy-repair-source.zip` (verified SHA-256 `5e1580a4a403b9d73f955edeb570c0b844a8dbdd932f65bb22c325d2160ca0fe`)
- **Product Identity Preserved:** `Private Vault`, `versionName = "2.0.4"`, `versionCode = 5`, `com.tpeditz.privatevault.v2` (`minSdk = 24`, `targetSdk = 36`)
- **Preserved Prior Work (121/121 Baseline Retained & Extended to 126/126):**
  - `PVAULTB3` privacy-enhanced streaming container and `PVAULTB1` / `PVAULTB2` restore readers
  - `GoogleDriveSyncManager` HTTP `308` missing-`Range` (`0L`) confirmed-offset repair and `CloudBackupSchedulerAndSyncTest` (`9/9`)
  - `DocumentExportCleanup` preserving completed exports and `DocumentExportCleanupTest` (`3/3`)
  - Explicit manual Note saving, `TotpEngine` URI/parameter parsing, media stream cleanup, and complete Autofill removal (`0` refs in `app/src/main`)

---

## Repair Summary (`R1` – `R6`)

### R1 — Complete Backup Asset Integrity (Media, Documents & Thumbnails)
- **Issue:** Backup export verified media/document container headers and file SHA-256s, but thumbnails were only SHA-256-hashed (without verifying the thumbnail AEAD authentication tag against `(mediaId, vaultSlot, thumbnailKeyBase64)`), and `PVM1` media/document frames were not AEAD-authenticated on every chunk before export.
- **Changed Files:**
  - `app/src/main/java/com/tpeditz/privatevault/v2/security/StreamingMediaCryptoEngine.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/EncryptedBackupV2Engine.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/VaultRepository.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/VaultErrorMapper.kt`
- **Correction:**
  - Extracted shared bounded helper `StreamingMediaCryptoEngine.verifyEncryptedThumbnailFile(...)` (supporting both compact AEAD thumbnails and `PVM1` chunked thumbnails, wiping plaintext bytes immediately) and added cancellable chunk loop support to `verifyEncryptedMediaFileEveryChunk(...)`.
  - Passed `vaultSlotForAuth = slotId` from `VaultRepository.createAuthoritativeStreamingBackup(...)` (used by local SAF export, manual Google Drive upload, foreground pending snapshot staging, and `CloudBackupWorker`).
  - `EncryptedBackupV2Engine.prepareVerifiedBackupExport` now decodes and validates `fileMediaKeyBase64`, `thumbnailKeyBase64`, and `fileKeyBase64`, authenticates every media/document chunk and thumbnail AEAD payload under its slot/ID context, confirms streamed SHA-256 equality while streaming, and maps `GeneralSecurityException` to safe user messages without leaking plaintext or touching prior backups.
- **Verification & Limitations:** Verified by `BackupPrivacyAndIntegrityV3RegressionTest.testAuthoritativeBackupRejectsTamperedThumbnailEvenWithSameByteLength` and `testRepositoryRejectsCorruptedRecordAndAssetMismatchDuringBackup` (JVM). Full end-to-end SAF / WorkManager hardware trigger remains pending physical phone testing.

---

### R2 — Safe Restore of Partial / Category Backups & Unknown Legacy Scope Policy
- **Issue:** `EncryptedBackupV2Engine.parseAndValidateManifestBytes` defaulted missing/unknown `exportScope` values to `BackupExportScope.ALL_DATA`, and `Replace Active` was selectable even for single-category exports.
- **Changed Files:**
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/PortableBackupModels.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/EncryptedBackupV2Engine.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/LegacyV1BackupReader.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/VaultRepository.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/VaultViewModel.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/screens/BackupRestoreScreen.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/screens/SetupAndUnlockScreens.kt`
- **Correction:**
  - Added `isExportScopeKnown: Boolean`, `isVerifiedFullBackup`, `isPartialCategoryBackup`, and `scopeDisplayLabel` (`"Full vault backup"`, category label, or `"Backup scope unknown"`) to `BackupPayload`.
  - Older `PVAULTB1` and un-scoped `PVAULTB2` backups are flagged `isExportScopeKnown = false` instead of being silently treated as full backups.
  - Enforced policy in both `VaultRepository.commitRestore` / `initializeVaultAndCommitRestore` and `VaultViewModel.confirmRestore`: partial category backups strictly forbid `REPLACE_ACTIVE_VAULT` (preserving unrelated categories/folders) and merge non-destructively.
  - `BackupRestoreScreen` displays `"Backup Scope: ..."`, disables `"Replace Active"` for partial backups, and highlights `"Merge (Recommended)"` with explicit replacement warnings for unknown-scope legacy backups.
- **Verification & Limitations:** Repository/engine behavior verified by `BackupPrivacyAndIntegrityV3RegressionTest.testCategoryBackupBlocksReplaceActiveAndLegacyUnknownScopeIsPreserved` (JVM); Compose UI preview states added in `PrivacyAndSecurityUiAndroidTest.kt` (compiled cleanly, pending physical device execution).

---

### R3 — Document Folder Preservation & Scoped Filtering
- **Issue:** `BackupExportScope.DOCUMENTS_ONLY` excluded `VaultFolder` entries with `FolderScope.DOCUMENTS`, and `NOTES_PASSWORDS_LINKS` leaked `FolderScope.DOCUMENTS` folders.
- **Changed Files:**
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/PortableBackupModels.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/VaultViewModel.kt`
- **Correction:**
  - Extracted shared `filterItemsForBackupScope(allItems, scope)` in `PortableBackupModels.kt` so `DOCUMENTS_ONLY` preserves `(it is VaultFolder && it.scope == FolderScope.DOCUMENTS)` alongside `DocumentRecord`s, while `NOTES_PASSWORDS_LINKS` excludes `DOCUMENTS` folders and `MEDIA_ONLY` preserves `VaultAlbumRecord`s cleanly.
  - Verified `MediaScreen.DocumentsSection` renders all restored documents safely even if a historical `folderId` folder record is absent in an older backup.
- **Verification:** Verified by `BackupPrivacyAndIntegrityV3RegressionTest.testDocumentsOnlyScopePreservesDocumentFoldersWithoutLeakingOtherCategories` (JVM).

---

### R4 — First-Launch Recovery Consistency & Safe Retry
- **Issue:** `createVaultAndCommitRestore` previously ran `createInitialVault` (committing DB envelopes and opening an unlocked session) separately from `commitRestore`, risking a half-initialized unlocked empty vault if record restoration failed or cancelled afterward.
- **Changed Files:**
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/VaultRepository.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/VaultViewModel.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/MainActivity.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/screens/SetupAndUnlockScreens.kt`
- **Correction:**
  - Implemented `VaultRepository.initializeVaultAndCommitRestore(...)` which validates all staged assets, derives key envelopes, copies & `fd.sync()`s durable asset files under recovery journal tracking, checks cancellation, and executes ONE single Room transaction committing envelopes, default folders, and restored records/entities together before opening the unlocked session.
  - On pre-commit failure or cancellation, durable asset copies roll back cleanly, the vault stays uninitialized (`isVaultInitialized == false`), already-initialized vaults are never overwritten, and the staged backup remains ready for safe retry or cancel.
  - Updated first-launch mode resolution in `createVaultAndCommitRestore` to use `MERGE_KEEP_NEWEST` for partial/unknown-scope backups so default vault folders remain intact.
- **Verification:** Verified by `BackupPrivacyAndIntegrityV3RegressionTest.testFirstLaunchAtomicInitializeAndRestoreRollbackAndRetryConsistency` (JVM).

---

### R5 — Cancellation, Finalization & Exclusive Operation Ownership
- **Issue:** Stale backup/restore coroutine jobs could overwrite `activeBackupJob` / `activeRestoreJob` / `currentStagedRestore` or UI state after a newer job started; finalization and secondary preference updates needed clean separation after genuine commit/close boundaries.
- **Changed Files:**
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/VaultViewModel.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/data/EncryptedBackupV2Engine.kt`
- **Correction:**
  - Added atomic monotonic operation IDs (`backupOpSeq`, `restoreOpSeq`, `cloudUploadOpSeq`) in `VaultViewModel` so superseded jobs never mutate newer job handles, UI progress/status, or `currentStagedRestore`, and clean up any orphaned staging directory cleanly.
  - Separated committed outcomes (`exportCompleted`, `uploadCommitted`, `committed`, `vaultAndRestoreCommitted`) from non-fatal secondary preference/notification steps so completed exports are never deleted or misreported.
  - Added final cancellation guard before V2/V3 `outputStream.flush()` / V3 final chunk emission and wired `isCancelled` into `EncryptedBackupV2Engine.decryptAndVerifyManifest(...)`.
- **Verification:** Verified by `BackupPrivacyAndIntegrityV3RegressionTest.testV3FinalFlushCancellationCheckAbortsCleanlyAndDecryptManifestSupportsCancel` (JVM). Live multi-tap SAF concurrency requires hardware verification.

---

### R6 — Notes Editor Draft, Checklist Pending Input & Lifecycle Completion
- **Issue:** Unsubmitted text inside `"Add checklist item..."` (`newChecklistText`) could be silently dropped if the user tapped `"Save Note"` or Back -> `"Save"`, and in-flight note saves needed session/destination ownership plus duplicate-call protection.
- **Changed Files:**
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/screens/NotesScreen.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/ui/VaultViewModel.kt`
  - `app/src/main/java/com/tpeditz/privatevault/v2/MainActivity.kt`
- **Correction:**
  - `NoteEditorScreen.performExplicitSave()` (shared by `"Save Note"` and `"Save changes?"` -> `"Save"`) automatically commits non-blank `newChecklistText` in Checklist mode before saving and accepts it as valid note content, clearing `newChecklistText` cleanly once added so retries never duplicate or lose text.
  - Added `DisposableEffect` active-editor tracking (`isEditorActive`), per-record `activeNoteSaveIds` guard + post-write session ID verification in `VaultViewModel.saveNote`, and `MainActivity` destination/unlock checks so stale callbacks never trigger unintended navigation or success snackbars across locked/changed screens.
  - Draft state remains strictly volatile in memory (no plaintext `rememberSaveable` / Bundle disk leakage).
- **Verification & Limitations:** Added Compose UI tests `testNoteEditorPreservesPendingChecklistInputAcrossSaveAndUnsavedDialog` and `testNoteEditorDialogSaveAlsoIncludesPendingChecklistInput` in `PrivacyAndSecurityUiAndroidTest.kt` (compiled via `:app:compileDebugAndroidTestKotlin`, pending on-device execution).
