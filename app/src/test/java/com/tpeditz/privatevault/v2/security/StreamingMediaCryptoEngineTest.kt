package com.tpeditz.privatevault.v2.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class StreamingMediaCryptoEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val random = SecureRandom()

    @Test
    fun testSmallPayloadRoundtrip() {
        val key = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val originalData = "Small test plaintext payload for media chunking".toByteArray(StandardCharsets.UTF_8)
        val targetFile = tempFolder.newFile("media_small.pvm")

        val header = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(originalData),
            expectedPlaintextSize = originalData.size.toLong(),
            targetFile = targetFile,
            mediaId = "media-small-1",
            vaultSlot = 0,
            fileMediaKey = key
        )

        assertEquals(1, header.totalChunks)
        assertEquals(originalData.size.toLong(), header.totalPlaintextBytes)

        val report = StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
            encryptedFile = targetFile,
            mediaId = "media-small-1",
            vaultSlot = 0,
            fileMediaKey = key
        )
        assertEquals(1, report.header.totalChunks)
        assertTrue(report.ciphertextSha256Hex.isNotBlank())

        val decrypted = StreamingMediaCryptoEngine.decryptEntireMediaFile(
            encryptedFile = targetFile,
            mediaId = "media-small-1",
            vaultSlot = 0,
            fileMediaKey = key
        )

        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun testMultiChunkPayloadRoundtrip() {
        val key = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        // 600 KiB: spans 2 full chunks (256 KiB each) and 1 partial chunk (88 KiB)
        val totalBytes = 600 * 1024
        val originalData = ByteArray(totalBytes)
        random.nextBytes(originalData)

        val targetFile = tempFolder.newFile("media_multi_600k.pvm")
        val header = StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(originalData),
            expectedPlaintextSize = totalBytes.toLong(),
            targetFile = targetFile,
            mediaId = "media-multi-600k",
            vaultSlot = 0,
            fileMediaKey = key
        )

        assertEquals(3, header.totalChunks)
        assertEquals(totalBytes.toLong(), header.totalPlaintextBytes)

        val report = StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
            encryptedFile = targetFile,
            mediaId = "media-multi-600k",
            vaultSlot = 0,
            fileMediaKey = key
        )
        assertEquals(3, report.header.totalChunks)

        val decrypted = StreamingMediaCryptoEngine.decryptEntireMediaFile(
            encryptedFile = targetFile,
            mediaId = "media-multi-600k",
            vaultSlot = 0,
            fileMediaKey = key
        )

        assertEquals(totalBytes, decrypted.size)
        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun testTamperedChunkThrowsAeadBadTagException() {
        val key = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val originalData = "Crucial confidential document stream".toByteArray(StandardCharsets.UTF_8)
        val targetFile = tempFolder.newFile("media_tamper.pvm")

        StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(originalData),
            expectedPlaintextSize = originalData.size.toLong(),
            targetFile = targetFile,
            mediaId = "media-tamper-test",
            vaultSlot = 0,
            fileMediaKey = key
        )

        // Tamper with a payload byte in chunk 0 (after 32-byte header + 16-byte chunk frame prefix)
        val raf = RandomAccessFile(targetFile, "rw")
        val tamperOffset = (StreamingMediaCryptoEngine.HEADER_BYTES + 20).toLong()
        raf.seek(tamperOffset)
        val originalByte = raf.readByte()
        raf.seek(tamperOffset)
        raf.writeByte(originalByte.toInt() xor 0xFF)
        raf.close()

        try {
            StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                encryptedFile = targetFile,
                mediaId = "media-tamper-test",
                vaultSlot = 0,
                fileMediaKey = key
            )
            fail("Expected AEADBadTagException or GeneralSecurityException for corrupted chunk payload")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun testThumbnailEncryptionRoundtrip() {
        val key = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val rawThumb = ByteArray(1024)
        random.nextBytes(rawThumb)

        val encryptedThumb = StreamingMediaCryptoEngine.encryptThumbnail(
            thumbnailPngBytes = rawThumb,
            mediaId = "media-thumb-1",
            vaultSlot = 0,
            thumbKey = key
        )
        assertFalse(rawThumb.contentEquals(encryptedThumb))

        val decryptedThumb = StreamingMediaCryptoEngine.decryptThumbnail(
            encryptedThumbBytes = encryptedThumb,
            mediaId = "media-thumb-1",
            vaultSlot = 0,
            thumbKey = key
        )
        assertArrayEquals(rawThumb, decryptedThumb)
    }

    @Test
    fun testEncryptedChunkMediaDataSourceReadAt() {
        val key = CryptoEngine.randomBytes(CryptoEngine.DEK_BYTES)
        val totalBytes = 300 * 1024 // 300 KiB: 2 chunks
        val originalData = ByteArray(totalBytes)
        random.nextBytes(originalData)

        val targetFile = tempFolder.newFile("media_datasource.pvm")
        StreamingMediaCryptoEngine.encryptStreamToMediaFile(
            inputStream = ByteArrayInputStream(originalData),
            expectedPlaintextSize = totalBytes.toLong(),
            targetFile = targetFile,
            mediaId = "media-ds-1",
            vaultSlot = 0,
            fileMediaKey = key
        )

        val dataSource = StreamingMediaCryptoEngine.EncryptedChunkMediaDataSource(
            mediaFile = targetFile,
            mediaId = "media-ds-1",
            vaultSlot = 0,
            fileMediaKey = key
        )

        assertEquals(totalBytes.toLong(), dataSource.size)

        // Read first 100 bytes
        val buf1 = ByteArray(100)
        val read1 = dataSource.readAt(0, buf1, 0, 100)
        assertEquals(100, read1)
        val expected1 = originalData.copyOfRange(0, 100)
        assertArrayEquals(expected1, buf1)

        // Seek across chunk boundary (256 KiB = 262144)
        val boundaryOffset = (256 * 1024 - 50).toLong()
        val buf2 = ByteArray(100)
        val read2 = dataSource.readAt(boundaryOffset, buf2, 0, 100)
        assertEquals(100, read2)
        val expected2 = originalData.copyOfRange(boundaryOffset.toInt(), (boundaryOffset + 100).toInt())
        assertArrayEquals(expected2, buf2)

        dataSource.close()
    }
}
