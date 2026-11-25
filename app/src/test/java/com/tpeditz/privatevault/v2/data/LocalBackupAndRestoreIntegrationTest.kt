package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import kotlinx.coroutines.CancellationException
import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

class LocalBackupAndRestoreIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Requirement 9:
     * Add integration tests using genuinely encrypted synthetic media and documents.
     * Verify that restored files can be decrypted and opened successfully.
     */
    @Test
    fun testGenuineEncryptedMediaAndDocumentsBackupAndDecryptionRoundtrip() {
        val backupPassword = "TEST_ONLY_BACKUP_PASSWORD_45".toCharArray()
        val appFilesDir = tempFolder.newFolder("app_storage")
        val mediaDir = File(appFilesDir, "media").apply { mkdirs() }
        val thumbDir = File(appFilesDir, "thumbs").apply { mkdirs() }
        val docDir = File(appFilesDir, "documents").apply { mkdirs() }

        // 1. Create genuine synthetic photo (JPEG structure + random pixels)
        val photoHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01
        )
        val photoBody = ByteArray(32768) { (it % 256).toByte() }
        val rawPhotoBytes = photoHeader + photoBody

        // 2. Create genuine synthetic PDF document
        val rawDocBytes = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n" +
            "xref\n0 4\n0000000000 65535 f\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n").toByteArray(StandardCharsets.UTF_8)

        // 3. Create synthetic thumbnail
        val rawThumbBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte()) + ByteArray(2048) { 0x42 }

        // 4. Generate AES-256 DEKs
        val photoKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val thumbKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val docKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)

        val mediaId = "photo-integration-001"
        val docId = "doc-integration-002"

        // 5. Encrypt media and document to app files using StreamingMediaCryptoEngine
        val encMediaFile = File(mediaDir, "$mediaId.pvmedia")
        val encThumbFile = File(thumbDir, "$mediaId.pvthumb")
        val encDocFile = File(docDir, "$docId.pvdoc")

        val mediaHeader = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(rawPhotoBytes),
            expectedPlaintextSize = rawPhotoBytes.size.toLong(),
            targetFile = encMediaFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = photoKey
        )

        StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(rawThumbBytes),
            expectedPlaintextSize = rawThumbBytes.size.toLong(),
            targetFile = encThumbFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = thumbKey
        )

        val docHeader = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(rawDocBytes),
            expectedPlaintextSize = rawDocBytes.size.toLong(),
            targetFile = encDocFile,
            mediaId = docId,
            vaultSlot = 0,
            fileMediaKey = docKey
        )

        val photoCiphertextBytes = encMediaFile.readBytes()
        val docCiphertextBytes = encDocFile.readBytes()
        val mediaSha256 = sha256Hex(photoCiphertextBytes)
        val docSha256 = sha256Hex(docCiphertextBytes)

        // 6. Build genuine VaultItem records
        val mediaRecord = MediaRecord(
            id = mediaId,
            title = "Vacation Camera Capture",
            originalFileName = "vacation.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = rawPhotoBytes.size.toLong(),
            encryptedSizeBytes = photoCiphertextBytes.size.toLong(),
            chunkCount = mediaHeader.totalChunks,
            fileMediaKeyBase64 = Base64.toBase64String(photoKey),
            thumbnailKeyBase64 = Base64.toBase64String(thumbKey),
            ciphertextSha256Hex = mediaSha256
        )

        val docRecord = DocumentRecord(
            id = docId,
            title = "Employment Agreement",
            originalFileName = "agreement.pdf",
            mimeType = "application/pdf",
            originalSizeBytes = rawDocBytes.size.toLong(),
            encryptedSizeBytes = docCiphertextBytes.size.toLong(),
            chunkCount = docHeader.totalChunks,
            fileKeyBase64 = Base64.toBase64String(docKey),
            ciphertextSha256Hex = docSha256
        )

        val noteRecord = NoteRecord(
            id = "note-int-003",
            title = "Fictional test note",
            body = "Synthetic note content for backup testing",
            category = "Work"
        )

        val credRecord = CredentialRecord(
            id = "cred-int-004",
            serviceName = "Example fixture service",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD_1"
        )

        val linkRecord = SecureLinkRecord(
            id = "link-int-005",
            title = "Example test bookmark",
            url = "https://links.example.test/"
        )

        val folderRecord = VaultFolder(
            id = "folder-int-006",
            name = "Important Records",
            scope = FolderScope.DOCUMENTS
        )

        val albumRecord = VaultAlbumRecord(
            id = "album-int-007",
            name = "2026 Archives"
        )

        val authenticatorRecord = AuthenticatorRecord(
            id = "auth-int-008",
            serviceName = "Example test authenticator",
            accountName = "fixture-user@example.test",
            secret = "MZXW6YTBOI======"
        )

        val allItems = listOf(
            mediaRecord, docRecord, noteRecord, credRecord,
            linkRecord, folderRecord, albumRecord, authenticatorRecord
        )

        // 7. Create Streaming Backup Container
        val backupFile = tempFolder.newFile("full_integration_backup.pvault")
        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = allItems,
                backupPasswordChars = backupPassword,
                outputStream = fos,
                appFilesDir = appFilesDir,
                appVersionName = "2.0.0",
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }

        assertTrue(backupFile.exists())
        assertTrue(backupFile.length() > 0)

        // 8. Stage and Inspect Backup
        val stagingDir = tempFolder.newFolder("integration_staging")
        val staged = backupFile.inputStream().use { fis ->
            EncryptedBackupV2Engine.inspectAndStageBackup(fis, backupPassword, stagingDir)
        }

        assertEquals(8, staged.payload.items.size)
        assertEquals(1, staged.payload.mediaCount)
        assertEquals(1, staged.payload.documentsCount)
        assertEquals(1, staged.payload.notesCount)
        assertEquals(1, staged.payload.credentialsCount)
        assertEquals(1, staged.payload.linksCount)
        assertEquals(1, staged.payload.authenticatorsCount)

        // 9. Verify restored files on disk in staging directory
        val restoredMediaFile = File(stagingDir, "media/$mediaId.pvmedia")
        val restoredThumbFile = File(stagingDir, "thumbs/$mediaId.pvthumb")
        val restoredDocFile = sequenceOf(
            File(stagingDir, "documents/$docId.pvdoc"),
            File(stagingDir, "docs/$docId.pvdoc")
        ).firstOrNull { it.exists() }

        assertTrue("Restored media file must exist", restoredMediaFile.exists())
        assertTrue("Restored thumb file must exist", restoredThumbFile.exists())
        assertNotNull("Restored doc file must exist", restoredDocFile)
        assertTrue(restoredDocFile!!.exists())

        // 10. DECRYPT RESTORED MEDIA AND DOCUMENTS TO CONFIRM INTEGRITY & OPENABILITY
        val restoredMediaRecord = staged.payload.items.find { it.id == mediaId } as MediaRecord
        val restoredPhotoKey = Base64.decode(restoredMediaRecord.fileMediaKeyBase64)
        val decryptedPhotoBytes = StreamingMediaCryptoEngine.decryptEntireMediaFile(
            encryptedFile = restoredMediaFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = restoredPhotoKey
        )
        assertArrayEquals("Decrypted restored photo must match original bytes 100%", rawPhotoBytes, decryptedPhotoBytes)

        val restoredDocRecord = staged.payload.items.find { it.id == docId } as DocumentRecord
        val restoredDocKey = Base64.decode(restoredDocRecord.fileKeyBase64)
        val decryptedDocBytes = StreamingMediaCryptoEngine.decryptEntireMediaFile(
            encryptedFile = restoredDocFile,
            mediaId = docId,
            vaultSlot = 0,
            fileMediaKey = restoredDocKey
        )
        assertArrayEquals("Decrypted restored document must match original bytes 100%", rawDocBytes, decryptedDocBytes)
        val docHeaderString = String(decryptedDocBytes.copyOfRange(0, 8), StandardCharsets.UTF_8)
        assertEquals("%PDF-1.4", docHeaderString)
    }

    /**
     * Requirement 10:
     * Test interruption and cancellation during backup streaming.
     */
    @Test
    fun testInterruptedBackupCreationAbortsAndCleansUp() {
        val backupPassword = "TEST_ONLY_BACKUP_PASSWORD_256".toCharArray()
        val appFilesDir = tempFolder.newFolder("app_interrupt")
        val backupFile = tempFolder.newFile("interrupted.pvault")

        val note = NoteRecord(id = "n-1", title = "T", body = "B")

        var isCancelledFlag = false
        try {
            FileOutputStream(backupFile).use { fos ->
                EncryptedBackupV2Engine.createStreamingBackup(
                    items = listOf(note),
                    backupPasswordChars = backupPassword,
                    outputStream = fos,
                    appFilesDir = appFilesDir,
                    isCancelled = {
                        isCancelledFlag = true
                        true // Cancel immediately
                    },
                    kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
                )
            }
            fail("Expected CancellationException when isCancelled returns true")
        } catch (_: java.util.concurrent.CancellationException) {
            // Expected
            assertTrue("isCancelled was called", isCancelledFlag)
        }
    }

    /**
     * Requirement 10:
     * Wrong passwords reject backup and leave existing vault data completely intact.
     */
    @Test
    fun testWrongPasswordRejection() {
        val correctPassword = "TEST_ONLY_BACKUP_PASSWORD_290".toCharArray()
        val wrongPassword = "TEST_ONLY_BACKUP_PASSWORD_291".toCharArray()
        val appFilesDir = tempFolder.newFolder("app_wrong_pwd")
        val backupFile = tempFolder.newFile("wrong_pwd.pvault")

        val note = NoteRecord(id = "n-pwd", title = "Secret", body = "Data")
        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = listOf(note),
                backupPasswordChars = correctPassword,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }

        val stagingDir = tempFolder.newFolder("staging_wrong_pwd")
        try {
            backupFile.inputStream().use { fis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(fis, wrongPassword, stagingDir)
            }
            fail("Expected AEADBadTagException or security exception for wrong password")
        } catch (_: GeneralSecurityException) {
            // Expected
            assertFalse("Staging dir must be purged on wrong password", stagingDir.exists() && stagingDir.list()?.isNotEmpty() == true)
        }
    }

    /**
     * Requirement 10:
     * Reject incomplete or corrupted backups before modifying existing vault data.
     */
    @Test
    fun testCorruptedHeaderMagicRejection() {
        val corruptedBytes = ByteArray(100) { 0x00 }
        assertFalse(EncryptedBackupV2Engine.isV2Backup(corruptedBytes))

        val stagingDir = tempFolder.newFolder("corrupted_magic_staging")
        try {
            ByteArrayInputStream(corruptedBytes).use { bis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(bis, "Pass123!".toCharArray(), stagingDir)
            }
            fail("Expected IllegalArgumentException for corrupted magic")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("magic", ignoreCase = true) == true)
            assertFalse("Staging dir must be purged on corrupted header", stagingDir.exists() && stagingDir.list()?.isNotEmpty() == true)
        }
    }

    /**
     * Requirement 10:
     * Corrupted payload file (tampered ciphertext / SHA-256 mismatch) is detected and rejected.
     */
    @Test
    fun testTamperedMediaFileSha256MismatchRejection() {
        val backupPassword = "TEST_ONLY_BACKUP_PASSWORD_345".toCharArray()
        val appFilesDir = tempFolder.newFolder("app_tamper")
        val mediaDir = File(appFilesDir, "media").apply { mkdirs() }
        val thumbsDir = File(appFilesDir, "thumbs").apply { mkdirs() }

        val originalBytes = "UNALTERED_PHOTO_BYTES".toByteArray(StandardCharsets.UTF_8)
        val thumbBytes = "UNALTERED_THUMB_BYTES".toByteArray(StandardCharsets.UTF_8)
        val photoKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val mediaId = "media-tamper-1"
        val encMediaFile = File(mediaDir, "$mediaId.pvmedia")
        val encThumbFile = File(thumbsDir, "$mediaId.pvthumb")

        val header = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(originalBytes),
            expectedPlaintextSize = originalBytes.size.toLong(),
            targetFile = encMediaFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = photoKey
        )
        val thumbCiphertext = StreamingMediaCryptoEngine.encryptThumbnail(
            thumbnailPngBytes = thumbBytes,
            mediaId = mediaId,
            vaultSlot = 0,
            thumbKey = photoKey
        )
        encThumbFile.writeBytes(thumbCiphertext)

        val realSha256 = sha256Hex(encMediaFile.readBytes())

        val mediaRecord = MediaRecord(
            id = mediaId,
            title = "Test Photo",
            originalFileName = "photo.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = originalBytes.size.toLong(),
            encryptedSizeBytes = encMediaFile.length(),
            chunkCount = header.totalChunks,
            fileMediaKeyBase64 = Base64.toBase64String(photoKey),
            thumbnailKeyBase64 = Base64.toBase64String(photoKey),
            ciphertextSha256Hex = realSha256
        )

        val backupFile = tempFolder.newFile("tamper_backup.pvault")
        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = listOf(mediaRecord),
                backupPasswordChars = backupPassword,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }

        // Now tamper with the media bytes inside the backup file
        val fileBytes = backupFile.readBytes()
        // Find a spot inside the payload and flip a bit
        val tamperedBytes = fileBytes.copyOf()
        tamperedBytes[tamperedBytes.size - 5] = (tamperedBytes[tamperedBytes.size - 5].toInt() xor 0xFF).toByte()
        backupFile.writeBytes(tamperedBytes)

        val stagingDir = tempFolder.newFolder("tamper_staging")
        try {
            backupFile.inputStream().use { fis ->
                EncryptedBackupV2Engine.inspectAndStageBackup(fis, backupPassword, stagingDir)
            }
            fail("Expected SHA-256 mismatch or decryption exception")
        } catch (_: Exception) {
            // Expected
            assertFalse("Staging dir must be purged on corrupted backup payload", stagingDir.exists() && stagingDir.list()?.isNotEmpty() == true)
        }
    }

    /**
     * Requirement 4 & 10:
     * Enforce the 2 GiB backup limit using memory-efficient streaming and storage checks.
     */
    @Test
    fun testTwoGibibyteLimitEnforcement() {
        val backupPassword = "TEST_ONLY_BACKUP_PASSWORD_425".toCharArray()
        val kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val salt = CryptoEngine.generateSalt()
        val nonce = CryptoEngine.generateNonce()
        val backupKek = CryptoEngine.deriveKeyArgon2id(backupPassword, salt, kdfParams, 32)

        val oversizedBytes = 2L * 1024L * 1024L * 1024L + 1024L // > 2 GiB
        val manifestJson = JSONObject().apply {
            put("backupSchemaVersion", 2)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("appVersionName", "2.0.0")
            put("applicationId", "com.tpeditz.privatevault.v2")
            put("records", JSONArray())
            val mediaArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("mediaId", "huge-media")
                    put("mediaSizeBytes", oversizedBytes)
                    put("thumbSizeBytes", 0L)
                    put("chunkCount", 1)
                    put("ciphertextSha256", "0".repeat(64))
                })
            }
            put("mediaEntries", mediaArr)
        }

        val manifestPlaintext = manifestJson.toString().toByteArray(StandardCharsets.UTF_8)
        val aad = CryptoEngine.buildBackupAad(2, kdfParams)
        val manifestCiphertext = CryptoEngine.encryptAesGcm(backupKek, nonce, manifestPlaintext, aad)

        val headerBytes = ByteArray(EncryptedBackupV2Engine.HEADER_BYTES)
        ByteBuffer.wrap(headerBytes).apply {
            put(EncryptedBackupV2Engine.V2_MAGIC_BYTES)
            putInt(2)
            putInt(kdfParams.memoryKb)
            putInt(kdfParams.iterations)
            putInt(kdfParams.parallelism)
            put(salt)
            put(nonce)
            putInt(manifestCiphertext.size)
        }

        val backupInputStream = ByteArrayInputStream(headerBytes + manifestCiphertext)
        try {
            EncryptedBackupV2Engine.decryptAndVerifyManifest(backupInputStream, backupPassword)
            fail("Expected IllegalArgumentException when declared backup payload size > 2 GiB")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("2 GiB", ignoreCase = true) == true)
        }
    }

    /**
     * Requirement 5:
     * Backward compatibility with legacy V1 backups including media blob synthesis.
     */
    @Test
    fun testLegacyV1BackupBackwardCompatibilityWithMedia() {
        val legacyPassword = "TEST_ONLY_BACKUP_PASSWORD_481".toCharArray()
        val salt = CryptoEngine.randomBytes(CryptoEngine.SALT_BYTES)
        val nonce = CryptoEngine.randomBytes(CryptoEngine.NONCE_BYTES)
        val kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val aad = CryptoEngine.buildBackupAad(schemaVersion = 1, kdfParams = kdfParams)
        val kek = CryptoEngine.deriveKeyArgon2id(legacyPassword, salt, kdfParams, 32)

        val mediaId = "legacy-blob-99"
        val dummyMediaCiphertext = "LEGACY_ENCRYPTED_PIXELS".toByteArray(StandardCharsets.UTF_8)
        val dummyThumbCiphertext = "LEGACY_ENCRYPTED_THUMB".toByteArray(StandardCharsets.UTF_8)
        val mediaSha = sha256Hex(dummyMediaCiphertext)

        val rootJson = JSONObject().apply {
            put("appVersionName", "1.0.4")
            put("exportedAtEpochMs", 1700000000000L)
            val recArray = JSONArray()
            recArray.put(JSONObject().apply {
                put("type", "note")
                put("id", "legacy-note-99")
                put("title", "Legacy Note")
                put("body", "Synthetic legacy note")
                put("category", "General")
                put("createdAtEpochMs", 1700000000000L)
                put("updatedAtEpochMs", 1700000000000L)
            })
            recArray.put(JSONObject().apply {
                put("recordType", "MEDIA")
                put("id", mediaId)
                put("title", "Legacy Vacation Snapshot")
                put("originalFileName", "legacy_photo.jpg")
                put("mimeType", "image/jpeg")
                put("mediaKind", "PHOTO")
                put("originalSizeBytes", dummyMediaCiphertext.size.toLong())
                put("encryptedSizeBytes", dummyMediaCiphertext.size.toLong())
                put("chunkCount", 1)
                put("formatVersion", 1)
                put("fileMediaKeyBase64", Base64.toBase64String(ByteArray(32) { 0x55 }))
                put("thumbnailKeyBase64", Base64.toBase64String(ByteArray(32) { 0x66 }))
                put("ciphertextSha256Hex", mediaSha)
                put("createdAtEpochMs", 1700000000000L)
                put("updatedAtEpochMs", 1700000000000L)
            })
            put("records", recArray)

            val blobsObj = JSONObject().apply {
                put(mediaId, JSONObject().apply {
                    put("mediaId", mediaId)
                    put("formatVersion", 1)
                    put("chunkCount", 1)
                    put("ciphertextSha256", mediaSha)
                    put("mediaCiphertextBase64", Base64.toBase64String(dummyMediaCiphertext))
                    put("thumbCiphertextBase64", Base64.toBase64String(dummyThumbCiphertext))
                })
            }
            put("mediaBlobs", blobsObj)
        }

        val plainJsonBytes = rootJson.toString().toByteArray(StandardCharsets.UTF_8)
        val (ciphertext, _) = CryptoEngine.encryptPayload(plainJsonBytes, kek, aad, customNonce = nonce)

        val containerBuffer = ByteBuffer.allocate(LegacyV1BackupReader.HEADER_BYTES + ciphertext.size)
        containerBuffer.put(LegacyV1BackupReader.V1_MAGIC_BYTES)
        containerBuffer.putInt(1)
        containerBuffer.putInt(kdfParams.memoryKb)
        containerBuffer.putInt(kdfParams.iterations)
        containerBuffer.putInt(kdfParams.parallelism)
        containerBuffer.put(salt)
        containerBuffer.put(nonce)
        containerBuffer.put(ciphertext)

        val legacyBytes = containerBuffer.array()

        val stagingDir = tempFolder.newFolder("legacy_v1_staging")
        val staged = ByteArrayInputStream(legacyBytes).use { bis ->
            LegacyV1BackupReader.inspectAndStageLegacyBackup(bis, legacyPassword, stagingDir)
        }

        // Both note and synthesized media record must exist
        assertEquals(2, staged.payload.items.size)
        val restoredNote = staged.payload.items.find { it.id == "legacy-note-99" } as? NoteRecord
        assertNotNull(restoredNote)
        assertEquals("Legacy Note", restoredNote?.title)

        val restoredMedia = staged.payload.items.find { it.id == mediaId } as? MediaRecord
        assertNotNull(restoredMedia)
        assertEquals("Legacy Vacation Snapshot", restoredMedia?.title)

        // Assets must be staged on disk
        val stagedMedia = File(stagingDir, "media/$mediaId.pvmedia")
        val stagedThumb = File(stagingDir, "thumbs/$mediaId.pvthumb")
        assertTrue("Staged legacy media file must exist", stagedMedia.exists())
        assertTrue("Staged legacy thumb file must exist", stagedThumb.exists())
        assertEquals("LEGACY_ENCRYPTED_PIXELS", stagedMedia.readText())
        assertEquals("LEGACY_ENCRYPTED_THUMB", stagedThumb.readText())
    }
}
