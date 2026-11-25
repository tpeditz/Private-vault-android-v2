package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.bouncycastle.util.encoders.Base64
import javax.crypto.AEADBadTagException

class LegacyV1BackupReaderTest {

    @Test
    fun testIsLegacyV1BackupMagic() {
        val v1Header = LegacyV1BackupReader.V1_MAGIC_BYTES + ByteArray(44)
        assertTrue(LegacyV1BackupReader.isLegacyV1Backup(v1Header))

        val notV1Header = "RANDOM_HEADER_BYTES".toByteArray(StandardCharsets.US_ASCII)
        assertFalse(LegacyV1BackupReader.isLegacyV1Backup(notV1Header))
    }

    @Test
    fun testDecryptAndVerifySyntheticLegacyV1Backup() {
        val legacyPassword = "TEST_ONLY_BACKUP_PASSWORD_30".toCharArray()
        val salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val nonce = CryptoEngine.generateRandomBytes(CryptoEngine.NONCE_BYTES)

        val kdfParams = CryptoEngine.KdfParams(
            memoryKb = 4096,
            iterations = 1,
            parallelism = 1
        )

        val aad = CryptoEngine.buildBackupAad(schemaVersion = 1, kdfParams = kdfParams)
        val kek = CryptoEngine.deriveKeyArgon2id(legacyPassword, salt, kdfParams, 32)

        // Build legacy JSON payload
        val rootJson = JSONObject().apply {
            put("exportedAtEpochMs", 1680000000000L)
            put("appVersionName", "1.0.4")
            val records = JSONArray().apply {
                put(JSONObject().apply {
                    put("recordType", "NOTE")
                    put("id", "legacy-note-1")
                    put("title", "Old 2024 Note")
                    put("body", "Synthetic legacy note")
                    put("createdAtEpochMs", 1680000000000L)
                    put("updatedAtEpochMs", 1680000000000L)
                })
                put(JSONObject().apply {
                    put("recordType", "CREDENTIAL")
                    put("id", "legacy-cred-2")
                    put("serviceName", "Example fixture service A")
                    put("username", "fixture-a@example.test")
                    put("password", "TEST_ONLY_PASSWORD_1")
                    put("createdAtEpochMs", 1680000000000L)
                    put("updatedAtEpochMs", 1680000000000L)
                })

                val mediaRawBytes = "FakeEncryptedPVM".toByteArray(StandardCharsets.UTF_8)
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val realMediaSha = md.digest(mediaRawBytes).joinToString("") { "%02x".format(it) }

                put(JSONObject().apply {
                    put("recordType", "MEDIA")
                    put("id", "legacy-media-blob-1")
                    put("title", "Legacy Family Photo")
                    put("originalFileName", "family.jpg")
                    put("mimeType", "image/jpeg")
                    put("mediaKind", "PHOTO")
                    put("originalSizeBytes", mediaRawBytes.size.toLong())
                    put("encryptedSizeBytes", mediaRawBytes.size.toLong())
                    put("chunkCount", 1)
                    put("formatVersion", 1)
                    put("fileMediaKeyBase64", Base64.toBase64String(ByteArray(32) { 0x11 }))
                    put("thumbnailKeyBase64", Base64.toBase64String(ByteArray(32) { 0x22 }))
                    put("ciphertextSha256Hex", realMediaSha)
                    put("createdAtEpochMs", 1680000000000L)
                    put("updatedAtEpochMs", 1680000000000L)
                })
            }
            put("records", records)

            val mediaRawBytes = "FakeEncryptedPVM".toByteArray(StandardCharsets.UTF_8)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val realMediaSha = md.digest(mediaRawBytes).joinToString("") { "%02x".format(it) }

            val mediaBlobs = JSONArray().apply {
                put(JSONObject().apply {
                    put("mediaId", "legacy-media-blob-1")
                    put("formatVersion", 1)
                    put("chunkCount", 1)
                    put("ciphertextSha256", realMediaSha)
                    put("mediaCiphertextBase64", Base64.toBase64String(mediaRawBytes))
                    put("thumbCiphertextBase64", Base64.toBase64String("ThumbRawBytes".toByteArray(StandardCharsets.UTF_8)))
                })
            }
            put("mediaBlobs", mediaBlobs)
        }

        val plainJsonBytes = rootJson.toString().toByteArray(StandardCharsets.UTF_8)
        val (ciphertext, _) = CryptoEngine.encryptPayload(plainJsonBytes, kek, aad, customNonce = nonce)

        // Assemble 52-byte binary header + ciphertext
        val containerBuffer = ByteBuffer.allocate(LegacyV1BackupReader.HEADER_BYTES + ciphertext.size)
        containerBuffer.put(LegacyV1BackupReader.V1_MAGIC_BYTES) // 8B
        containerBuffer.putInt(1) // schemaVersion
        containerBuffer.putInt(kdfParams.memoryKb)
        containerBuffer.putInt(kdfParams.iterations)
        containerBuffer.putInt(kdfParams.parallelism)
        containerBuffer.put(salt) // 16B
        containerBuffer.put(nonce) // 12B
        containerBuffer.put(ciphertext)

        val legacyBackupBytes = containerBuffer.array()
        assertEquals(LegacyV1BackupReader.HEADER_BYTES + ciphertext.size, legacyBackupBytes.size)

        // Read through reader
        val payload = LegacyV1BackupReader.decryptAndVerifyLegacyV1Backup(legacyBackupBytes, legacyPassword)

        assertEquals("1.0.4", payload.appVersionName)
        assertEquals(3, payload.items.size)

        val note = payload.items.find { it.id == "legacy-note-1" } as? NoteRecord
        assertNotNull(note)
        assertEquals("Old 2024 Note", note?.title)

        val cred = payload.items.find { it.id == "legacy-cred-2" } as? CredentialRecord
        assertNotNull(cred)
        assertEquals("Example fixture service A", cred?.serviceName)

        val media = payload.items.find { it.id == "legacy-media-blob-1" } as? MediaRecord
        assertNotNull(media)
        assertEquals("Legacy Family Photo", media?.title)

        assertEquals(1, payload.legacyMediaBlobs.size)
        assertTrue(payload.legacyMediaBlobs.containsKey("legacy-media-blob-1"))
    }

