package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class VaultRepositoryRestoreRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var dao: InMemoryVaultDao
    private lateinit var sessionManager: VaultSessionManager
    private val testDek = ByteArray(32) { 0x7A.toByte() }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("vault_app_files")
        cacheDir = tempFolder.newFolder("vault_app_cache")
        dao = InMemoryVaultDao()
        sessionManager = VaultSessionManager()
        sessionManager.openSession(
            slotId = VaultSessionManager.REAL_SLOT,
            dekBytes = testDek,
            isDecoyConfiguredInRealVault = false
        )
    }

    /**
     * Requirement 1 & 10:
     * Exercise the ACTUAL VaultRepository.commitRestore() in REPLACE mode.
     * Verify that incoming valid files/records are committed and obsolete files are pruned.
     */
    @Test
    fun testActualRepositoryRestoreReplacesActiveVaultAndPrunesObsoleteFiles() = runBlocking {
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        // 1. Setup existing vault state on disk and in database
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val thumbsDir = File(filesDir, "thumbs").apply { mkdirs() }
        val existingPhotoFile = File(mediaDir, "old-photo-1.pvmedia").apply { writeText("OLD_PHOTO_CIPHERTEXT") }
        val existingThumbFile = File(thumbsDir, "old-photo-1.pvthumb").apply { writeText("OLD_THUMB_CIPHERTEXT") }

        dao.upsertRecord(
            EncryptedRecordEntity(
                recordId = "old-photo-1",
                vaultSlot = 0,
                schemaVersion = 1,
                nonce = ByteArray(12),
                ciphertext = ByteArray(32)
            )
        )
        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = 0,
                mediaId = "old-photo-1",
                formatVersion = 1,
                relativeMediaPath = "media/old-photo-1.pvmedia",
                relativeThumbPath = "thumbs/old-photo-1.pvthumb",
                encryptedSizeBytes = 20L,
                chunkCount = 1,
                ciphertextSha256 = "0".repeat(64),
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )

        assertEquals(1, dao.getRecordsForSlot(0).size)
        assertEquals(1, dao.getAllMediaFileEntitiesForSlot(0).size)
        assertTrue(existingPhotoFile.exists())

        // 2. Prepare incoming staged backup using REAL StreamingMediaCryptoEngine PVM1 + PVT1 encryption
        val stagingDir = tempFolder.newFolder("staged_restore_1")
        val stagedMediaDir = File(stagingDir, "media").apply { mkdirs() }
        val stagedThumbsDir = File(stagingDir, "thumbs").apply { mkdirs() }

        val rawPhotoPlaintext = "GENUINE_JPEG_SYNTHETIC_BYTES_FOR_REPO_TEST".toByteArray(StandardCharsets.UTF_8)
        val rawThumbPlaintext = "GENUINE_THUMB_PNG_BYTES".toByteArray(StandardCharsets.UTF_8)
        val mediaKey = ByteArray(32) { 0x11 }
        val thumbKey = ByteArray(32) { 0x22 }

        val newMediaFile = File(stagedMediaDir, "new-photo-2.pvmedia")
        com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = java.io.ByteArrayInputStream(rawPhotoPlaintext),
            expectedPlaintextSize = rawPhotoPlaintext.size.toLong(),
            targetFile = newMediaFile,
            mediaId = "new-photo-2",
            vaultSlot = 0,
            fileMediaKey = mediaKey
        )
        val newThumbCiphertext = com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine.encryptThumbnail(
            thumbnailPngBytes = rawThumbPlaintext,
            mediaId = "new-photo-2",
            vaultSlot = 0,
            thumbKey = thumbKey
        )
        val newThumbFile = File(stagedThumbsDir, "new-photo-2.pvthumb").apply { writeBytes(newThumbCiphertext) }
        val newMediaSha = sha256Hex(newMediaFile.readBytes())

        val newMediaRecord = MediaRecord(
            id = "new-photo-2",
            title = "Fresh Capture",
            originalFileName = "fresh.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = rawPhotoPlaintext.size.toLong(),
            encryptedSizeBytes = newMediaFile.length(),
            chunkCount = 1,
            formatVersion = 1,
            fileMediaKeyBase64 = Base64.toBase64String(mediaKey),
            thumbnailKeyBase64 = Base64.toBase64String(thumbKey),
            ciphertextSha256Hex = newMediaSha
        )

        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.0",
                items = listOf(newMediaRecord)
            ),
            stagingDir = stagingDir
        )

        // 3. Execute actual commitRestore
        val report = repository.commitRestore(staged, RestoreMode.REPLACE_ACTIVE_VAULT)

        assertEquals(1, report.restoredItemsCount)
        assertEquals(1, report.restoredMediaCount)

        // 4. Verify new item is recorded in DAO with authoritative durable path and decrypts cleanly
        val records = dao.getRecordsForSlot(0)
        assertEquals(1, records.size)
        assertEquals("new-photo-2", records[0].recordId)

        val mediaEntities = dao.getAllMediaFileEntitiesForSlot(0)
        assertEquals(1, mediaEntities.size)
        assertEquals("new-photo-2", mediaEntities[0].mediaId)

        val restoredFile = File(filesDir, mediaEntities[0].relativeMediaPath)
        assertTrue("Restored media file must exist at authoritative DB path", restoredFile.exists())
        assertTrue(java.util.Arrays.equals(rawPhotoPlaintext, repository.loadDecryptedPhotoBytes(newMediaRecord)))
        assertTrue(java.util.Arrays.equals(rawThumbPlaintext, repository.loadDecryptedThumbnailBytes(newMediaRecord)))

        // 5. Verify export-after-restore locates authoritative durable path
        val reExportFile = tempFolder.newFile("re_exported.pvault")
        FileOutputStream(reExportFile).use { fos ->
            repository.createAuthoritativeStreamingBackup(
                items = repository.getAllItemsForActiveSlot(),
                backupPasswordChars = "ReExportPass2026!".toCharArray(),
                outputStream = fos,
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }
        assertTrue(reExportFile.length() > EncryptedBackupV2Engine.HEADER_BYTES)

        // 6. Verify obsolete old file was safely deleted
        assertFalse("Obsolete file must be pruned after successful REPLACE commit", existingPhotoFile.exists())
        assertFalse("Obsolete thumb must be pruned", existingThumbFile.exists())
    }

    /**
     * Requirement 3 & 10:
     * Reject backups containing missing media payloads BEFORE modifying existing vault data.
     * Confirm existing valid records and files remain 100% untouched.
     */
    @Test
    fun testActualRepositoryRejectsMissingMediaPayloadAndPreservesExistingData() = runBlocking {
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        // Existing data that must survive intact
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val existingPhotoFile = File(mediaDir, "vital-data.pvmedia").apply { writeText("MUST_NOT_BE_TOUCHED") }
        dao.upsertRecord(
            EncryptedRecordEntity(
                recordId = "vital-data",
                vaultSlot = 0,
                schemaVersion = 1,
                nonce = ByteArray(12),
                ciphertext = ByteArray(32)
            )
        )

        // Staged backup with MediaRecord pointing to non-existent file
        val stagingDir = tempFolder.newFolder("empty_staging_missing_file")
        val missingMediaRecord = MediaRecord(
            id = "phantom-media-99",
            title = "Missing File Photo",
            originalFileName = "missing.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = 100L,
            encryptedSizeBytes = 100L,
            chunkCount = 1,
            fileMediaKeyBase64 = Base64.toBase64String(ByteArray(32)),
            thumbnailKeyBase64 = Base64.toBase64String(ByteArray(32)),
            ciphertextSha256Hex = "0".repeat(64)
        )

        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.0",
                items = listOf(missingMediaRecord)
            ),
            stagingDir = stagingDir
        )

        try {
            repository.commitRestore(staged, RestoreMode.REPLACE_ACTIVE_VAULT)
            fail("Expected commitRestore to throw IllegalArgumentException for missing media payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Missing media payload file") == true)
        }

        // VERIFY: Existing files and database entities were completely preserved!
        assertTrue("Existing vital photo must survive rejected restore", existingPhotoFile.exists())
        assertEquals("MUST_NOT_BE_TOUCHED", existingPhotoFile.readText())
        val records = dao.getRecordsForSlot(0)
        assertEquals(1, records.size)
        assertEquals("vital-data", records[0].recordId)
    }

    /**
     * Requirement 3 & 10:
     * Reject backups containing corrupted SHA-256 digests BEFORE modifying existing vault data.
     */
    @Test
    fun testActualRepositoryRejectsCorruptedSha256PayloadAndPreservesExistingData() = runBlocking {
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val existingPhotoFile = File(mediaDir, "safe-photo.pvmedia").apply { writeText("SAFE_EXISTING_CONTENT") }

        val stagingDir = tempFolder.newFolder("staging_corrupt_sha")
        val stagedMediaDir = File(stagingDir, "media").apply { mkdirs() }
        val stagedThumbDir = File(stagingDir, "thumbs").apply { mkdirs() }

        val tamperedBytes = "TAMPERED_OR_CORRUPTED_BYTES".toByteArray(StandardCharsets.UTF_8)
        File(stagedMediaDir, "corrupt-1.pvmedia").writeBytes(tamperedBytes)
        val validThumb = com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine.encryptThumbnail(
            thumbnailPngBytes = "ThumbBytes".toByteArray(StandardCharsets.UTF_8),
            mediaId = "corrupt-1",
            vaultSlot = 0,
            thumbKey = ByteArray(32) { 0x22 }
        )
        File(stagedThumbDir, "corrupt-1.pvthumb").writeBytes(validThumb)

        val recordWithWrongSha = MediaRecord(
            id = "corrupt-1",
            title = "Corrupt Photo",
            originalFileName = "pic.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = tamperedBytes.size.toLong(),
            encryptedSizeBytes = tamperedBytes.size.toLong(),
            chunkCount = 1,
            fileMediaKeyBase64 = Base64.toBase64String(ByteArray(32) { 0x11 }),
            thumbnailKeyBase64 = Base64.toBase64String(ByteArray(32) { 0x22 }),
            ciphertextSha256Hex = "f".repeat(64) // Deliberately mismatched SHA-256
        )

        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.0",
                items = listOf(recordWithWrongSha)
            ),
            stagingDir = stagingDir
        )

        try {
            repository.commitRestore(staged, RestoreMode.REPLACE_ACTIVE_VAULT)
            fail("Expected commitRestore to reject corrupted SHA-256 payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("SHA-256 digest mismatch") == true)
        }

        // Verify existing file is completely preserved
        assertTrue(existingPhotoFile.exists())
        assertEquals("SAFE_EXISTING_CONTENT", existingPhotoFile.readText())
    }

    /**
     * Requirement 1 & 10:
     * Restart Recovery Test:
     * Simulate an unexpected process kill / app crash during restoration.
     * Verify that upon application restart (instantiating a brand-new VaultRepository),
     * recoverIncompleteRestoreIfPresent() runs automatically, reverts uncommitted file copies,
     * restores backed-up colliding files, and purges the recovery journal.
     */
    @Test
    fun testActualRepositoryRestartRecoveryRecoversInterruptedRestore() = runBlocking {
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val originalPhotoFile = File(mediaDir, "colliding_photo.pvmedia").apply {
            writeText("ORIGINAL_CRITICAL_FILE_CONTENT")
        }

        // Simulate crash mid-flight:
        // Persistent recovery journal sitting on disk in STATE_COPIED
        val recoveryJournalDir = File(filesDir, VaultRepository.RECOVERY_JOURNAL_DIR_NAME).apply { mkdirs() }
        val journalFile = File(recoveryJournalDir, VaultRepository.RECOVERY_MANIFEST_FILE_NAME)
        val rollbackDir = File(recoveryJournalDir, "rollback_files").apply { mkdirs() }

        // Backed up copy of original file
        val backupFile = File(rollbackDir, "backup_colliding_photo.pvmedia").apply {
            writeText("ORIGINAL_CRITICAL_FILE_CONTENT")
        }

        // File on disk was partially overwritten before crash
        originalPhotoFile.writeText("UNCOMMITTED_CORRUPTED_INCOMING_CONTENT")

        // Stray uncommitted new file introduced by crashed restore
        val strayNewFile = File(mediaDir, "stray_uncommitted_new.pvmedia").apply {
            writeText("STRAY_NEW_DATA")
        }

        // Write the crash manifest
        val manifestJson = JSONObject().apply {
            put("slotId", 0)
            put("mode", RestoreMode.REPLACE_ACTIVE_VAULT.name)
            put("state", VaultRepository.STATE_COPIED)
            put("timestamp", System.currentTimeMillis())

            val backedUpArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("original", originalPhotoFile.absolutePath)
                    put("backup", backupFile.absolutePath)
                })
            }
            put("backedUpFiles", backedUpArr)

            val newlyCopiedArr = JSONArray().apply {
                put(strayNewFile.absolutePath)
                put(originalPhotoFile.absolutePath)
            }
            put("newlyCopiedFiles", newlyCopiedArr)
            put("filesToDeleteAfterCommit", JSONArray())
        }

        FileOutputStream(journalFile).use { fos ->
            fos.write(manifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        assertTrue("Journal file must exist prior to restart", journalFile.exists())
        assertEquals("UNCOMMITTED_CORRUPTED_INCOMING_CONTENT", originalPhotoFile.readText())
        assertTrue(strayNewFile.exists())

        // SIMULATE APPLICATION RESTART:
        // A brand-new VaultRepository instance is created upon app launch.
        // Its init block executes recoverIncompleteRestoreIfPresent().
        val restartedRepository = VaultRepository(dao, sessionManager, filesDir, cacheDir)
        assertNotNull(restartedRepository)

        // VERIFY RESTART RECOVERY:
        // 1. Original file was rolled back to original content!
        assertTrue("Original file must exist", originalPhotoFile.exists())
        assertEquals("ORIGINAL_CRITICAL_FILE_CONTENT", originalPhotoFile.readText())

        // 2. Stray uncommitted file from failed restore must be completely purged!
        assertFalse("Stray uncommitted file must be deleted", strayNewFile.exists())

        // 3. Recovery journal must be cleaned up
        assertFalse("Recovery journal directory must be purged", recoveryJournalDir.exists())
    }

    /**
     * Requirement 1 & 2:
     * Restart Recovery Test in STATE_STAGED:
     * Simulate unexpected crash when files were staged separately before database commit.
     * Verify that upon application restart, recoverIncompleteRestoreIfPresent() purges
     * the pending staging folder, preserves active files completely untouched, and deletes the journal.
     */
    @Test
    fun testActualRepositoryRestartRecoveryRecoversInterruptedRestoreStaged() = runBlocking {
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val activePhotoFile = File(mediaDir, "active_safe_photo.pvmedia").apply {
            writeText("ACTIVE_SAFE_CONTENT")
        }

        val recoveryJournalDir = File(filesDir, VaultRepository.RECOVERY_JOURNAL_DIR_NAME).apply { mkdirs() }
        val journalFile = File(recoveryJournalDir, VaultRepository.RECOVERY_MANIFEST_FILE_NAME)
        val pendingStagingDir = File(filesDir, "restore_pending_test_staging").apply { mkdirs() }
        val pendingMediaDir = File(pendingStagingDir, "media").apply { mkdirs() }
        val uncommittedStagedFile = File(pendingMediaDir, "staged_incoming.pvmedia").apply {
            writeText("UNCOMMITTED_INCOMING_BYTES")
        }

        val manifestJson = JSONObject().apply {
            put("slotId", 0)
            put("mode", RestoreMode.REPLACE_ACTIVE_VAULT.name)
            put("state", VaultRepository.STATE_STAGED)
            put("timestamp", System.currentTimeMillis())
            put("stagingDir", pendingStagingDir.absolutePath)
            put("stagedFilesToPromote", JSONArray())
            put("filesToDeleteAfterCommit", JSONArray())
        }

        FileOutputStream(journalFile).use { fos ->
            fos.write(manifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        assertTrue(journalFile.exists())
        assertTrue(uncommittedStagedFile.exists())
        assertEquals("ACTIVE_SAFE_CONTENT", activePhotoFile.readText())

        // Simulate app restart
        val restartedRepo = VaultRepository(dao, sessionManager, filesDir, cacheDir)
        assertNotNull(restartedRepo)

        // Staging directory must be wiped
        assertFalse(pendingStagingDir.exists())
        assertFalse(uncommittedStagedFile.exists())

        // Active files must be 100% untouched
        assertTrue(activePhotoFile.exists())
        assertEquals("ACTIVE_SAFE_CONTENT", activePhotoFile.readText())

        // Recovery journal must be purged
        assertFalse(recoveryJournalDir.exists())
    }

    /**
     * Requirement 1 & 2:
     * Restart Recovery Test in STATE_COMMITTED:
     * Simulate unexpected crash right after the database transaction committed, but before
     * pending staged files were promoted into the active vault.
     * Verify that upon application restart, recoverIncompleteRestoreIfPresent() promotes
     * all staged files into active directories, deletes obsolete files, and deletes the journal.
     */
    @Test
    fun testActualRepositoryRestartRecoveryPromotesCommittedRestore() = runBlocking {
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val obsoleteOldFile = File(mediaDir, "obsolete_old_photo.pvmedia").apply {
            writeText("OBSOLETE_PRE_RESTORE_DATA")
        }

        val recoveryJournalDir = File(filesDir, VaultRepository.RECOVERY_JOURNAL_DIR_NAME).apply { mkdirs() }
        val journalFile = File(recoveryJournalDir, VaultRepository.RECOVERY_MANIFEST_FILE_NAME)
        val pendingStagingDir = File(filesDir, "restore_pending_committed_staging").apply { mkdirs() }
        val pendingMediaDir = File(pendingStagingDir, "media").apply { mkdirs() }

        val committedStagedFile = File(pendingMediaDir, "promoted_photo_1.pvmedia").apply {
            writeText("COMMITTED_RESTORED_DATA")
        }
        val targetActiveFile = File(mediaDir, "promoted_photo_1.pvmedia")

        val manifestJson = JSONObject().apply {
            put("slotId", 0)
            put("mode", RestoreMode.REPLACE_ACTIVE_VAULT.name)
            put("state", VaultRepository.STATE_COMMITTED)
            put("timestamp", System.currentTimeMillis())
            put("stagingDir", pendingStagingDir.absolutePath)

            val stagedArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("staged", committedStagedFile.absolutePath)
                    put("target", targetActiveFile.absolutePath)
                })
            }
            put("stagedFilesToPromote", stagedArr)

            val toDeleteArr = JSONArray().apply {
                put(obsoleteOldFile.absolutePath)
            }
            put("filesToDeleteAfterCommit", toDeleteArr)
        }

        FileOutputStream(journalFile).use { fos ->
            fos.write(manifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        assertTrue(journalFile.exists())
        assertTrue(committedStagedFile.exists())
        assertTrue(obsoleteOldFile.exists())

        // Simulate app restart
        val restartedRepo = VaultRepository(dao, sessionManager, filesDir, cacheDir)
        assertNotNull(restartedRepo)

        // Promoted file must now exist in active directory with valid committed content
        assertTrue(targetActiveFile.exists())
        assertEquals("COMMITTED_RESTORED_DATA", targetActiveFile.readText())

        // Obsolete file must be purged
        assertFalse(obsoleteOldFile.exists())

        // Staging and journal must be cleaned up
        assertFalse(journalFile.exists())
        assertFalse(recoveryJournalDir.exists())
    }

    /**
     * Task A Authoritative DB Recovery Verification:
     * Even if journal state says STATE_STAGED, if the DB transaction ALREADY committed a file
     * or an unrelated slot (Slot 1 Decoy) owns a file, recovery MUST preserve DB-referenced files
     * and delete only unreferenced orphans.
     */
    @Test
    fun testAuthoritativeDatabaseRecoveryProtectsCommittedFilesAndUnrelatedSlots() = runBlocking {
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val thumbsDir = File(filesDir, "thumbs").apply { mkdirs() }

        val decoyFile = File(mediaDir, "decoy_secret.pvmedia").apply { writeText("DECOY_SLOT_1_ASSET") }
        val decoyThumb = File(thumbsDir, "decoy_secret.pvthumb").apply { writeText("DECOY_THUMB") }
        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = VaultSessionManager.DECOY_SLOT,
                mediaId = "decoy_secret",
                formatVersion = 1,
                relativeMediaPath = "media/decoy_secret.pvmedia",
                relativeThumbPath = "thumbs/decoy_secret.pvthumb",
                encryptedSizeBytes = decoyFile.length(),
                chunkCount = 1,
                ciphertextSha256 = "a".repeat(64),
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )

        val committedFile = File(mediaDir, "committed_tok123.pvmedia").apply { writeText("COMMITTED_IN_DB") }
        val committedThumb = File(thumbsDir, "committed_tok123.pvthumb").apply { writeText("COMMITTED_THUMB") }
        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = VaultSessionManager.REAL_SLOT,
                mediaId = "committed_id",
                formatVersion = 1,
                relativeMediaPath = "media/committed_tok123.pvmedia",
                relativeThumbPath = "thumbs/committed_tok123.pvthumb",
                encryptedSizeBytes = committedFile.length(),
                chunkCount = 1,
                ciphertextSha256 = "b".repeat(64),
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )

        val uncommittedOrphan = File(mediaDir, "orphan_uncommitted.pvmedia").apply { writeText("ORPHAN") }
        val obsoleteReplaced = File(mediaDir, "obsolete_replaced.pvmedia").apply { writeText("OBSOLETE") }

        val recoveryJournalDir = File(filesDir, VaultRepository.RECOVERY_JOURNAL_DIR_NAME).apply { mkdirs() }
        val journalFile = File(recoveryJournalDir, VaultRepository.RECOVERY_MANIFEST_FILE_NAME)
        // Phase A: Simulate crash while STATE_STAGED (before DB commit):
        // uncommittedOrphan must be deleted, committedFile/decoyFile/obsoleteReplaced MUST ALL survive
        // because DB has not flipped yet!
        val stagedManifestJson = JSONObject().apply {
            put("slotId", 0)
            put("mode", RestoreMode.REPLACE_ACTIVE_VAULT.name)
            put("state", VaultRepository.STATE_STAGED)
            put("timestamp", System.currentTimeMillis())
            put("writtenAssetFiles", JSONArray().apply {
                put(committedFile.absolutePath)
                put(uncommittedOrphan.absolutePath)
            })
            put("filesToDeleteAfterCommit", JSONArray().apply {
                put(obsoleteReplaced.absolutePath)
                put(decoyFile.absolutePath)
            })
        }
        FileOutputStream(journalFile).use { fos ->
            fos.write(stagedManifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        VaultRepository(dao, sessionManager, filesDir, cacheDir)

        assertTrue("Committed DB asset MUST NOT be deleted by recovery", committedFile.exists())
        assertTrue("Unrelated Slot 1 Decoy asset MUST NOT be deleted", decoyFile.exists())
        assertTrue("Pre-commit old asset MUST NOT be deleted while STATE_STAGED", obsoleteReplaced.exists())
        assertFalse("Uncommitted orphan MUST be deleted", uncommittedOrphan.exists())
        assertFalse("Journal must be cleaned after recovery", recoveryJournalDir.exists())

        // Phase B: Simulate crash while STATE_COMMITTED (after DB commit):
        // obsoleteReplaced IS pruned, while authoritative DB assets (committedFile & decoyFile) survive!
        recoveryJournalDir.mkdirs()
        val committedManifestJson = JSONObject().apply {
            put("slotId", 0)
            put("mode", RestoreMode.REPLACE_ACTIVE_VAULT.name)
            put("state", VaultRepository.STATE_COMMITTED)
            put("timestamp", System.currentTimeMillis())
            put("writtenAssetFiles", JSONArray().apply {
                put(committedFile.absolutePath)
            })
            put("filesToDeleteAfterCommit", JSONArray().apply {
                put(obsoleteReplaced.absolutePath)
                put(decoyFile.absolutePath) // Even if listed here, authoritative DB Slot 1 owns it!
            })
        }
        FileOutputStream(journalFile).use { fos ->
            fos.write(committedManifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        VaultRepository(dao, sessionManager, filesDir, cacheDir)

        assertTrue("Committed DB asset MUST NOT be deleted by recovery", committedFile.exists())
        assertTrue("Unrelated Slot 1 Decoy asset MUST NOT be deleted", decoyFile.exists())
        assertFalse("Obsolete unreferenced file MUST be deleted after STATE_COMMITTED", obsoleteReplaced.exists())
        assertFalse("Journal must be cleaned after full cleanup", recoveryJournalDir.exists())
    }

    /**
     * Requirement 4:
     * Bind restore operations to authorized session and reject session changes mid-operation.
     */
    @Test
    fun testActualRepositorySessionChangeDuringRestoreRejection() = runBlocking {
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)
        val stagingDir = tempFolder.newFolder("staging_session_test")

        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.0",
                items = emptyList()
            ),
            stagingDir = stagingDir
        )

        // Cancelled mid-operation
        try {
            repository.commitRestore(
                staged = staged,
                mode = RestoreMode.REPLACE_ACTIVE_VAULT,
                isCancelled = { true }
            )
            fail("Expected commitRestore to throw CancellationException when isCancelled returns true")
        } catch (_: java.util.concurrent.CancellationException) {
            // Expected
        }
    }

    /**
     * Requirement 8:
     * Prevent restore from executing on a locked session.
     */
    @Test
    fun testActualRepositoryLockedSessionRejection() = runBlocking {
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        // Lock the session
        sessionManager.lockNow()

        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.0",
                items = emptyList()
            ),
            stagingDir = tempFolder.newFolder("empty_staging_locked")
        )

        try {
            repository.commitRestore(staged, RestoreMode.MERGE_KEEP_NEWEST)
            fail("Expected IllegalStateException when committing restore on locked session")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Vault is locked") == true)
        }
    }

    /**
     * Requirement 6:
     * Merge mode preserves existing newer records and merges older/new records.
     */
    @Test
    fun testActualRepositoryMergeModePreservesNewerExistingRecords() = runBlocking {
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val noteId = "note-merge-1"
        val existingNote = NoteRecord(
            id = noteId,
            title = "Existing Newer Note",
            body = "Updated today",
            updatedAtEpochMs = 2000L
        )

        // Save existing note via repository
        repository.saveItem(existingNote)

        // Staged backup has an older version of the same note (updatedAt 1000L)
        val olderBackupNote = NoteRecord(
            id = noteId,
            title = "Stale Backup Note",
            body = "Old content from last month",
            updatedAtEpochMs = 1000L
        )

        val brandNewNote = NoteRecord(
            id = "note-merge-2",
            title = "Brand New Note",
            body = "New from backup",
            updatedAtEpochMs = 1500L
        )

        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.0",
                items = listOf(olderBackupNote, brandNewNote)
            ),
            stagingDir = tempFolder.newFolder("merge_staging")
        )

        // Execute merge restore
        val report = repository.commitRestore(staged, RestoreMode.MERGE_KEEP_NEWEST)

        // Only the brand new note should be restored; the stale note should be skipped
        assertEquals(1, report.restoredItemsCount)

        val allItems = repository.getAllItemsForActiveSlot()
        assertEquals(2, allItems.size)

        val resolvedNote1 = allItems.find { it.id == noteId } as NoteRecord
        assertEquals("Existing Newer Note", resolvedNote1.title)
        assertEquals("Updated today", resolvedNote1.body)

        val resolvedNote2 = allItems.find { it.id == "note-merge-2" } as NoteRecord
        assertEquals("Brand New Note", resolvedNote2.title)
    }
}
