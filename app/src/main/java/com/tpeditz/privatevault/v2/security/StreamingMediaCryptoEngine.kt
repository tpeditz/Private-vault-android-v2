package com.tpeditz.privatevault.v2.security

import android.media.MediaDataSource
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.AEADBadTagException

/**
 * Streaming chunked authenticated encryption engine (`PVM1`) and zero-disk-plaintext
 * `MediaDataSource` for PRIVATE VAULT V2 (Photos, Videos & Documents).
 *
 * Binary Format (`PVM1`):
 * - Header (32 bytes):
 *   - `[0..3]`   4B Magic `"PVM1"`
 *   - `[4..5]`   2B Format Version (`1`)
 *   - `[6..7]`   2B Flags (`0`)
 *   - `[8..11]`  4B Nominal Plaintext Chunk Size (`262144` = 256 KiB)
 *   - `[12..15]` 4B Total Chunk Count `N` (`>= 1`)
 *   - `[16..23]` 8B Total Original Plaintext Bytes (`>= 1`)
 *   - `[24..31]` 8B Reserved (`0L`)
 * - Chunk Frame `i` (`0 <= i < N`):
 *   - `4B` Plaintext Chunk Length `L` (`1 <= L <= 262144`; for `0 <= i < N - 1`, `L == 262144`)
 *   - `12B` Chunk Nonce (CSPRNG 96-bit IV)
 *   - `L + 16B` AES-256-GCM Ciphertext + 128-bit Authentication Tag
 */
object StreamingMediaCryptoEngine {

    private val PVM1_MAGIC = "PVM1".toByteArray(StandardCharsets.US_ASCII)
    const val FORMAT_VERSION_V1: Int = 1
    const val HEADER_BYTES: Int = 32
    const val CHUNK_PLAIN_BYTES: Int = 256 * 1024 // 256 KiB per chunk
    const val CHUNK_FRAME_OVERHEAD_BYTES: Int = 4 + CryptoEngine.NONCE_BYTES + CryptoEngine.GCM_TAG_BYTES // 32 bytes
    const val FULL_CHUNK_FRAME_BYTES: Int = CHUNK_PLAIN_BYTES + CHUNK_FRAME_OVERHEAD_BYTES // 262,176 bytes

    data class ParsedMediaHeader(
        val formatVersion: Int,
        val flags: Int,
        val nominalChunkPlainBytes: Int,
        val totalChunks: Int,
        val totalPlaintextBytes: Long
    )

    data class EncryptedMediaVerifyReport(
        val header: ParsedMediaHeader,
        val encryptedFileBytes: Long,
        val ciphertextSha256Hex: String
    )