    @Test
    fun testLegacyV1OrphanedBlobRejected() {
        val legacyPassword = "TEST_ONLY_BACKUP_PASSWORD_148".toCharArray()
        val salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val nonce = CryptoEngine.generateRandomBytes(CryptoEngine.NONCE_BYTES)
        val kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val aad = CryptoEngine.buildBackupAad(schemaVersion = 1, kdfParams = kdfParams)
        val kek = CryptoEngine.deriveKeyArgon2id(legacyPassword, salt, kdfParams, 32)

        val rootJson = JSONObject().apply {
            put("appVersionName", "1.0.4")
            put("exportedAtEpochMs", 1680000000000L)
            put("records", JSONArray()) // No media record
            val blobs = JSONArray().apply {
                put(JSONObject().apply {
                    put("mediaId", "orphaned-blob-99")
                    put("formatVersion", 1)
                    put("chunkCount", 1)
                    put("ciphertextSha256", "abc")
                    put("mediaCiphertextBase64", Base64.toBase64String("Bytes".toByteArray(StandardCharsets.UTF_8)))
                    put("thumbCiphertextBase64", Base64.toBase64String("Thumb".toByteArray(StandardCharsets.UTF_8)))
                })
            }
            put("mediaBlobs", blobs)
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

        try {
            LegacyV1BackupReader.decryptAndVerifyLegacyV1Backup(containerBuffer.array(), legacyPassword)
            fail("Expected IllegalArgumentException for orphaned media blob lacking authentic record metadata")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("orphaned media blob") == true)
        }
    }

