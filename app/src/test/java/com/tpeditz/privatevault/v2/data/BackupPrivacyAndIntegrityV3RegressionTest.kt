package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException

class BackupPrivacyAndIntegrityV3RegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun testKdf(): CryptoEngine.KdfParams =
        CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)

    @Test
    fun testV3PrivacyEnhancedBackupRoundtripHidesMetadataAndLegacyHeaders() {
        val password = "V3_STRONG_TEST_BACKUP_PASSWORD_2026".toCharArray()
        val appFilesDir = tempFolder.newFolder("v3_app_files")
        val mediaDir = File(appFilesDir, "media").apply { mkdirs() }
        val thumbDir = File(appFilesDir, "thumbs").apply { mkdirs() }
        val docDir = File(appFilesDir, "documents").apply { mkdirs() }

        val mediaId = "media-v3-1"
        val docId = "doc-v3-1"
        val mediaKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val thumbKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val docKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)

        val plainVideoBytes = ByteArray(300 * 1024) { idx -> (idx % 251).toByte() }
        val plainDocBytes = ByteArray(48 * 1024) { idx -> ('A'.code + (idx % 26)).toByte() }
        val plainThumbBytes = "PNG_SYNTHETIC_THUMB_DATA".toByteArray(StandardCharsets.UTF_8)

        val mediaFile = File(mediaDir, "$mediaId.pvmedia")
        val thumbFile = File(thumbDir, "$mediaId.pvthumb")
        val docFile = File(docDir, "$docId.pvdoc")

        val mediaHeader = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(plainVideoBytes),
            expectedPlaintextSize = plainVideoBytes.size.toLong(),
            targetFile = mediaFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = mediaKey
        )
        thumbFile.writeBytes(
            StreamingMediaCryptoEngine.encryptThumbnail(
                thumbnailPngBytes = plainThumbBytes,
                mediaId = mediaId,
                vaultSlot = 0,
                thumbKey = thumbKey
            )
        )
        val docHeader = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(plainDocBytes),
            expectedPlaintextSize = plainDocBytes.size.toLong(),
            targetFile = docFile,
            mediaId = docId,
            vaultSlot = 0,
            fileMediaKey = docKey
        )

        val mediaSha = sha256Hex(mediaFile.readBytes())
        val docSha = sha256Hex(docFile.readBytes())

        val items = listOf<VaultItem>(
            NoteRecord(id = "note-v3-1", title = "Secret Project Title", body = "Confidential Secret Body"),
            CredentialRecord(
                id = "cred-v3-1",
                serviceName = "ExampleBank",
                username = "alice@example.test",
                password = "VaultPassword!999"
            ),
            MediaRecord(
                id = mediaId,
                title = "SecretMovie.mp4",
                originalFileName = "SecretMovie.mp4",
                mimeType = "video/mp4",
                mediaKind = MediaKind.VIDEO,
                originalSizeBytes = plainVideoBytes.size.toLong(),
                encryptedSizeBytes = mediaFile.length(),
                chunkCount = mediaHeader.totalChunks,
                fileMediaKeyBase64 = Base64.toBase64String(mediaKey),
                thumbnailKeyBase64 = Base64.toBase64String(thumbKey),
                ciphertextSha256Hex = mediaSha
            ),
            DocumentRecord(
                id = docId,
                title = "SecretContract.pdf",
                originalFileName = "SecretContract.pdf",
                mimeType = "application/pdf",
                originalSizeBytes = plainDocBytes.size.toLong(),
                encryptedSizeBytes = docFile.length(),
                chunkCount = docHeader.totalChunks,
                fileKeyBase64 = Base64.toBase64String(docKey),
                ciphertextSha256Hex = docSha
            ),
            SecureLinkRecord(id = "link-v3-1", title = "Internal Wiki", url = "https://wiki.example.test/"),
            AuthenticatorRecord(
                id = "totp-v3-1",
                serviceName = "GitHub",
                accountName = "octocat",
                secret = "JBSWY3DPEHPK3PXP",
                algorithm = TotpAlgorithm.SHA256
            )
        )

        val backupFile = tempFolder.newFile("v3_backup.pvault")
        val progressEvents = mutableListOf<Pair<Long, Long>>()

        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createPrivacyEnhancedStreamingBackup(
                items = items,
                backupPasswordChars = password,
                outputStream = fos,
                appFilesDir = appFilesDir,
                appVersionName = "2.0.4",
                kdfParams = testKdf(),
                onProgress = { written, total -> progressEvents.add(written to total) },
                exportScope = BackupExportScope.ALL_DATA
            )
        }

        val containerBytes = backupFile.readBytes()
        assertTrue(EncryptedBackupV2Engine.isV3Backup(containerBytes))
        assertTrue(EncryptedBackupV2Engine.isSupportedBackup(containerBytes))
        assertFalse(EncryptedBackupV2Engine.isV2Backup(containerBytes))

        // PV-14: Internal PVM1 headers and plaintext titles must never appear anywhere in the container
        val containerText = String(containerBytes, StandardCharsets.ISO_8859_1)
        assertFalse("V3 container must hide internal PVM1 headers", containerText.contains("PVM1"))
        assertFalse(containerText.contains("Secret Project Title"))
        assertFalse(containerText.contains("SecretMovie.mp4"))
        assertFalse(containerText.contains("SecretContract.pdf"))

        // Progress accounting should reach the estimated total monotonically (PV-08)
        assertTrue(progressEvents.isNotEmpty())
        assertTrue(progressEvents.last().first >= progressEvents.last().second)

        // Wrong password rejected cleanly
        try {
            backupFile.inputStream().use { fis ->
                EncryptedBackupV2Engine.decryptAndVerifyManifest(fis, "WRONG_PASSWORD_2026".toCharArray())
            }
            fail("Expected AEADBadTagException for wrong V3 password")
        } catch (_: AEADBadTagException) {
            // expected
        }

        // Tampered ciphertext byte inside V3 chunk stream rejected cleanly
        val tamperedFile = tempFolder.newFile("v3_tampered.pvault")
        val tampered = containerBytes.copyOf()
        val flipIndex = EncryptedBackupV2Engine.HEADER_BYTES_V3 + 64
        tampered[flipIndex] = (tampered[flipIndex].toInt() xor 0xFF).toByte()
        tamperedFile.writeBytes(tampered)
        try {
            tamperedFile.inputStream().use { fis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(
                    fis,
                    password,
                    tempFolder.newFolder("staging_tampered")
                )
            }
            fail("Expected AEADBadTagException for tampered V3 stream")
        } catch (_: AEADBadTagException) {
            // expected
        }

        // Trailing bytes appended after V3 isFinal chunk rejected (PV-04)
        val trailingFile = tempFolder.newFile("v3_trailing.pvault")
        trailingFile.writeBytes(containerBytes + ByteArray(23) { 0x5A })
        try {
            trailingFile.inputStream().use { fis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(
                    fis,
                    password,
                    tempFolder.newFolder("staging_trailing")
                )
            }
            fail("Expected IllegalArgumentException for trailing bytes in V3 backup")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Full staged restore verification
        val stagingDir = tempFolder.newFolder("staging_v3_ok")
        val stageProgress = mutableListOf<Pair<Long, Long>>()
        val staged = backupFile.inputStream().use { fis ->
            EncryptedBackupV2Engine.inspectAndStageBackup(
                inputStream = fis,
                backupPasswordChars = password,
                stagingDir = stagingDir,
                onProgress = { r, t -> stageProgress.add(r to t) }
            )
        }
        assertEquals(items.size, staged.payload.items.size)
        assertTrue(stageProgress.isNotEmpty())
        assertTrue(stageProgress.last().first >= stageProgress.last().second)
        assertArrayEquals(mediaFile.readBytes(), File(stagingDir, "media/$mediaId.pvmedia").readBytes())
        assertArrayEquals(thumbFile.readBytes(), File(stagingDir, "thumbs/$mediaId.pvthumb").readBytes())
        val stagedDoc = sequenceOf(
            File(stagingDir, "documents/$docId.pvdoc"),
            File(stagingDir, "docs/$docId.pvdoc")
        ).firstOrNull { it.exists() }
        assertNotNull(stagedDoc)
        assertArrayEquals(docFile.readBytes(), stagedDoc!!.readBytes())
    }

    @Test
    fun testV2TrailingBytesRejectedAndBackwardCompatibilityPreserved() {
        val password = "V2_COMPAT_TEST_PASSWORD_2026".toCharArray()
        val appFilesDir = tempFolder.newFolder("v2_app_files")
        val note = NoteRecord(id = "v2-note-1", title = "Legacy V2 Note", body = "Still supported")
        val backupFile = tempFolder.newFile("v2_backup.pvault")

        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = listOf(note),
                backupPasswordChars = password,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = testKdf(),
                exportScope = BackupExportScope.NOTES_ONLY
            )
        }

        val v2Bytes = backupFile.readBytes()
        assertTrue(EncryptedBackupV2Engine.isV2Backup(v2Bytes))
        assertTrue(EncryptedBackupV2Engine.isSupportedBackup(v2Bytes))

        val staged = backupFile.inputStream().use { fis ->
            EncryptedBackupV2Engine.inspectAndStageBackup(fis, password, tempFolder.newFolder("v2_stage_ok"))
        }
        assertEquals(1, staged.payload.items.size)
        assertEquals(BackupExportScope.NOTES_ONLY, staged.payload.exportScope)

        // Trailing garbage after V2 container must fail EOF validation
        val v2Trailing = tempFolder.newFile("v2_trailing.pvault")
        v2Trailing.writeBytes(v2Bytes + ByteArray(9) { 0x11 })
        try {
            v2Trailing.inputStream().use { fis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(fis, password, tempFolder.newFolder("v2_stage_bad"))
            }
            fail("Expected trailing garbage in V2 container to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testRepositoryRejectsCorruptedRecordAndAssetMismatchDuringBackup() = runBlocking {
        val filesDir = tempFolder.newFolder("repo_files_err")
        val cacheDir = tempFolder.newFolder("repo_cache_err")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val repo = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        assertTrue(repo.createInitialVault("RepoMaster!2026#Safe".toCharArray(), testKdf()) is AuthResult.Success)
        val note = NoteRecord(id = "good-note-1", title = "Good Note", body = "Healthy body")
        repo.saveItem(note)

        // Inject a corrupted/unreadable record in active Slot 0
        dao.upsertRecord(
            EncryptedRecordEntity(
                recordId = "corrupt-rec-1",
                vaultSlot = VaultSessionManager.REAL_SLOT,
                schemaVersion = CryptoEngine.RECORD_SCHEMA_VERSION,
                nonce = ByteArray(12) { 1 },
                ciphertext = ByteArray(48) { 9 }
            )
        )

        // PV-03: getAllItemsForBackupOrThrow must throw CorruptedVaultRecordsException instead of silently omitting
        try {
            repo.getAllItemsForBackupOrThrow()
            fail("Expected CorruptedVaultRecordsException when any record fails authentication")
        } catch (e: CorruptedVaultRecordsException) {
            assertEquals(1, e.corruptedCount)
        }

        // Remove corrupted record, add media with tampered file on disk -> PV-02 must abort backup
        dao.deleteRecord(VaultSessionManager.REAL_SLOT, "corrupt-rec-1")
        val mediaId = "bad-disk-media-1"
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val thumbDir = File(filesDir, "thumbs").apply { mkdirs() }
        val mediaKey = CryptoEngine.randomBytes(32)
        val thumbKey = CryptoEngine.randomBytes(32)
        val mediaFile = File(mediaDir, "$mediaId.pvmedia")
        val thumbFile = File(thumbDir, "$mediaId.pvthumb")
        val hdr = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            ByteArrayInputStream("GenuineVideoBytes".toByteArray()),
            17L,
            mediaFile,
            mediaId,
            0,
            mediaKey
        )
        thumbFile.writeBytes(StreamingMediaCryptoEngine.encryptThumbnail("Thumb".toByteArray(), mediaId, 0, thumbKey))
        val expectedSha = sha256Hex(mediaFile.readBytes())
        val expectedSize = mediaFile.length()

        repo.saveItem(
            MediaRecord(
                id = mediaId,
                title = "Corrupted On Disk",
                originalFileName = "video.mp4",
                mimeType = "video/mp4",
                mediaKind = MediaKind.VIDEO,
                originalSizeBytes = 17L,
                encryptedSizeBytes = expectedSize,
                chunkCount = hdr.totalChunks,
                fileMediaKeyBase64 = Base64.toBase64String(mediaKey),
                thumbnailKeyBase64 = Base64.toBase64String(thumbKey),
                ciphertextSha256Hex = expectedSha
            )
        )
        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = 0,
                mediaId = mediaId,
                formatVersion = hdr.formatVersion,
                relativeMediaPath = "media/$mediaId.pvmedia",
                relativeThumbPath = "thumbs/$mediaId.pvthumb",
                encryptedSizeBytes = expectedSize,
                chunkCount = hdr.totalChunks,
                ciphertextSha256 = expectedSha,
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1L
            )
        )

        // Tamper on-disk bytes AFTER commit without changing size
        val tamperedDisk = mediaFile.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }
        mediaFile.writeBytes(tamperedDisk)

        val validItems = repo.getAllItemsForBackupOrThrow()
        val out = ByteArrayOutputStream()
        try {
            repo.createAuthoritativeStreamingBackup(
                items = validItems,
                backupPasswordChars = "ExportStrong!2026".toCharArray(),
                outputStream = out,
                kdfParams = testKdf()
            )
            fail("Expected integrity/authentication error when on-disk asset is tampered")
        } catch (e: Exception) {
            assertTrue(
                "Expected IllegalArgumentException or AEADBadTagException but got ${e.javaClass.name}",
                e is IllegalArgumentException || e is AEADBadTagException
            )
        }
    }

    @Test
    fun testRestoreCancellationAcrossV3AndV1PlusFirstLaunchPreflightValidation() = runBlocking {
        val password = "CANCEL_TEST_PASSWORD_2026".toCharArray()
        val appFilesDir = tempFolder.newFolder("cancel_app_files")
        val v3File = tempFolder.newFile("cancel_v3.pvault")

        FileOutputStream(v3File).use { fos ->
            EncryptedBackupV2Engine.createPrivacyEnhancedStreamingBackup(
                items = listOf(NoteRecord(id = "n-c-1", title = "Note", body = "Body")),
                backupPasswordChars = password,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = testKdf()
            )
        }

        // V3 restore inspection respects isCancelled
        try {
            v3File.inputStream().use { fis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(
                    inputStream = fis,
                    backupPasswordChars = password,
                    stagingDir = tempFolder.newFolder("cancel_stage_v3"),
                    isCancelled = { true }
                )
            }
            fail("Expected CancellationException on V3 restore cancel")
        } catch (_: CancellationException) {
            // expected
        }

        // Legacy V1 restore inspection respects isCancelled
        val v1Salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val v1Nonce = CryptoEngine.generateRandomBytes(CryptoEngine.NONCE_BYTES)
        val v1Kdf = testKdf()
        val v1Kek = CryptoEngine.deriveKeyArgon2id(password, v1Salt, v1Kdf, 32)
        val v1Json = JSONObject().apply {
            put("appVersionName", "1.0.4")
            put("exportedAtEpochMs", 1700000000000L)
            put("records", JSONArray())
            put("mediaBlobs", JSONArray())
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val (v1Cipher, _) = CryptoEngine.encryptPayload(
            v1Json,
            v1Kek,
            CryptoEngine.buildBackupAad(1, v1Kdf),
            customNonce = v1Nonce
        )
        val v1Bytes = ByteBuffer.allocate(LegacyV1BackupReader.HEADER_BYTES + v1Cipher.size).apply {
            put(LegacyV1BackupReader.V1_MAGIC_BYTES)
            putInt(1)
            putInt(v1Kdf.memoryKb)
            putInt(v1Kdf.iterations)
            putInt(v1Kdf.parallelism)
            put(v1Salt)
            put(v1Nonce)
            put(v1Cipher)
        }.array()

        try {
            LegacyV1BackupReader.inspectAndStageLegacyBackup(
                inputStream = ByteArrayInputStream(v1Bytes),
                backupPasswordChars = password,
                stagingDir = tempFolder.newFolder("cancel_stage_v1"),
                isCancelled = { true }
            )
            fail("Expected CancellationException on V1 restore cancel")
        } catch (_: CancellationException) {
            // expected
        }

        // PV-07: Pre-flight asset validation rejects missing staged assets before createInitialVault touches DB
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val filesDir = tempFolder.newFolder("preflight_files")
        val cacheDir = tempFolder.newFolder("preflight_cache")
        val repo = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val brokenStaged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.4",
                items = listOf(
                    DocumentRecord(
                        id = "missing-doc-1",
                        title = "Missing Doc",
                        originalFileName = "doc.pdf",
                        mimeType = "application/pdf",
                        originalSizeBytes = 100L,
                        encryptedSizeBytes = 128L,
                        chunkCount = 1,
                        fileKeyBase64 = Base64.toBase64String(ByteArray(32)),
                        ciphertextSha256Hex = "a".repeat(64)
                    )
                )
            ),
            stagingDir = tempFolder.newFolder("preflight_stage_missing")
        )
        try {
            repo.validateStagedAssetsForSlot(brokenStaged, VaultSessionManager.REAL_SLOT)
            fail("Expected IllegalArgumentException for missing staged document")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertNull("Database envelope must remain uninitialized on preflight failure", dao.getEnvelopeForSlot(0))
    }

    @Test
    fun testAuthoritativeBackupRejectsTamperedThumbnailEvenWithSameByteLength() = runBlocking {
        val filesDir = tempFolder.newFolder("r1_thumb_files")
        val cacheDir = tempFolder.newFolder("r1_thumb_cache")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val repo = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        assertTrue(repo.createInitialVault("R1ThumbMaster!2026".toCharArray(), testKdf()) is AuthResult.Success)
        val mediaId = "r1-media-1"
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val thumbDir = File(filesDir, "thumbs").apply { mkdirs() }
        val mediaKey = CryptoEngine.randomBytes(32)
        val thumbKey = CryptoEngine.randomBytes(32)
        val mediaFile = File(mediaDir, "$mediaId.pvmedia")
        val thumbFile = File(thumbDir, "$mediaId.pvthumb")
        val mediaPlain = ByteArray(8192) { (it % 199).toByte() }
        val hdr = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(mediaPlain),
            expectedPlaintextSize = mediaPlain.size.toLong(),
            targetFile = mediaFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = mediaKey
        )
        thumbFile.writeBytes(
            StreamingMediaCryptoEngine.encryptThumbnail(
                thumbnailPngBytes = "GenuineThumbPngBytes".toByteArray(StandardCharsets.UTF_8),
                mediaId = mediaId,
                vaultSlot = 0,
                thumbKey = thumbKey
            )
        )
        val mediaSha = sha256Hex(mediaFile.readBytes())
        val record = MediaRecord(
            id = mediaId,
            title = "Thumb Auth Check",
            originalFileName = "photo.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = mediaPlain.size.toLong(),
            encryptedSizeBytes = mediaFile.length(),
            chunkCount = hdr.totalChunks,
            fileMediaKeyBase64 = Base64.toBase64String(mediaKey),
            thumbnailKeyBase64 = Base64.toBase64String(thumbKey),
            ciphertextSha256Hex = mediaSha
        )
        repo.saveItem(record)
        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = 0,
                mediaId = mediaId,
                formatVersion = hdr.formatVersion,
                relativeMediaPath = "media/$mediaId.pvmedia",
                relativeThumbPath = "thumbs/$mediaId.pvthumb",
                encryptedSizeBytes = mediaFile.length(),
                chunkCount = hdr.totalChunks,
                ciphertextSha256 = mediaSha,
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1L
            )
        )

        // Flip 1 byte in thumbnail ciphertext WITHOUT changing file size -> R1 must reject backup!
        val thumbBytes = thumbFile.readBytes()
        thumbBytes[thumbBytes.lastIndex] = (thumbBytes[thumbBytes.lastIndex].toInt() xor 0x55).toByte()
        thumbFile.writeBytes(thumbBytes)

        val validItems = repo.getAllItemsForBackupOrThrow()
        try {
            repo.createAuthoritativeStreamingBackup(
                items = validItems,
                backupPasswordChars = "ExportStrong!2026".toCharArray(),
                outputStream = ByteArrayOutputStream(),
                kdfParams = testKdf()
            )
            fail("Expected AEADBadTagException / SecurityException when thumbnail fails AEAD authentication")
        } catch (_: Exception) {
            // expected: corrupted thumbnail cannot pass backup validation even though thumbSha was never stored in DB
        }
    }

    @Test
    fun testCategoryBackupBlocksReplaceActiveAndLegacyUnknownScopeIsPreserved() = runBlocking {
        // 1. Export NOTES_ONLY backup and inspect scope flags
        val appFilesDir = tempFolder.newFolder("r2_app_files")
        val password = "R2_STRONG_TEST_PASSWORD_2026".toCharArray()
        val noteFolder = VaultFolder(id = "nf-r2", name = "Work Notes", scope = FolderScope.NOTES)
        val note = NoteRecord(id = "n-r2", title = "Category Note", body = "Scoped body", folderId = noteFolder.id)
        val notesBackup = tempFolder.newFile("r2_notes_only.pvault")
        FileOutputStream(notesBackup).use { fos ->
            EncryptedBackupV2Engine.createPrivacyEnhancedStreamingBackup(
                items = listOf(noteFolder, note),
                backupPasswordChars = password,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = testKdf(),
                exportScope = BackupExportScope.NOTES_ONLY
            )
        }

        val stagedNotes = notesBackup.inputStream().use { fis ->
            EncryptedBackupV2Engine.inspectAndStageBackup(fis, password, tempFolder.newFolder("r2_stage_notes"))
        }
        assertTrue(stagedNotes.payload.isExportScopeKnown)
        assertTrue(stagedNotes.payload.isPartialCategoryBackup)
        assertFalse(stagedNotes.payload.isVerifiedFullBackup)
        assertEquals("Notes Only", stagedNotes.payload.scopeDisplayLabel)

        // Destination vault has unrelated Password & Document records that must survive category restore
        val destDao = InMemoryVaultDao()
        val destSession = VaultSessionManager()
        val destRepo = VaultRepository(destDao, destSession, tempFolder.newFolder("r2_dest_files"), tempFolder.newFolder("r2_dest_cache"))
        assertTrue(destRepo.createInitialVault("DestMaster!2026#Safe".toCharArray(), testKdf()) is AuthResult.Success)
        val keepCred = CredentialRecord(id = "cred-keep-1", serviceName = "Bank", username = "u", password = "p")
        destRepo.saveItem(keepCred)

        // Replace Active must be rejected at repository boundary for partial category backups
        try {
            destRepo.commitRestore(stagedNotes, RestoreMode.REPLACE_ACTIVE_VAULT)
            fail("Expected REPLACE_ACTIVE_VAULT to be blocked for NOTES_ONLY partial backup")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue("Existing credential must still be present after blocked replace", destRepo.getAllItemsForActiveSlot().any { it.id == keepCred.id })

        // Merge preserves unrelated credentials while adding restored notes
        destRepo.commitRestore(stagedNotes, RestoreMode.MERGE_KEEP_NEWEST)
        val mergedIds = destRepo.getAllItemsForActiveSlot().map { it.id }.toSet()
        assertTrue(mergedIds.contains(keepCred.id))
        assertTrue(mergedIds.contains(note.id))
        assertTrue(mergedIds.contains(noteFolder.id))

        // 2. Legacy V2 manifest WITHOUT "exportScope" must be classified as unknown scope, not verified ALL_DATA
        val salt = CryptoEngine.generateSalt()
        val nonce = CryptoEngine.generateNonce()
        val kdf = testKdf()
        val kek = CryptoEngine.deriveKeyArgon2id(password, salt, kdf, 32)
        val manifestJson = JSONObject().apply {
            put("backupSchemaVersion", 2)
            put("exportedAtEpochMs", 1_690_000_000_000L)
            put("appVersionName", "2.0.0")
            put("applicationId", "com.tpeditz.privatevault.v2")
            put("records", JSONArray().apply {
                put(JSONObject(String(VaultRecordCodec.encode(NoteRecord(id = "old-n1", title = "Old", body = "B")), StandardCharsets.UTF_8)))
            })
            put("mediaEntries", JSONArray())
            put("docEntries", JSONArray())
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = CryptoEngine.encryptAesGcm(kek, nonce, manifestJson, CryptoEngine.buildBackupAad(2, kdf))
        val hdrBytes = ByteBuffer.allocate(EncryptedBackupV2Engine.HEADER_BYTES).apply {
            put(EncryptedBackupV2Engine.V2_MAGIC_BYTES)
            putInt(2)
            putInt(kdf.memoryKb)
            putInt(kdf.iterations)
            putInt(kdf.parallelism)
            put(salt)
            put(nonce)
            putInt(cipher.size)
        }.array()
        val (legacyV2Payload, _) = EncryptedBackupV2Engine.decryptAndVerifyManifest(ByteArrayInputStream(hdrBytes + cipher), password)
        assertFalse(legacyV2Payload.isExportScopeKnown)
        assertFalse(legacyV2Payload.isVerifiedFullBackup)
        assertEquals("Backup scope unknown", legacyV2Payload.scopeDisplayLabel)
    }

    @Test
    fun testDocumentsOnlyScopePreservesDocumentFoldersWithoutLeakingOtherCategories() {
        val docFolder1 = VaultFolder(id = "df-1", name = "Taxes", scope = FolderScope.DOCUMENTS)
        val docFolder2 = VaultFolder(id = "df-2", name = "IDs", scope = FolderScope.DOCUMENTS)
        val noteFolder = VaultFolder(id = "nf-1", name = "Personal", scope = FolderScope.NOTES)
        val credFolder = VaultFolder(id = "cf-1", name = "Logins", scope = FolderScope.CREDENTIALS)
        val album = VaultAlbumRecord(id = "alb-1", name = "Trips")
        val doc = DocumentRecord(
            id = "doc-scoped-1",
            title = "Return2026.pdf",
            originalFileName = "Return2026.pdf",
            mimeType = "application/pdf",
            originalSizeBytes = 100L,
            encryptedSizeBytes = 148L,
            chunkCount = 1,
            fileKeyBase64 = Base64.toBase64String(ByteArray(32) { 1 }),
            ciphertextSha256Hex = "a".repeat(64),
            folderId = docFolder1.id
        )
        val note = NoteRecord(id = "n-other", title = "Note", body = "Body", folderId = noteFolder.id)
        val cred = CredentialRecord(id = "c-other", serviceName = "Site", username = "u", password = "p", folderId = credFolder.id)
        val link = SecureLinkRecord(id = "l-other", title = "Link", url = "https://example.test")
        val media = MediaRecord(
            id = "m-other",
            title = "Pic",
            originalFileName = "p.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = 10L,
            encryptedSizeBytes = 58L,
            chunkCount = 1,
            fileMediaKeyBase64 = Base64.toBase64String(ByteArray(32) { 2 }),
            thumbnailKeyBase64 = Base64.toBase64String(ByteArray(32) { 3 }),
            ciphertextSha256Hex = "b".repeat(64),
            albumId = album.id
        )

        val allItems = listOf(docFolder1, docFolder2, noteFolder, credFolder, album, doc, note, cred, link, media)

        val docsOnly = filterItemsForBackupScope(allItems, BackupExportScope.DOCUMENTS_ONLY).map { it.id }.toSet()
        assertEquals(setOf(docFolder1.id, docFolder2.id, doc.id), docsOnly)

        val npl = filterItemsForBackupScope(allItems, BackupExportScope.NOTES_PASSWORDS_LINKS).map { it.id }.toSet()
        assertEquals(setOf(noteFolder.id, credFolder.id, note.id, cred.id, link.id), npl)
    }

    @Test
    fun testFirstLaunchAtomicInitializeAndRestoreRollbackAndRetryConsistency() = runBlocking {
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val filesDir = tempFolder.newFolder("r4_files")
        val cacheDir = tempFolder.newFolder("r4_cache")
        val repo = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val note = NoteRecord(id = "r4-note-1", title = "Restored First Note", body = "First launch content")
        val docId = "r4-doc-1"
        val docKey = CryptoEngine.randomBytes(32)
        val stageDir = tempFolder.newFolder("r4_stage")
        val stageDocsDir = File(stageDir, "docs").apply { mkdirs() }
        val docFile = File(stageDocsDir, "$docId.pvdoc")
        val plainDoc = "First launch restored PDF bytes".toByteArray(StandardCharsets.UTF_8)
        val hdr = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(plainDoc),
            expectedPlaintextSize = plainDoc.size.toLong(),
            targetFile = docFile,
            mediaId = docId,
            vaultSlot = 0,
            fileMediaKey = docKey
        )
        val docRecord = DocumentRecord(
            id = docId,
            title = "Guide.pdf",
            originalFileName = "Guide.pdf",
            mimeType = "application/pdf",
            originalSizeBytes = plainDoc.size.toLong(),
            encryptedSizeBytes = docFile.length(),
            chunkCount = hdr.totalChunks,
            fileKeyBase64 = Base64.toBase64String(docKey),
            ciphertextSha256Hex = sha256Hex(docFile.readBytes())
        )
        val staged = StagedBackupRestore(
            payload = BackupPayload(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersionName = "2.0.4",
                items = listOf(note, docRecord),
                exportScope = BackupExportScope.ALL_DATA,
                isExportScopeKnown = true
            ),
            stagingDir = stageDir
        )

        // 1. Mid-operation cancellation leaves database UNINITIALIZED and keeps staged files intact for retry!
        var cancelChecks = 0
        try {
            repo.initializeVaultAndCommitRestore(
                masterPasswordChars = "FirstLaunchMaster!2026".toCharArray(),
                staged = staged,
                kdfParams = testKdf(),
                isCancelled = {
                    cancelChecks++
                    cancelChecks >= 3
                }
            )
            fail("Expected CancellationException during first-launch restore")
        } catch (_: CancellationException) {
            // expected
        }
        assertFalse("Vault must remain uninitialized after aborted first-launch restore", repo.isVaultInitialized())
        assertNull("Session must stay locked after aborted first-launch restore", sessionManager.getActiveSession())
        assertTrue("Staged backup dir must still exist for retry", stageDir.exists())

        // 2. Retry on same staged backup completes atomically, initializes vault, opens session, and restores items
        val retryRes = repo.initializeVaultAndCommitRestore(
            masterPasswordChars = "FirstLaunchMaster!2026".toCharArray(),
            staged = staged,
            kdfParams = testKdf(),
            isCancelled = { false }
        )
        assertTrue(retryRes is AuthResult.Success)
        assertTrue(repo.isVaultInitialized())
        assertNotNull(sessionManager.getActiveSession())
        val loadedIds = repo.getAllItemsForActiveSlot().map { it.id }.toSet()
        assertTrue(loadedIds.contains(note.id))
        assertTrue(loadedIds.contains(docId))

        // 3. Running again when vault already exists must fail cleanly without touching existing data
        val duplicateAttempt = repo.initializeVaultAndCommitRestore(
            masterPasswordChars = "AnotherMaster!2026".toCharArray(),
            staged = staged,
            kdfParams = testKdf()
        )
        assertTrue(duplicateAttempt is AuthResult.Error)
        assertTrue(repo.getAllItemsForActiveSlot().any { it.id == docId })
    }

    @Test
    fun testV3FinalFlushCancellationCheckAbortsCleanlyAndDecryptManifestSupportsCancel() {
        val appFilesDir = tempFolder.newFolder("r5_files")
        val password = "R5_CANCEL_PASSWORD_2026".toCharArray()
        val goodBackup = tempFolder.newFile("r5_good.pvault")

        FileOutputStream(goodBackup).use { fos ->
            EncryptedBackupV2Engine.createPrivacyEnhancedStreamingBackup(
                items = listOf(NoteRecord(id = "n-r5", title = "Keep", body = "Good")),
                backupPasswordChars = password,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = testKdf()
            )
        }
        val expectedGoodBytes = goodBackup.readBytes()

        // R5: Cancel before final flush aborts export cleanly without throwing out an uncancelled false success
        var progressCalls = 0
        try {
            EncryptedBackupV2Engine.createPrivacyEnhancedStreamingBackup(
                items = listOf(NoteRecord(id = "n-r5-2", title = "Aborted", body = "Cancelled at end")),
                backupPasswordChars = password,
                outputStream = ByteArrayOutputStream(),
                appFilesDir = appFilesDir,
                kdfParams = testKdf(),
                onProgress = { _, _ -> progressCalls++ },
                isCancelled = { progressCalls > 0 }
            )
            fail("Expected CancellationException when cancelled before final flush")
        } catch (_: CancellationException) {
            // expected
        }
        assertArrayEquals("Prior completed backup must remain untouched", expectedGoodBytes, goodBackup.readBytes())

        // R5: decryptAndVerifyManifest honors isCancelled
        try {
            goodBackup.inputStream().use { fis ->
                EncryptedBackupV2Engine.decryptAndVerifyManifest(fis, password, isCancelled = { true })
            }
            fail("Expected CancellationException in decryptAndVerifyManifest")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