    fun buildChunkAad(
        mediaId: String,
        vaultSlot: Int,
        formatVersion: Int,
        chunkIndex: Int,
        totalChunks: Int,
        isLastChunk: Boolean,
        chunkPlainLength: Int,
        totalPlaintextBytes: Long
    ): ByteArray {
        val aad = "PVM1-CHK|mediaId=$mediaId|slot=$vaultSlot|ver=$formatVersion|idx=$chunkIndex|total=$totalChunks|last=$isLastChunk|len=$chunkPlainLength|totalPlain=$totalPlaintextBytes"
        return aad.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildThumbnailAad(
        mediaId: String,
        vaultSlot: Int,
        formatVersion: Int = FORMAT_VERSION_V1
    ): ByteArray {
        val aad = "PVT1|mediaId=$mediaId|slot=$vaultSlot|ver=$formatVersion"
        return aad.toByteArray(StandardCharsets.UTF_8)
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun encryptStreamToMediaFile(
        inputStream: InputStream,
        expectedPlaintextSize: Long,
        targetFile: File,
        mediaId: String,
        vaultSlot: Int,
        fileMediaKey: ByteArray,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ParsedMediaHeader {
        require(fileMediaKey.size == CryptoEngine.DEK_BYTES) { "Media key must be 32 bytes." }
        require(expectedPlaintextSize > 0L) { "Cannot import an empty (0-byte) file." }

        val totalChunksLong = (expectedPlaintextSize + CHUNK_PLAIN_BYTES - 1L) / CHUNK_PLAIN_BYTES.toLong()
        require(totalChunksLong in 1L..Int.MAX_VALUE.toLong()) { "Invalid chunk count." }
        val totalChunks = totalChunksLong.toInt()

        val headerBytes = ByteArray(HEADER_BYTES)
        ByteBuffer.wrap(headerBytes).apply {
            put(PVM1_MAGIC)
            putShort(FORMAT_VERSION_V1.toShort())
            putShort(0.toShort())
            putInt(CHUNK_PLAIN_BYTES)
            putInt(totalChunks)
            putLong(expectedPlaintextSize)
            putLong(0L)
        }

        val readBuffer = ByteArray(CHUNK_PLAIN_BYTES)
        var totalPlainRead = 0L
        var chunkIndex = 0

        try {
            FileOutputStream(targetFile).use { fos ->
                fos.write(headerBytes)

                while (true) {
                    val bytesRead = readChunkFullyOrUntilEof(inputStream, readBuffer)
                    if (bytesRead <= 0) break

                    if (chunkIndex >= totalChunks) {
                        throw IllegalArgumentException("Source stream exceeded expected byte length.")
                    }
                    totalPlainRead += bytesRead.toLong()
                    val isLast = (chunkIndex == totalChunks - 1)
                    if (!isLast && bytesRead != CHUNK_PLAIN_BYTES) {
                        throw IllegalArgumentException("Non-final chunk had unexpected length ($bytesRead).")
                    }

                    val nonce = CryptoEngine.generateNonce()
                    val aad = buildChunkAad(
                        mediaId = mediaId,
                        vaultSlot = vaultSlot,
                        formatVersion = FORMAT_VERSION_V1,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        isLastChunk = isLast,
                        chunkPlainLength = bytesRead,
                        totalPlaintextBytes = expectedPlaintextSize
                    )

                    val plainChunkSlice = if (bytesRead == CHUNK_PLAIN_BYTES) {
                        readBuffer
                    } else {
                        readBuffer.copyOf(bytesRead)
                    }

                    val cipherWithTag = CryptoEngine.encryptAesGcm(
                        keyBytes = fileMediaKey,
                        nonce = nonce,
                        plaintext = plainChunkSlice,
                        aad = aad
                    )

                    val frameHeader = ByteArray(4 + CryptoEngine.NONCE_BYTES)
                    ByteBuffer.wrap(frameHeader).apply {
                        putInt(bytesRead)
                        put(nonce)
                    }
                    fos.write(frameHeader)
                    fos.write(cipherWithTag)

                    chunkIndex++
                    onProgress(totalPlainRead, expectedPlaintextSize)
                }
            }

            if (totalPlainRead != expectedPlaintextSize || chunkIndex != totalChunks) {
                targetFile.delete()
                throw IllegalArgumentException("Stream terminated prematurely ($totalPlainRead of $expectedPlaintextSize bytes read).")
            }

            return ParsedMediaHeader(
                formatVersion = FORMAT_VERSION_V1,
                flags = 0,
                nominalChunkPlainBytes = CHUNK_PLAIN_BYTES,
                totalChunks = totalChunks,
                totalPlaintextBytes = expectedPlaintextSize
            )
        } finally {
            Arrays.fill(readBuffer, 0.toByte())
        }
    }

    @Throws(IOException::class, AEADBadTagException::class, IllegalArgumentException::class)
    fun verifyEncryptedMediaFileEveryChunk(
        encryptedFile: File,
        mediaId: String,
        vaultSlot: Int,
        fileMediaKey: ByteArray
    ): EncryptedMediaVerifyReport {
        require(encryptedFile.exists() && encryptedFile.isFile) { "Encrypted file does not exist." }
        val fileLength = encryptedFile.length()
        require(fileLength >= HEADER_BYTES + CHUNK_FRAME_OVERHEAD_BYTES + 1) { "Encrypted file is truncated." }

        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(encryptedFile, "r").use { raf ->
            val headerBytes = ByteArray(HEADER_BYTES)
            raf.readFully(headerBytes)
            md.update(headerBytes)

            val header = parseHeaderBytes(headerBytes)
            require(header.formatVersion == FORMAT_VERSION_V1) { "Unsupported media format version: ${header.formatVersion}" }
            require(header.totalChunks >= 1) { "Invalid chunk count: ${header.totalChunks}" }
            require(header.totalPlaintextBytes > 0L) { "Invalid plaintext length: ${header.totalPlaintextBytes}" }

            var plainAccumulator = 0L
            val frameHeaderBuf = ByteArray(4 + CryptoEngine.NONCE_BYTES)
            val nonce = ByteArray(CryptoEngine.NONCE_BYTES)

            for (i in 0 until header.totalChunks) {
                raf.readFully(frameHeaderBuf)
                md.update(frameHeaderBuf)

                val bb = ByteBuffer.wrap(frameHeaderBuf)
                val chunkPlainLen = bb.int
                bb.get(nonce)

                val isLast = (i == header.totalChunks - 1)
                if (isLast) {
                    val remainingExpected = header.totalPlaintextBytes - plainAccumulator
                    require(chunkPlainLen.toLong() == remainingExpected) {
                        "Final chunk length ($chunkPlainLen) does not match remaining expected bytes ($remainingExpected)."
                    }
                } else {
                    require(chunkPlainLen == header.nominalChunkPlainBytes) {
                        "Intermediate chunk length ($chunkPlainLen) does not equal nominal size (${header.nominalChunkPlainBytes})."
                    }
                }
                plainAccumulator += chunkPlainLen.toLong()

                val cipherLen = chunkPlainLen + CryptoEngine.GCM_TAG_BYTES
                val cipherBuf = ByteArray(cipherLen)
                raf.readFully(cipherBuf)
                md.update(cipherBuf)

                val aad = buildChunkAad(
                    mediaId = mediaId,
                    vaultSlot = vaultSlot,
                    formatVersion = header.formatVersion,
                    chunkIndex = i,
                    totalChunks = header.totalChunks,
                    isLastChunk = isLast,
                    chunkPlainLength = chunkPlainLen,
                    totalPlaintextBytes = header.totalPlaintextBytes
                )

                val decrypted = CryptoEngine.decryptAesGcm(
                    keyBytes = fileMediaKey,
                    nonce = nonce,
                    ciphertext = cipherBuf,
                    aad = aad
                )
                Arrays.fill(decrypted, 0.toByte())
            }

            require(raf.filePointer == fileLength) {
                "Trailing unauthenticated bytes detected at end of encrypted media file."
            }
            require(plainAccumulator == header.totalPlaintextBytes) {
                "Accumulated chunk length ($plainAccumulator) did not match header length (${header.totalPlaintextBytes})."
            }

            val shaHex = md.digest().joinToString("") { "%02x".format(it) }
            return EncryptedMediaVerifyReport(
                header = header,
                encryptedFileBytes = fileLength,
                ciphertextSha256Hex = shaHex
            )
        }
    }

    fun parseHeaderBytes(headerBytes: ByteArray): ParsedMediaHeader {
        require(headerBytes.size == HEADER_BYTES) { "Invalid header buffer length." }
        val bb = ByteBuffer.wrap(headerBytes)
        val magic = ByteArray(4)
        bb.get(magic)
        require(magic.contentEquals(PVM1_MAGIC)) { "Invalid magic bytes in encrypted header." }

        val formatVersion = bb.short.toInt()
        val flags = bb.short.toInt()
        val nominalChunk = bb.int
        val totalChunks = bb.int
        val totalPlain = bb.long
        return ParsedMediaHeader(
            formatVersion = formatVersion,
            flags = flags,
            nominalChunkPlainBytes = nominalChunk,
            totalChunks = totalChunks,
            totalPlaintextBytes = totalPlain
        )
    }

    @Throws(IOException::class, AEADBadTagException::class)
    fun decryptEntireMediaFile(
        encryptedFile: File,
        mediaId: String,
        vaultSlot: Int,
        fileMediaKey: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        RandomAccessFile(encryptedFile, "r").use { raf ->
            val headerBytes = ByteArray(HEADER_BYTES)
            raf.readFully(headerBytes)
            val header = parseHeaderBytes(headerBytes)

            val frameHeaderBuf = ByteArray(4 + CryptoEngine.NONCE_BYTES)
            val nonce = ByteArray(CryptoEngine.NONCE_BYTES)

            for (i in 0 until header.totalChunks) {
                raf.readFully(frameHeaderBuf)
                val bb = ByteBuffer.wrap(frameHeaderBuf)
                val chunkPlainLen = bb.int
                bb.get(nonce)

                val isLast = (i == header.totalChunks - 1)
                val cipherLen = chunkPlainLen + CryptoEngine.GCM_TAG_BYTES
                val cipherBuf = ByteArray(cipherLen)
                raf.readFully(cipherBuf)

                val aad = buildChunkAad(
                    mediaId = mediaId,
                    vaultSlot = vaultSlot,
                    formatVersion = header.formatVersion,
                    chunkIndex = i,
                    totalChunks = header.totalChunks,
                    isLastChunk = isLast,
                    chunkPlainLength = chunkPlainLen,
                    totalPlaintextBytes = header.totalPlaintextBytes
                )

                val plain = CryptoEngine.decryptAesGcm(
                    keyBytes = fileMediaKey,
                    nonce = nonce,
                    ciphertext = cipherBuf,
                    aad = aad
                )
                try {
                    out.write(plain)
                } finally {
                    Arrays.fill(plain, 0.toByte())
                }
            }
        }
        return out.toByteArray()
    }

    fun encryptThumbnail(
        thumbnailPngBytes: ByteArray,
        mediaId: String,
        vaultSlot: Int,
        thumbKey: ByteArray
    ): ByteArray {
        require(thumbKey.size == CryptoEngine.DEK_BYTES) { "Thumb key must be 32 bytes." }
        val nonce = CryptoEngine.generateNonce()
        val aad = buildThumbnailAad(mediaId, vaultSlot)
        val ciphertext = CryptoEngine.encryptAesGcm(
            keyBytes = thumbKey,
            nonce = nonce,
            plaintext = thumbnailPngBytes,
            aad = aad
        )
        val out = ByteArray(CryptoEngine.NONCE_BYTES + ciphertext.size)
        System.arraycopy(nonce, 0, out, 0, CryptoEngine.NONCE_BYTES)
        System.arraycopy(ciphertext, 0, out, CryptoEngine.NONCE_BYTES, ciphertext.size)
        return out
    }

    fun decryptThumbnail(
        encryptedThumbBytes: ByteArray,
        mediaId: String,
        vaultSlot: Int,
        thumbKey: ByteArray
    ): ByteArray {
        require(thumbKey.size == CryptoEngine.DEK_BYTES) { "Thumb key must be 32 bytes." }
        require(encryptedThumbBytes.size > CryptoEngine.NONCE_BYTES + CryptoEngine.GCM_TAG_BYTES) {
            "Thumbnail payload is truncated."
        }
        val nonce = encryptedThumbBytes.copyOfRange(0, CryptoEngine.NONCE_BYTES)
        val ciphertext = encryptedThumbBytes.copyOfRange(CryptoEngine.NONCE_BYTES, encryptedThumbBytes.size)
        val aad = buildThumbnailAad(mediaId, vaultSlot)
        return CryptoEngine.decryptAesGcm(
            keyBytes = thumbKey,
            nonce = nonce,
            ciphertext = ciphertext,
            aad = aad
        )
    }

    private fun readChunkFullyOrUntilEof(stream: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val r = stream.read(buffer, totalRead, buffer.size - totalRead)
            if (r < 0) break
            totalRead += r
        }
        return totalRead
    }

    /**
     * In-memory seekable `MediaDataSource` for streaming video playback directly from
     * encrypted `.pvmedia` files with zero disk plaintext.
     */
    class EncryptedChunkMediaDataSource(
        private val mediaFile: File,
        private val mediaId: String,
        private val vaultSlot: Int,
        private val fileMediaKey: ByteArray
    ) : MediaDataSource() {

        private val raf: RandomAccessFile = RandomAccessFile(mediaFile, "r")
        private val header: ParsedMediaHeader

        // Ring buffer holding at most 2 decrypted chunks (512 KiB) in memory
        private var cachedChunkIndexA: Int = -1
        private var cachedChunkBytesA: ByteArray? = null

        private var cachedChunkIndexB: Int = -1
        private var cachedChunkBytesB: ByteArray? = null

        init {
            val headerBuf = ByteArray(HEADER_BYTES)
            raf.readFully(headerBuf)
            header = parseHeaderBytes(headerBuf)
        }

        @Synchronized
        override fun getSize(): Long = header.totalPlaintextBytes

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= header.totalPlaintextBytes) return -1
            if (size <= 0) return 0

            val toRead = size.coerceAtMost((header.totalPlaintextBytes - position).toInt())
            var bytesCopied = 0

            while (bytesCopied < toRead) {
                val currentPos = position + bytesCopied
                val chunkIndex = (currentPos / CHUNK_PLAIN_BYTES.toLong()).toInt()
                val chunkOffset = (currentPos % CHUNK_PLAIN_BYTES.toLong()).toInt()

                val chunk = getDecryptedChunk(chunkIndex)
                val availableInChunk = chunk.size - chunkOffset
                val copyLen = (toRead - bytesCopied).coerceAtMost(availableInChunk)

                System.arraycopy(chunk, chunkOffset, buffer, offset + bytesCopied, copyLen)
                bytesCopied += copyLen
            }

            return bytesCopied
        }

        @Synchronized
        private fun getDecryptedChunk(index: Int): ByteArray {
            if (index == cachedChunkIndexA && cachedChunkBytesA != null) {
                return cachedChunkBytesA!!
            }
            if (index == cachedChunkIndexB && cachedChunkBytesB != null) {
                return cachedChunkBytesB!!
            }

            val decrypted = readAndDecryptChunkAt(index)
            // Evict slot A to slot B and put new in slot A
            cachedChunkBytesB?.let { Arrays.fill(it, 0.toByte()) }
            cachedChunkIndexB = cachedChunkIndexA
            cachedChunkBytesB = cachedChunkBytesA

            cachedChunkIndexA = index
            cachedChunkBytesA = decrypted
            return decrypted
        }

        private fun readAndDecryptChunkAt(index: Int): ByteArray {
            // Compute file offset of chunk frame `index`
            var frameOffset = HEADER_BYTES.toLong() + (index.toLong() * FULL_CHUNK_FRAME_BYTES.toLong())
            raf.seek(frameOffset)

            val frameHeader = ByteArray(4 + CryptoEngine.NONCE_BYTES)
            raf.readFully(frameHeader)
            val bb = ByteBuffer.wrap(frameHeader)
            val plainLen = bb.int
            val nonce = ByteArray(CryptoEngine.NONCE_BYTES)
            bb.get(nonce)

            val isLast = (index == header.totalChunks - 1)
            val cipherLen = plainLen + CryptoEngine.GCM_TAG_BYTES
            val cipherBuf = ByteArray(cipherLen)
            raf.readFully(cipherBuf)

            val aad = buildChunkAad(
                mediaId = mediaId,
                vaultSlot = vaultSlot,
                formatVersion = header.formatVersion,
                chunkIndex = index,
                totalChunks = header.totalChunks,
                isLastChunk = isLast,
                chunkPlainLength = plainLen,
                totalPlaintextBytes = header.totalPlaintextBytes
            )

            return CryptoEngine.decryptAesGcm(
                keyBytes = fileMediaKey,
                nonce = nonce,
                ciphertext = cipherBuf,
                aad = aad
            )
        }

        @Synchronized
        override fun close() {
            cachedChunkBytesA?.let { Arrays.fill(it, 0.toByte()) }
            cachedChunkBytesB?.let { Arrays.fill(it, 0.toByte()) }
            cachedChunkBytesA = null
            cachedChunkBytesB = null
            cachedChunkIndexA = -1
            cachedChunkIndexB = -1
            runCatching { raf.close() }
        }
    }
}