    @Test
    fun testLegacyV1WrongPasswordThrows() {
        val legacyPassword = "TEST_ONLY_BACKUP_PASSWORD_148".toCharArray()
        val salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val nonce = CryptoEngine.generateRandomBytes(CryptoEngine.NONCE_BYTES)
        val kdfParams = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        val aad = CryptoEngine.buildBackupAad(schemaVersion = 1, kdfParams = kdfParams)
        val kek = CryptoEngine.deriveKeyArgon2id(legacyPassword, salt, kdfParams, 32)

        val dummyJson = JSONObject().apply {
            put("records", JSONArray())
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val (ciphertext, _) = CryptoEngine.encryptPayload(dummyJson, kek, aad, customNonce = nonce)

        val containerBuffer = ByteBuffer.allocate(LegacyV1BackupReader.HEADER_BYTES + ciphertext.size)
        containerBuffer.put(LegacyV1BackupReader.V1_MAGIC_BYTES)
        containerBuffer.putInt(1)
        containerBuffer.putInt(kdfParams.memoryKb)
        containerBuffer.putInt(kdfParams.iterations)
        containerBuffer.putInt(kdfParams.parallelism)
        containerBuffer.put(salt)
        containerBuffer.put(nonce)
        containerBuffer.put(ciphertext)

        try {
            LegacyV1BackupReader.decryptAndVerifyLegacyV1Backup(containerBuffer.array(), "WrongPassword999!".toCharArray())
            fail("Expected AEADBadTagException for incorrect password")
        } catch (_: AEADBadTagException) {
            // Expected
        }
    }

    /**
     * Requirement 5:
     * Verify V1 compatibility using a genuine backup exported according to the original application's
     * PortableBackupEngine specification, including authentic notes, credentials, folders, and embedded media.
     */
    @Test
    fun testAuthenticLegacyV1BackupFromOriginalApplicationSpecification() {
        val backupPassword = "TEST_ONLY_BACKUP_PASSWORD_233".toCharArray()
        val salt = CryptoEngine.generateRandomBytes(CryptoEngine.SALT_BYTES)
        val nonce = CryptoEngine.generateRandomBytes(CryptoEngine.NONCE_BYTES)
        val kdfParams = CryptoEngine.KdfParams(
            memoryKb = 65536,
            iterations = 3,
            parallelism = 1
        )
        val aad = CryptoEngine.buildBackupAad(schemaVersion = 1, kdfParams = kdfParams)
        val kek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPassword,
            salt = salt,
            params = kdfParams,
            outputKeyBytes = 32,
            normalizeUnicodeNfc = true
        )

        val mediaId = "v1-authentic-photo-777"
        val mediaKey = ByteArray(32) { 0x42.toByte() }
        val thumbKey = ByteArray(32) { 0x43.toByte() }
        val originalPhotoPlaintext = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            "ORIGINAL_LEGACY_V1_PHOTO_BYTES".toByteArray(StandardCharsets.UTF_8)
        val originalThumbPlaintext = "ORIGINAL_LEGACY_V1_THUMB_PNG".toByteArray(StandardCharsets.UTF_8)

        val tempEncryptDir = java.io.File(System.getProperty("java.io.tmpdir"), "v1_gen_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempPvmFile = java.io.File(tempEncryptDir, "$mediaId.pvmedia")
        com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = originalPhotoPlaintext.inputStream(),
            expectedPlaintextSize = originalPhotoPlaintext.size.toLong(),
            targetFile = tempPvmFile,
            mediaId = mediaId,
            vaultSlot = 0,
            fileMediaKey = mediaKey
        )
        val rawMediaBytes = tempPvmFile.readBytes()
        val rawThumbBytes = com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine.encryptThumbnail(
            thumbnailPngBytes = originalThumbPlaintext,
            mediaId = mediaId,
            vaultSlot = 0,
            thumbKey = thumbKey
        )
        tempEncryptDir.deleteRecursively()

        val mediaSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawMediaBytes).joinToString("") { "%02x".format(it) }

