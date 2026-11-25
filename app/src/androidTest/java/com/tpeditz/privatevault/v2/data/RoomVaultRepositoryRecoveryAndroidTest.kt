package com.tpeditz.privatevault.v2.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * On-device / emulator Instrumentation Test exercising genuine Room SQLite transactions,
 * VaultRepository recovery after interrupted restore, and full streaming backup/restore round-trips.
 */
@RunWith(AndroidJUnit4::class)
class RoomVaultRepositoryRecoveryAndroidTest {

    private lateinit var db: PrivateVaultDatabase
    private lateinit var testRootDir: File
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var sessionManager: VaultSessionManager
    private lateinit var repository: VaultRepository

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testRootDir = File(context.cacheDir, "android_test_repo_${System.currentTimeMillis()}").apply { mkdirs() }
        filesDir = File(testRootDir, "files").apply { mkdirs() }
        cacheDir = File(testRootDir, "cache").apply { mkdirs() }
        db = PrivateVaultDatabase.createInMemoryForTest(context)
        sessionManager = VaultSessionManager()
        repository = VaultRepository(
            context = context,
            database = db,
            dao = db.vaultDao(),
            sessionManager = sessionManager,
            preferencesRepository = null,
            baseFilesDir = filesDir,
            baseCacheDir = cacheDir
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        testRootDir.deleteRecursively()
    }

    @Test
    fun testRoomTransactionCommitRestoreAndInterruptedJournalRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val createRes = repository.createInitialVault("MasterPass!123".toCharArray())
        assertTrue(createRes is AuthResult.Success)

        val mediaPlain = ByteArray(16_384) { (it % 251).toByte() }
        val thumbPlain = "thumb-png-header".toByteArray()
        val mediaId = "room_media_1"
        val mediaKey = ByteArray(32) { 0x33 }
        val thumbKey = ByteArray(32) { 0x44 }

        val relMediaPath = "media/$mediaId.pvmedia"
        val relThumbPath = "thumbs/$mediaId.pvthumb"
        val encMediaFile = File(filesDir, relMediaPath).apply { parentFile?.mkdirs() }
        val encThumbFile = File(filesDir, relThumbPath).apply { parentFile?.mkdirs() }

        StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(mediaPlain),
            expectedPlaintextSize = mediaPlain.size.toLong(),
            targetFile = encMediaFile,
            mediaId = mediaId,
            vaultSlot = VaultSessionManager.REAL_SLOT,
            fileMediaKey = mediaKey
        )
        val thumbCiphertext = StreamingMediaCryptoEngine.encryptThumbnail(
            thumbnailPngBytes = thumbPlain,
            mediaId = mediaId,
            vaultSlot = VaultSessionManager.REAL_SLOT,
            thumbKey = thumbKey
        )
        encThumbFile.writeBytes(thumbCiphertext)

        val mediaRecord = MediaRecord(
            id = mediaId,
            title = "Room Photo",
            originalFileName = "photo.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = mediaPlain.size.toLong(),
            encryptedSizeBytes = encMediaFile.length(),
            chunkCount = 1,
            formatVersion = 1,
            fileMediaKeyBase64 = Base64.toBase64String(mediaKey),
            thumbnailKeyBase64 = Base64.toBase64String(thumbKey),
            ciphertextSha256Hex = sha256Hex(encMediaFile.readBytes())
        )

        db.vaultDao().upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = VaultSessionManager.REAL_SLOT,
                mediaId = mediaId,
                formatVersion = 1,
                relativeMediaPath = relMediaPath,
                relativeThumbPath = relThumbPath,
                encryptedSizeBytes = encMediaFile.length(),
                chunkCount = 1,
                ciphertextSha256 = mediaRecord.ciphertextSha256Hex,
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1_000L
            )
        )
        repository.saveItem(mediaRecord)
        repository.saveItem(NoteRecord(id = "note_1", title = "Secret", body = "Stored in Room"))

        // Export authoritative backup using real repository
        val items = repository.getAllItemsForActiveSlot()
        val baos = ByteArrayOutputStream()
        repository.createAuthoritativeStreamingBackup(
            items = items,
            backupPasswordChars = "BackupPwd!456".toCharArray(),
            outputStream = baos,
            kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        )

        // Simulate crash marker left over with an uncommitted orphan file next to real DB-committed file
        val orphanMedia = File(filesDir, "media/uncommitted_orphan.pvmedia").apply { writeBytes(ByteArray(64)) }
        File(cacheDir, "vault_restore_journal.json").writeText(
            """{"pendingOldFiles":["${encMediaFile.absolutePath.replace("\\", "\\\\")}","${orphanMedia.absolutePath.replace("\\", "\\\\")}"]}"""
        )

        // Fresh repository recovery on startup must keep DB-committed encMediaFile & delete orphanMedia
        val restartedRepo = VaultRepository(
            context = context,
            database = db,
            dao = db.vaultDao(),
            sessionManager = sessionManager,
            preferencesRepository = null,
            baseFilesDir = filesDir,
            baseCacheDir = cacheDir
        )
        restartedRepo.recoverIncompleteRestoreIfPresent()
        assertTrue("DB-referenced media file must survive recovery", encMediaFile.exists())
        assertFalse("Unreferenced orphan file must be cleaned", orphanMedia.exists())

        // Stage & commit REPLACE restore
        val stagingDir = File(cacheDir, "stage_room").apply { mkdirs() }
        val staged = EncryptedBackupV2Engine.inspectAndStageBackup(
            ByteArrayInputStream(baos.toByteArray()),
            "BackupPwd!456".toCharArray(),
            stagingDir
        )
        val report = restartedRepo.commitRestore(staged, RestoreMode.REPLACE_ACTIVE_VAULT)
        assertEquals(2, report.restoredItemsCount)
        assertEquals(1, report.restoredMediaCount)

        val restoredItems = restartedRepo.getAllItemsForActiveSlot()
        assertTrue(restoredItems.any { it is NoteRecord && it.body == "Stored in Room" })
        val restoredMedia = restoredItems.filterIsInstance<MediaRecord>().first { it.id == mediaId }
        val decryptedMedia = restartedRepo.loadDecryptedPhotoBytes(restoredMedia)
        assertArrayEquals(mediaPlain, decryptedMedia)
        val decryptedThumb = restartedRepo.loadDecryptedThumbnailBytes(restoredMedia)
        assertArrayEquals(thumbPlain, decryptedThumb)
    }
}
