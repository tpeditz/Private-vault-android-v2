package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.BiometricKeystoreManager
import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class V204HardeningRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun testDisableDecoyCleansSlot1FilesWhileProtectingSlot0Assets() = runBlocking {
        val filesDir = tempFolder.newFolder("files_decoy")
        val cacheDir = tempFolder.newFolder("cache_decoy")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val realPwd = "RealVault!Pass2026"
        val decoyPwd = "DecoyVault!Pass2026"
        val params = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        assertTrue(repository.createInitialVault(realPwd.toCharArray(), params) is AuthResult.Success)
        assertTrue(repository.provisionDecoyVault(realPwd.toCharArray(), decoyPwd.toCharArray()))

        // Create committed media files in both Slot 0 and Slot 1
        File(filesDir, "media").mkdirs()
        File(filesDir, "thumbs").mkdirs()
        File(filesDir, "documents").mkdirs()

        val realMedia = File(filesDir, "media/real-01.pvmedia").apply { writeBytes(ByteArray(32) { 1 }) }
        val realThumb = File(filesDir, "thumbs/real-01.pvthumb").apply { writeBytes(ByteArray(16) { 2 }) }
        val decoyMedia = File(filesDir, "media/decoy-01.pvmedia").apply { writeBytes(ByteArray(32) { 3 }) }
        val decoyThumb = File(filesDir, "thumbs/decoy-01.pvthumb").apply { writeBytes(ByteArray(16) { 4 }) }
        val decoyDoc = File(filesDir, "documents/decoy-doc.pvdoc").apply { writeBytes(ByteArray(24) { 5 }) }

        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = VaultSessionManager.REAL_SLOT,
                mediaId = "real-01",
                formatVersion = 1,
                relativeMediaPath = "media/real-01.pvmedia",
                relativeThumbPath = "thumbs/real-01.pvthumb",
                encryptedSizeBytes = 32L,
                chunkCount = 1,
                ciphertextSha256 = "a".repeat(64),
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1L
            )
        )
        dao.upsertMediaFileEntity(
            EncryptedMediaFileEntity(
                vaultSlot = VaultSessionManager.DECOY_SLOT,
                mediaId = "decoy-01",
                formatVersion = 1,
                relativeMediaPath = "media/decoy-01.pvmedia",
                relativeThumbPath = "thumbs/decoy-01.pvthumb",
                encryptedSizeBytes = 32L,
                chunkCount = 1,
                ciphertextSha256 = "b".repeat(64),
                importStatus = EncryptedMediaFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1L
            )
        )
        dao.upsertDocumentFileEntity(
            EncryptedDocumentFileEntity(
                vaultSlot = VaultSessionManager.DECOY_SLOT,
                documentId = "decoy-doc",
                formatVersion = 1,
                relativeFilePath = "documents/decoy-doc.pvdoc",
                encryptedSizeBytes = 24L,
                chunkCount = 1,
                ciphertextSha256 = "c".repeat(64),
                importStatus = EncryptedDocumentFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1L
            )
        )

        assertTrue(repository.disableDecoyVault(realPwd.toCharArray()))

        assertTrue("Slot 0 media must stay untouched", realMedia.exists())
        assertTrue("Slot 0 thumb must stay untouched", realThumb.exists())
        assertFalse("Orphaned Slot 1 media must be removed", decoyMedia.exists())
        assertFalse("Orphaned Slot 1 thumb must be removed", decoyThumb.exists())
        assertFalse("Orphaned Slot 1 document must be removed", decoyDoc.exists())
    }

    @Test
    fun testStreamedDocumentExportAndBoundedPreviewOnMultiChunkPdf() = runBlocking {
        val filesDir = tempFolder.newFolder("files_doc_stream")
        val cacheDir = tempFolder.newFolder("cache_doc_stream")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager().apply {
            openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x42 }, false)
        }
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        // > 2 chunks (2 * 256 KiB = 524,288 bytes)
        val rawDoc = ByteArray(600_000) { idx -> ('A'.code + (idx % 26)).toByte() }
        val docId = "multi-chunk-pdf-1"
        val docKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val relPath = "documents/$docId.pvdoc"
        val encFile = File(filesDir, relPath).apply { parentFile?.mkdirs() }

        val header = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(rawDoc),
            expectedPlaintextSize = rawDoc.size.toLong(),
            targetFile = encFile,
            mediaId = docId,
            vaultSlot = VaultSessionManager.REAL_SLOT,
            fileMediaKey = docKey
        )

        val docRecord = DocumentRecord(
            id = docId,
            title = "Large Statement",
            originalFileName = "statement.pdf",
            mimeType = "application/pdf",
            originalSizeBytes = rawDoc.size.toLong(),
            encryptedSizeBytes = encFile.length(),
            chunkCount = header.totalChunks,
            formatVersion = header.formatVersion,
            fileKeyBase64 = Base64.toBase64String(docKey),
            ciphertextSha256Hex = sha256Hex(encFile.readBytes())
        )
        dao.upsertDocumentFileEntity(
            EncryptedDocumentFileEntity(
                vaultSlot = VaultSessionManager.REAL_SLOT,
                documentId = docId,
                formatVersion = header.formatVersion,
                relativeFilePath = relPath,
                encryptedSizeBytes = encFile.length(),
                chunkCount = header.totalChunks,
                ciphertextSha256 = docRecord.ciphertextSha256Hex,
                importStatus = EncryptedDocumentFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
        repository.saveItem(docRecord)

        // Bounded preview must return exact cap bytes without reading whole file
        val preview = repository.loadDecryptedDocumentPreviewBytes(docRecord, maxPreviewBytes = 1024)
        assertEquals(1024, preview.size)
        assertArrayEquals(rawDoc.copyOfRange(0, 1024), preview)

        // Streaming export must reconstruct all 600,000 bytes accurately
        val outBaos = ByteArrayOutputStream()
        val exportedBytes = repository.exportDecryptedDocumentToStream(docRecord, outBaos)
        assertEquals(rawDoc.size.toLong(), exportedBytes)
        assertArrayEquals(rawDoc, outBaos.toByteArray())
    }

    @Test
    fun testCsvImportPrioritizesLoginUriSeparatesDuplicatesAndEnforcesBounds() = runBlocking {
        val filesDir = tempFolder.newFolder("files_csv")
        val cacheDir = tempFolder.newFolder("cache_csv")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager().apply {
            openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x31 }, false)
        }
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val csv = "\uFEFFname,login_uri,login_username,login_password,notes,grouping\n" +
            "Example Bank,https://bank.example.test/login,alice@example.test,P@ssword#1234,\"line1\nline2\",Finance\n" +
            "Example Bank,https://bank.example.test/login,alice@example.test,P@ssword#1234,\"line1\nline2\",Finance\n" +
            "Email,https://mail.example.test,bob@example.test,MailPass!9876,Work inbox,Personal\n" +
            ",,,\n"

        val (imported, skipped) = repository.importCredentialsFromCsv(
            ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8))
        )
        assertEquals(2, imported)
        assertEquals(1, skipped) // Duplicate skipped (fully blank row ignored cleanly)

        val saved = repository.getAllItemsForActiveSlot().filterIsInstance<CredentialRecord>()
        assertEquals(2, saved.size)
        val bank = saved.first { it.serviceName == "Example Bank" }
        assertEquals("https://bank.example.test/login", bank.websiteUrl)
        assertEquals("alice@example.test", bank.username)
        assertEquals("P@ssword#1234", bank.password)
        assertEquals("line1\nline2", bank.notes)
        assertEquals("Finance", bank.folderName)

        // Reject oversized CSV payload safely without saving partial garbage
        val hugePayload = ("name,username,password\n" + "a".repeat(5 * 1024 * 1024 + 64))
            .toByteArray(StandardCharsets.UTF_8)
        try {
            repository.importCredentialsFromCsv(ByteArrayInputStream(hugePayload))
            fail("Expected oversized CSV to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("exceeds", ignoreCase = true))
        }
    }

    @Test
    fun testBiometricStagedAliasAlternatesWithoutClobberingActiveAlias() {
        val alt = BiometricKeystoreManager.selectStagingAlias(BiometricKeystoreManager.KEY_ALIAS_REAL_VAULT_BIO)
        assertEquals(BiometricKeystoreManager.KEY_ALIAS_REAL_VAULT_BIO_ALT, alt)

        val primary = BiometricKeystoreManager.selectStagingAlias(BiometricKeystoreManager.KEY_ALIAS_REAL_VAULT_BIO_ALT)
        assertEquals(BiometricKeystoreManager.KEY_ALIAS_REAL_VAULT_BIO, primary)
    }

    @Test
    fun testCsvImportPreservesExactCredentialsWithoutSilentTruncationAndRejectsOverLimits() = runBlocking {
        val filesDir = tempFolder.newFolder("files_csv_exact")
        val cacheDir = tempFolder.newFolder("cache_csv_exact")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager().apply {
            openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x35 }, false)
        }
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val longPass = "  P@ssword_With_Spaces_And_Quotes\"\"_" + "X".repeat(2500) + "  "
        val expectedPass = "  P@ssword_With_Spaces_And_Quotes\"_" + "X".repeat(2500) + "  "
        val longUrl = "https://auth.example.test/callback?" + "k=v&".repeat(600)
        val csv = "title,website,username,password,notes\n" +
            "Exact Unicode \uD83D\uDD10,$longUrl,\"  user@example.test  \",\"$longPass\",\"Line1\n  Line2\"\n"

        val (imported, skipped) = repository.importCredentialsFromCsv(
            ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8))
        )
        assertEquals(1, imported)
        assertEquals(0, skipped)

        val saved = repository.getAllItemsForActiveSlot().filterIsInstance<CredentialRecord>().single()
        assertEquals("Exact Unicode \uD83D\uDD10", saved.serviceName)
        assertEquals(longUrl, saved.websiteUrl)
        assertEquals("  user@example.test  ", saved.username)
        assertEquals(expectedPass, saved.password)
        assertEquals("Line1\n  Line2", saved.notes)

        // Over-limit field (> 16,384 chars) must fail clearly rather than truncating silently
        val overFieldCsv = "title,username,password\nTooLong,u,${"p".repeat(16_500)}\n"
        try {
            repository.importCredentialsFromCsv(ByteArrayInputStream(overFieldCsv.toByteArray(StandardCharsets.UTF_8)))
            fail("Expected oversized CSV field to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("exceeds", ignoreCase = true))
        }

        // Over-limit row count (> 10,000 rows) must fail clearly and leave DB unchanged (still only 1 saved item)
        val sb = StringBuilder("title,username,password\n")
        for (i in 1..10_002) {
            sb.append("Site$i,user$i,pass$i\n")
        }
        try {
            repository.importCredentialsFromCsv(ByteArrayInputStream(sb.toString().toByteArray(StandardCharsets.UTF_8)))
            fail("Expected CSV exceeding max row limit to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("exceeds", ignoreCase = true))
        }
        assertEquals(1, repository.getAllItemsForActiveSlot().filterIsInstance<CredentialRecord>().size)
    }

    @Test
    fun testCsvImportIsAtomicAndBoundToInitialVaultSession() = runBlocking {
        val filesDir = tempFolder.newFolder("files_csv_atomic")
        val cacheDir = tempFolder.newFolder("cache_csv_atomic")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val realDek = ByteArray(32) { 0x41 }
        val decoyDek = ByteArray(32) { 0x42 }
        val realSession = sessionManager.openSession(VaultSessionManager.REAL_SLOT, realDek, true)
        repository.saveItem(CredentialRecord(serviceName = "Existing", username = "orig", password = "orig-password"), realSession.sessionId)

        // Switch session to Decoy midway through reading the stream -> zero new CSV rows committed to either slot!
        val csv = "title,username,password\n" +
            "Row1,u1,Pass1!234567\n" +
            "Row2,u2,Pass2!234567\n"
        val switchingStream = object : ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)) {
            var reads = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                reads++
                if (reads == 1) {
                    sessionManager.openSession(VaultSessionManager.DECOY_SLOT, decoyDek, false)
                }
                return n
            }
        }
        try {
            repository.importCredentialsFromCsv(switchingStream, expectedSessionId = realSession.sessionId)
            fail("Expected session switch during CSV import to fail atomically")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("session", ignoreCase = true))
        }

        // Neither Decoy nor Real should contain partial CSV rows
        assertEquals(0, dao.getRecordsForSlot(VaultSessionManager.DECOY_SLOT).size)
        val restoredReal = sessionManager.openSession(VaultSessionManager.REAL_SLOT, realDek, true)
        val realCreds = repository.getAllItemsForActiveSlot(restoredReal.sessionId).filterIsInstance<CredentialRecord>()
        assertEquals(1, realCreds.size)
        assertEquals("Existing", realCreds.single().serviceName)
    }

    @Test
    fun testBiometricAliasReconciliationRecoversBothPreAndPostDbInterruption(): Unit = runBlocking {
        val oldAlias = BiometricKeystoreManager.KEY_ALIAS_REAL_VAULT_BIO
        val newAlias = BiometricKeystoreManager.KEY_ALIAS_REAL_VAULT_BIO_ALT
        val oldNonceSha = BiometricKeystoreManager.nonceSha256Hex(byteArrayOf(1, 2, 3))
        val newNonceSha = BiometricKeystoreManager.nonceSha256Hex(byteArrayOf(9, 8, 7))
        val presentKeys = setOf(oldAlias, newAlias)

        // Case 1: Crashed BEFORE SQLite commit -> DB still holds oldNonceSha -> keep oldAlias & retire unused staged newAlias
        val preCommit = BiometricKeystoreManager.reconcileAliasState(
            activeAlias = oldAlias,
            pendingAlias = newAlias,
            pendingNonceSha256 = newNonceSha,
            dbBiometricNonceSha256 = oldNonceSha,
            hasKeyInKeystore = { it in presentKeys }
        )
        assertEquals(oldAlias, preCommit.unlockAlias)
        assertFalse(preCommit.shouldPromotePendingAlias)
        assertEquals(newAlias, preCommit.staleAliasToRetire)
        assertTrue(preCommit.shouldClearPending)

        // Case 2: Crashed AFTER SQLite commit, BEFORE pref update -> DB holds newNonceSha -> promote newAlias & retire oldAlias
        val postCommit = BiometricKeystoreManager.reconcileAliasState(
            activeAlias = oldAlias,
            pendingAlias = newAlias,
            pendingNonceSha256 = newNonceSha,
            dbBiometricNonceSha256 = newNonceSha,
            hasKeyInKeystore = { it in presentKeys }
        )
        assertEquals(newAlias, postCommit.unlockAlias)
        assertTrue(postCommit.shouldPromotePendingAlias)
        assertEquals(oldAlias, postCommit.staleAliasToRetire)
        assertTrue(postCommit.shouldClearPending)

        // Case 3: DB biometric wrapper cleared -> resolve null and clear pending
        val cleared = BiometricKeystoreManager.reconcileAliasState(
            activeAlias = oldAlias,
            pendingAlias = newAlias,
            pendingNonceSha256 = newNonceSha,
            dbBiometricNonceSha256 = null,
            hasKeyInKeystore = { it in presentKeys }
        )
        assertEquals(null, cleared.unlockAlias)
        assertTrue(cleared.shouldClearPending)

        // Also verify changeMasterPassword clears DB biometric wrapper
        val filesDir = tempFolder.newFolder("files_bio_clear")
        val cacheDir = tempFolder.newFolder("cache_bio_clear")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)
        val params = CryptoEngine.KdfParams(memoryKb = 4096, iterations = 1, parallelism = 1)
        assertTrue(repository.createInitialVault("InitialPass!2026".toCharArray(), params) is AuthResult.Success)
        val existingEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT)!!
        dao.upsertEnvelope(existingEnv.copy(biometricNonce = byteArrayOf(1, 2, 3), biometricWrappedDek = byteArrayOf(4, 5, 6)))
        assertTrue(repository.changeMasterPassword("InitialPass!2026".toCharArray(), "RotatedPass!2026".toCharArray()))
        val afterEnv = dao.getEnvelopeForSlot(VaultSessionManager.REAL_SLOT)!!
        assertEquals(null, afterEnv.biometricNonce)
        assertEquals(null, afterEnv.biometricWrappedDek)
    }

    @Test
    fun testStreamingDocumentExportAbortsCleanlyOnCancellationOrSessionChange() = runBlocking {
        val filesDir = tempFolder.newFolder("files_doc_cancel")
        val cacheDir = tempFolder.newFolder("cache_doc_cancel")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager()
        val session = sessionManager.openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x55 }, false)
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val rawDoc = ByteArray(700_000) { idx -> (idx % 127).toByte() } // 3 chunks (256 KiB each + tail)
        val docId = "cancel-doc-1"
        val docKey = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val relPath = "documents/$docId.pvdoc"
        val encFile = File(filesDir, relPath).apply { parentFile?.mkdirs() }
        val header = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(rawDoc),
            expectedPlaintextSize = rawDoc.size.toLong(),
            targetFile = encFile,
            mediaId = docId,
            vaultSlot = VaultSessionManager.REAL_SLOT,
            fileMediaKey = docKey
        )
        val docRecord = DocumentRecord(
            id = docId,
            title = "Cancel Test",
            originalFileName = "large.bin",
            mimeType = "application/octet-stream",
            originalSizeBytes = rawDoc.size.toLong(),
            encryptedSizeBytes = encFile.length(),
            chunkCount = header.totalChunks,
            formatVersion = header.formatVersion,
            fileKeyBase64 = Base64.toBase64String(docKey),
            ciphertextSha256Hex = sha256Hex(encFile.readBytes())
        )
        dao.upsertDocumentFileEntity(
            EncryptedDocumentFileEntity(
                vaultSlot = VaultSessionManager.REAL_SLOT,
                documentId = docId,
                formatVersion = header.formatVersion,
                relativeFilePath = relPath,
                encryptedSizeBytes = encFile.length(),
                chunkCount = header.totalChunks,
                ciphertextSha256 = docRecord.ciphertextSha256Hex,
                importStatus = EncryptedDocumentFileEntity.IMPORT_STATUS_COMMITTED,
                createdAtEpochMs = 1L
            )
        )

        var writes = 0
        val outBaos = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                writes++
                if (writes == 1) {
                    sessionManager.lockNow()
                }
            }
        }
        try {
            repository.exportDecryptedDocumentToStream(
                documentRecord = docRecord,
                outputStream = outBaos,
                expectedSessionId = session.sessionId
            )
            fail("Expected document export to abort when vault locks mid-stream")
        } catch (_: java.util.concurrent.CancellationException) {
            assertTrue("Partial export should not match full document size", outBaos.size().toLong() < rawDoc.size.toLong())
        }
    }
}