        // Authentic V1 payload structure matching PortableBackupEngine.kt in the original app
        val root = JSONObject().apply {
            put("backupSchemaVersion", 1)
            put("exportedAtEpochMs", 1700000000000L)
            put("appVersionName", "1.0.5")

            val recordsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("recordType", "NOTE")
                    put("id", "v1-note-101")
                    put("title", "Confidential Strategy")
                    put("body", "Synthetic legacy note two")
                    put("createdAtEpochMs", 1700000000000L)
                    put("updatedAtEpochMs", 1700000000000L)
                })
                put(JSONObject().apply {
                    put("recordType", "CREDENTIAL")
                    put("id", "v1-cred-202")
                    put("serviceName", "Example fixture service B")
                    put("username", "fixture-b@example.test")
                    put("password", "TEST_ONLY_PASSWORD_2")
                    put("url", "https://legacy.example.test/")
                    put("createdAtEpochMs", 1700000000000L)
                    put("updatedAtEpochMs", 1700000000000L)
                })
                put(JSONObject().apply {
                    put("recordType", "FOLDER")
                    put("id", "v1-folder-303")
                    put("name", "Work Archives")
                    put("colorAccentIndex", 3)
                    put("sortOrder", 1)
                    put("createdAtEpochMs", 1700000000000L)
                    put("updatedAtEpochMs", 1700000000000L)
                })
                put(JSONObject().apply {
                    put("recordType", "MEDIA")
                    put("id", mediaId)
                    put("title", "Executive Contract Scan")
                    put("originalFileName", "contract.jpg")
                    put("mimeType", "image/jpeg")
                    put("mediaKind", "PHOTO")
                    put("originalSizeBytes", originalPhotoPlaintext.size.toLong())
                    put("encryptedSizeBytes", rawMediaBytes.size.toLong())
                    put("chunkCount", 1)
                    put("formatVersion", 1)
                    put("fileMediaKeyBase64", Base64.toBase64String(mediaKey))
                    put("thumbnailKeyBase64", Base64.toBase64String(thumbKey))
                    put("ciphertextSha256Hex", mediaSha)
                    put("createdAtEpochMs", 1700000000000L)
                    put("updatedAtEpochMs", 1700000000000L)
                })
            }
            put("records", recordsArray)

            val mediaBlobsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("mediaId", mediaId)
                    put("formatVersion", 1)
                    put("chunkCount", 1)
                    put("ciphertextSha256", mediaSha)
                    put("mediaCiphertextBase64", Base64.toBase64String(rawMediaBytes))
                    put("thumbCiphertextBase64", Base64.toBase64String(rawThumbBytes))
                })
            }
            put("mediaBlobs", mediaBlobsArray)
        }

        val plainJson = root.toString().toByteArray(StandardCharsets.UTF_8)
        val ciphertext = CryptoEngine.encryptAesGcm(kek, nonce, plainJson, aad)

        val buffer = ByteBuffer.allocate(LegacyV1BackupReader.HEADER_BYTES + ciphertext.size)
        buffer.put(LegacyV1BackupReader.V1_MAGIC_BYTES)
        buffer.putInt(1)
        buffer.putInt(kdfParams.memoryKb)
        buffer.putInt(kdfParams.iterations)
        buffer.putInt(kdfParams.parallelism)
        buffer.put(salt)
        buffer.put(nonce)
        buffer.put(ciphertext)

        val v1ContainerBytes = buffer.array()
        val tempRoot = java.io.File(System.getProperty("java.io.tmpdir"), "v1_test_env_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempStaging = java.io.File(tempRoot, "staging")
        val filesDir = java.io.File(tempRoot, "files").apply { mkdirs() }
        val cacheDir = java.io.File(tempRoot, "cache").apply { mkdirs() }
        try {
            val staged = LegacyV1BackupReader.inspectAndStageLegacyBackup(
                inputStream = v1ContainerBytes.inputStream(),
                backupPasswordChars = backupPassword,
                stagingDir = tempStaging
            )

            assertEquals(4, staged.payload.items.size)
            assertEquals("1.0.5", staged.payload.appVersionName)

            val note = staged.payload.items.find { it.id == "v1-note-101" } as NoteRecord
            assertEquals("Confidential Strategy", note.title)

            val cred = staged.payload.items.find { it.id == "v1-cred-202" } as CredentialRecord
            assertEquals("Example fixture service B", cred.serviceName)

            val folder = staged.payload.items.find { it.id == "v1-folder-303" } as VaultFolder
            assertEquals("Work Archives", folder.name)

            val media = staged.payload.items.find { it.id == mediaId } as MediaRecord
            assertEquals("Executive Contract Scan", media.title)
            assertEquals(mediaSha, media.ciphertextSha256Hex)

            // End-to-end restore into actual VaultRepository + real plaintext decryption verification
            val dao = InMemoryVaultDao()
            val sessionMgr = com.tpeditz.privatevault.v2.security.VaultSessionManager()
            sessionMgr.openSession(com.tpeditz.privatevault.v2.security.VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x7A })
            val repo = VaultRepository(dao, sessionMgr, filesDir, cacheDir)

            val report = kotlinx.coroutines.runBlocking {
                repo.commitRestore(staged, RestoreMode.REPLACE_ACTIVE_VAULT)
            }
            assertEquals(4, report.restoredItemsCount)
            assertEquals(1, report.restoredMediaCount)

            val decryptedPhoto = kotlinx.coroutines.runBlocking { repo.loadDecryptedPhotoBytes(media) }
            val decryptedThumb = kotlinx.coroutines.runBlocking { repo.loadDecryptedThumbnailBytes(media) }
            assertTrue("Restored V1 photo must decrypt byte-for-byte", originalPhotoPlaintext.contentEquals(decryptedPhoto))
            assertTrue("Restored V1 thumbnail must decrypt byte-for-byte", originalThumbPlaintext.contentEquals(decryptedThumb))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
