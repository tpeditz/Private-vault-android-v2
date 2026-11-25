package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.VaultSessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultRepositorySafetyHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRecoveryAbortsCleanlyWhenDatabaseQueryFailsWithoutDeletingMedia() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val mediaDir = File(filesDir, "media").apply { mkdirs() }
        val committedMedia = File(mediaDir, "keep-photo.pvmedia").apply { writeBytes(ByteArray(128) { 0x2A }) }

        // Prepare a journal pointing to committedMedia as pending old cleanup
        File(cacheDir, "vault_restore_journal.json").writeText(
            """{"pendingOldFiles":["${committedMedia.absolutePath.replace("\\", "\\\\")}"]}"""
        )

        val failingDao = object : VaultDao by InMemoryVaultDao() {
            override suspend fun getRecordsForSlot(vaultSlot: Int): List<EncryptedRecordEntity> {
                throw IllegalStateException("Simulated SQLite read failure")
            }
        }
        val sessionManager = VaultSessionManager().apply {
            openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x11 }, false)
        }
        val repository = VaultRepository(failingDao, sessionManager, filesDir, cacheDir)

        val recovered = repository.recoverIncompleteRestoreIfPresent()
        assertFalse("Recovery must abort returning false when DB state cannot be verified", recovered)
        assertTrue("Valid existing media must NOT be deleted when DB query fails", committedMedia.exists())
        assertTrue(
            "Recovery journal must remain intact for retry when recovery aborts",
            File(cacheDir, "vault_restore_journal.json").exists()
        )
    }

    @Test
    fun testVaultMutationMutexSerializesConcurrentItemSaves() = runBlocking {
        val filesDir = tempFolder.newFolder("files_concurrent")
        val cacheDir = tempFolder.newFolder("cache_concurrent")
        val dao = InMemoryVaultDao()
        val sessionManager = VaultSessionManager().apply {
            openSession(VaultSessionManager.REAL_SLOT, ByteArray(32) { 0x55 }, false)
        }
        val repository = VaultRepository(dao, sessionManager, filesDir, cacheDir)

        val jobs = (1..25).map { idx ->
            async {
                repository.saveItem(
                    NoteRecord(
                        id = "note-$idx",
                        title = "Title $idx",
                        body = "Body $idx"
                    )
                )
            }
        }
        jobs.awaitAll()

        val items = repository.getAllItemsForActiveSlot().filterIsInstance<NoteRecord>()
        assertEquals(25, items.size)
    }
}
