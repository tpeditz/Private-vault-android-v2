package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException

class EncryptedBackupV2EngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsV2BackupMagicCheck() {
        val validHeader = EncryptedBackupV2Engine.V2_MAGIC_BYTES + ByteArray(48)
        assertTrue(EncryptedBackupV2Engine.isV2Backup(validHeader))

        val invalidHeader = "INVALID_MAGIC_HEADER".toByteArray(StandardCharsets.US_ASCII)
        assertFalse(EncryptedBackupV2Engine.isV2Backup(invalidHeader))
    }

    @Test
    fun testBackupV2ExportAndRestoreRoundtrip() {
        val testPassword = "TEST_ONLY_BACKUP_PASSWORD_35".toCharArray()

        val note = NoteRecord(
            id = "note-b2-1",
            title = "Encrypted Confidential Note",
            body = "Synthetic note for testing",
            category = "Personal"
        )
        val cred = CredentialRecord(
            id = "cred-b2-2",
            serviceName = "Example fixture service",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD_1"
        )

        val items = listOf<VaultItem>(note, cred)
        val backupFile = tempFolder.newFile("test_backup.pvault")
        val appFilesDir = tempFolder.newFolder("app_files")

        // 1. Export streaming backup
        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = items,
                backupPasswordChars = testPassword,
                outputStream = fos,
                appFilesDir = appFilesDir,
                appVersionName = "2.0.0",
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }

        assertTrue(backupFile.length() > EncryptedBackupV2Engine.HEADER_BYTES)

        // 2. Read and verify manifest
        val (manifest, details) = backupFile.inputStream().use { fis ->
            EncryptedBackupV2Engine.decryptAndVerifyManifest(fis, testPassword)
        }

        assertEquals(2, manifest.items.size)
        assertEquals("2.0.0", manifest.appVersionName)
        assertEquals(2, manifest.notesCount + manifest.credentialsCount)

        // 3. Restore records
        val restoredNote = manifest.items.find { it.id == "note-b2-1" } as? NoteRecord
        val restoredCred = manifest.items.find { it.id == "cred-b2-2" } as? CredentialRecord

        assertNotNull(restoredNote)
        assertEquals(note.title, restoredNote?.title)
        assertEquals(note.body, restoredNote?.body)

        assertNotNull(restoredCred)
        assertEquals(cred.serviceName, restoredCred?.serviceName)
        assertEquals(cred.password, restoredCred?.password)

        // 4. Test stage restore
        val stagingDir = tempFolder.newFolder("staging")
        val staged = backupFile.inputStream().use { fis ->
            EncryptedBackupV2Engine.inspectAndStageBackup(fis, testPassword, stagingDir)
        }
        assertEquals(2, staged.payload.items.size)
        assertTrue(staged.stagingDir.exists())
    }

    @Test
    fun testBackupV2WrongPasswordFails() {
        val correctPassword = "TEST_ONLY_BACKUP_PASSWORD_100".toCharArray()
        val wrongPassword = "TEST_ONLY_BACKUP_PASSWORD_101".toCharArray()

        val note = NoteRecord(id = "n-1", title = "T", body = "B")
        val backupFile = tempFolder.newFile("test_wrong_pwd.pvault")
        val appFilesDir = tempFolder.newFolder("app_files_wrong")

        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = listOf(note),
                backupPasswordChars = correctPassword,
                outputStream = fos,
                appFilesDir = appFilesDir,
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }

        try {
            backupFile.inputStream().use { fis ->
                EncryptedBackupV2Engine.decryptAndVerifyManifest(fis, wrongPassword)
            }
            fail("Expected AEADBadTagException for wrong backup password")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun testFullBackupAndRestoreRoundtripAllRecordTypes() {
        val testPassword = "TEST_ONLY_BACKUP_PASSWORD_129".toCharArray()
        val appFilesDir = tempFolder.newFolder("full_app_files")
        val mediaDir = File(appFilesDir, "media").apply { mkdirs() }
        val thumbDir = File(appFilesDir, "thumbs").apply { mkdirs() }
        val docDir = File(appFilesDir, "documents").apply { mkdirs() }

        val mediaContent = "ENCRYPTED_MEDIA_CONTENT".toByteArray(StandardCharsets.UTF_8)
        val thumbContent = "ENCRYPTED_THUMB_CONTENT".toByteArray(StandardCharsets.UTF_8)
        val docContent = "ENCRYPTED_DOC_CONTENT".toByteArray(StandardCharsets.UTF_8)

        fun computeSha256(data: ByteArray): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(data).joinToString("") { "%02x".format(it) }
        }

        val mediaSha256 = computeSha256(mediaContent)
        val docSha256 = computeSha256(docContent)

        val note = NoteRecord(id = "note-full-1", title = "Full Note", body = "Body text", category = "Work")
        val cred = CredentialRecord(id = "cred-full-2", serviceName = "Example fixture service B", username = "fixture-b@example.test", password = "TEST_ONLY_PASSWORD_2")
        val media = MediaRecord(
            id = "media-full-3",
            title = "Holiday Photo",
            originalFileName = "photo.jpg",
            mimeType = "image/jpeg",
            mediaKind = MediaKind.PHOTO,
            originalSizeBytes = 1000L,
            encryptedSizeBytes = mediaContent.size.toLong(),
            chunkCount = 1,
            fileMediaKeyBase64 = "key1Base64SecretKeyBytesLongEnough==",
            thumbnailKeyBase64 = "key2Base64SecretKeyBytesLongEnough==",
            ciphertextSha256Hex = mediaSha256
        )
        val doc = DocumentRecord(
            id = "doc-full-4",
            title = "Contract",
            originalFileName = "contract.pdf",
            mimeType = "application/pdf",
            originalSizeBytes = 2000L,
            encryptedSizeBytes = docContent.size.toLong(),
            chunkCount = 1,
            fileKeyBase64 = "docKeyBase64SecretKeyBytesLongEnough==",
            ciphertextSha256Hex = docSha256
        )
        val link = SecureLinkRecord(id = "link-full-5", title = "Example", url = "https://links.example.test/")
        val album = VaultAlbumRecord(id = "album-full-6", name = "Vacation 2026")

        // Write disk assets
        File(mediaDir, "media-full-3.pvmedia").writeBytes(mediaContent)
        File(thumbDir, "media-full-3.pvthumb").writeBytes(thumbContent)
        File(docDir, "doc-full-4.pvdoc").writeBytes(docContent)

        val items = listOf<VaultItem>(note, cred, media, doc, link, album)
        val backupFile = tempFolder.newFile("full_test_backup.pvault")

        FileOutputStream(backupFile).use { fos ->
            EncryptedBackupV2Engine.createStreamingBackup(
                items = items,
                backupPasswordChars = testPassword,
                outputStream = fos,
                appFilesDir = appFilesDir,
                appVersionName = "2.0.0",
                kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
            )
        }

        val stagingDir = tempFolder.newFolder("full_staging")
        val staged = backupFile.inputStream().use { fis ->
            EncryptedBackupV2Engine.inspectAndStageBackup(fis, testPassword, stagingDir)
        }

        assertEquals(6, staged.payload.items.size)
        assertEquals(1, staged.payload.notesCount)
        assertEquals(1, staged.payload.credentialsCount)
        assertEquals(1, staged.payload.mediaCount)
        assertEquals(1, staged.payload.documentsCount)
        assertEquals(1, staged.payload.linksCount)
        assertEquals(1, staged.payload.items.count { it is VaultAlbumRecord })

        val restoredMediaFile = File(stagingDir, "media/media-full-3.pvmedia")
        val restoredThumbFile = File(stagingDir, "thumbs/media-full-3.pvthumb")
        val restoredDocFile = sequenceOf(
            File(stagingDir, "documents/doc-full-4.pvdoc"),
            File(stagingDir, "docs/doc-full-4.pvdoc")
        ).firstOrNull { it.exists() }

        assertTrue(restoredMediaFile.exists())
        assertEquals("ENCRYPTED_MEDIA_CONTENT", restoredMediaFile.readText())
        assertTrue(restoredThumbFile.exists())
        assertEquals("ENCRYPTED_THUMB_CONTENT", restoredThumbFile.readText())
        assertNotNull(restoredDocFile)
        assertTrue(restoredDocFile!!.exists())
        assertEquals("ENCRYPTED_DOC_CONTENT", restoredDocFile.readText())
    }
}
