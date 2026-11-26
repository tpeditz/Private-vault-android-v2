package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException

/**
 * Unified streaming encrypted backup & restore engine for PRIVATE VAULT V2 (`PVAULTB2` & `PVAULTB3`).
 *
 * Resource & Streaming Expectations:
 * - File payloads and chunked ciphertexts stream through bounded I/O buffers (64 KiB for V2;
 *   256 KiB chunk accumulator for V3) up to the 2 GiB (2,147,483,648 bytes) container limit.
 * - Note: Total process memory during backup/restore ALSO includes Argon2id working memory
 *   (16-32 MiB during key derivation), the encrypted/decoded JSON manifest (capped at 32 MiB),
 *   and decoded runtime record objects (PV-16).
 *
 * Container Layouts:
 * - `PVAULTB3` (Schema v3 - Default privacy-enhanced exports):
 *   - `[0..7]`   8B Magic `"PVAULTB3"`
 *   - `[8..11]`  4B `backupSchemaVersion` (`3`)
 *   - `[12..15]` 4B `kdfMemoryKb`
 *   - `[16..19]` 4B `kdfIterations`
 *   - `[20..23]` 4B `kdfParallelism`
 *   - `[24..39]` 16B `backupKdfSalt`
 *   - `[40..EOF]` Continuous stream `[4B manifestLen][manifestJson][asset0][asset1]...` encrypted
 *     in uniform 256 KiB AES-256-GCM frames (hiding manifest length, asset count, boundaries,
 *     and internal `PVM1` headers inside authenticated ciphertext).
 * - `PVAULTB2` (Schema v2 - Backward-compatible reader/writer):
 *   - 56B Header + AES-256-GCM Manifest + sequential `PVM1`/`PVT1` payloads.
 */
object EncryptedBackupV2Engine {

    val V2_MAGIC_BYTES = "PVAULTB2".toByteArray(StandardCharsets.US_ASCII)
    val V3_MAGIC_BYTES = "PVAULTB3".toByteArray(StandardCharsets.US_ASCII)

    const val HEADER_BYTES = 56
    const val HEADER_BYTES_V3 = 40
    const val V3_CHUNK_PLAIN_BYTES = StreamingMediaCryptoEngine.CHUNK_PLAIN_BYTES // 256 KiB
    const val V3_FRAME_HEADER_BYTES = 4 + 1 + CryptoEngine.NONCE_BYTES // 17B
    const val V3_FRAME_OVERHEAD_BYTES = V3_FRAME_HEADER_BYTES + CryptoEngine.GCM_TAG_BYTES // 33B

    const val MAX_SUPPORTED_BACKUP_BYTES = 2L * 1024L * 1024L * 1024L // 2 GiB target
    const val MAX_RECORDS = 50_000
    const val MAX_MANIFEST_CIPHERTEXT_BYTES = 32 * 1024 * 1024
    const val MAX_MANIFEST_PLAINTEXT_BYTES = MAX_MANIFEST_CIPHERTEXT_BYTES - CryptoEngine.GCM_TAG_BYTES
    private const val BUFFER_SIZE = 64 * 1024 // 64 KiB streaming I/O buffer

    data class AuthoritativeAssetExpectation(
        val relativeMediaOrDocPath: String? = null,
        val relativeThumbPath: String? = null,
        val expectedEncryptedBytes: Long? = null,
        val expectedSha256Hex: String? = null,
        val expectedChunkCount: Int? = null,
        val verifyPvm1Integrity: Boolean = false,
        val vaultSlotForAuth: Int? = null
    )

    private data class VerifiedMediaSource(
        val mediaId: String,
        val mediaFile: File,
        val mediaLen: Long,
        val mediaSha256: String,
        val thumbFile: File,
        val thumbLen: Long,
        val thumbSha256: String,
        val chunkCount: Int
    )

    private data class VerifiedDocSource(
        val documentId: String,
        val docFile: File,
        val docLen: Long,
        val docSha256: String,
        val chunkCount: Int
    )

    fun isV2Backup(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < V2_MAGIC_BYTES.size) return false
        return headerBytes.copyOfRange(0, V2_MAGIC_BYTES.size).contentEquals(V2_MAGIC_BYTES)
    }

    fun isV3Backup(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < V3_MAGIC_BYTES.size) return false
        return headerBytes.copyOfRange(0, V3_MAGIC_BYTES.size).contentEquals(V3_MAGIC_BYTES)
    }

    fun isSupportedBackup(headerBytes: ByteArray): Boolean = isV2Backup(headerBytes) || isV3Backup(headerBytes)

    /**
     * Streams a backward-compatible `PVAULTB2` encrypted backup directly to `outputStream`,
     * enforcing identical writer/reader format limits and live asset SHA-256 verification.
     */
    fun createStreamingBackup(
        items: List<VaultItem>,
        backupPasswordChars: CharArray,
        outputStream: OutputStream,
        appFilesDir: File,
        appVersionName: String = "2.0.0",
        kdfParams: CryptoEngine.KdfParams = CryptoEngine.KdfParams(),
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        assetPathResolver: (itemId: String) -> Pair<String?, String?> = { null to null },
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit = { _, _ -> },
        assetExpectations: Map<String, AuthoritativeAssetExpectation> = emptyMap(),
        exportScope: BackupExportScope = BackupExportScope.ALL_DATA
    ) {
        val prepared = prepareVerifiedBackupExport(
            items = items,
            backupPasswordChars = backupPasswordChars,
            appFilesDir = appFilesDir,
            appVersionName = appVersionName,
            schemaVersion = 2,
            kdfParams = kdfParams,
            exportedAtEpochMs = exportedAtEpochMs,
            assetPathResolver = assetPathResolver,
            assetExpectations = assetExpectations,
            exportScope = exportScope,
            isCancelled = isCancelled
        )

        val salt = CryptoEngine.generateSalt()
        val manifestNonce = CryptoEngine.generateNonce()
        val backupKek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPasswordChars,
            salt = salt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        if (isCancelled()) {
            Arrays.fill(backupKek, 0.toByte())
            Arrays.fill(prepared.manifestPlaintext, 0.toByte())
            throw CancellationException("Backup export cancelled by user.")
        }

        try {
            val manifestAad = CryptoEngine.buildBackupAad(2, kdfParams)
            val manifestCiphertext = CryptoEngine.encryptAesGcm(
                keyBytes = backupKek,
                nonce = manifestNonce,
                plaintext = prepared.manifestPlaintext,
                aad = manifestAad
            )
            require(manifestCiphertext.size in 1..MAX_MANIFEST_CIPHERTEXT_BYTES) {
                "Backup metadata exceeds maximum supported limit (32 MiB)."
            }

            val totalAssetBytes = VaultFileSafety.checkedSizeTotal(
                prepared.mediaSources.flatMap { listOf(it.mediaLen, it.thumbLen) } +
                    prepared.docSources.map { it.docLen },
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )
            val totalEstimatedBytes = VaultFileSafety.checkedSizeTotal(
                listOf(HEADER_BYTES.toLong(), manifestCiphertext.size.toLong(), totalAssetBytes),
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )

            val headerBytes = ByteArray(HEADER_BYTES)
            ByteBuffer.wrap(headerBytes).apply {
                put(V2_MAGIC_BYTES)
                putInt(2)
                putInt(kdfParams.memoryKb)
                putInt(kdfParams.iterations)
                putInt(kdfParams.parallelism)
                put(salt)
                put(manifestNonce)
                putInt(manifestCiphertext.size)
            }

            outputStream.write(headerBytes)
            outputStream.write(manifestCiphertext)

            var totalBytesWritten = HEADER_BYTES.toLong() + manifestCiphertext.size.toLong()
            onProgress(totalBytesWritten, totalEstimatedBytes)

            val streamBuffer = ByteArray(BUFFER_SIZE)
            try {
                for (m in prepared.mediaSources) {
                    totalBytesWritten += streamFileWithLiveShaVerification(
                        sourceFile = m.mediaFile,
                        expectedLength = m.mediaLen,
                        expectedSha256Hex = m.mediaSha256,
                        outputStream = outputStream,
                        buffer = streamBuffer,
                        isCancelled = isCancelled
                    ) { chunkBytes ->
                        onProgress(totalBytesWritten + chunkBytes, totalEstimatedBytes)
                    }
                    totalBytesWritten += streamFileWithLiveShaVerification(
                        sourceFile = m.thumbFile,
                        expectedLength = m.thumbLen,
                        expectedSha256Hex = m.thumbSha256,
                        outputStream = outputStream,
                        buffer = streamBuffer,
                        isCancelled = isCancelled
                    ) { chunkBytes ->
                        onProgress(totalBytesWritten + chunkBytes, totalEstimatedBytes)
                    }
                }
                for (d in prepared.docSources) {
                    totalBytesWritten += streamFileWithLiveShaVerification(
                        sourceFile = d.docFile,
                        expectedLength = d.docLen,
                        expectedSha256Hex = d.docSha256,
                        outputStream = outputStream,
                        buffer = streamBuffer,
                        isCancelled = isCancelled
                    ) { chunkBytes ->
                        onProgress(totalBytesWritten + chunkBytes, totalEstimatedBytes)
                    }
                }
            } finally {
                Arrays.fill(streamBuffer, 0.toByte())
            }
            require(totalBytesWritten == totalEstimatedBytes) {
                "Backup assets changed while exporting. Please retry."
            }
            if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
            outputStream.flush()
        } finally {
            Arrays.fill(backupKek, 0.toByte())
            Arrays.fill(prepared.manifestPlaintext, 0.toByte())
        }
    }

    /**
     * Streams a privacy-enhanced `PVAULTB3` container where the metadata manifest and all
     * encrypted media/thumb/doc assets are packed into one uniform chunked AES-256-GCM stream
     * without leaking manifest length, per-asset counts, boundaries, or `PVM1` headers.
     */
    fun createPrivacyEnhancedStreamingBackup(
        items: List<VaultItem>,
        backupPasswordChars: CharArray,
        outputStream: OutputStream,
        appFilesDir: File,
        appVersionName: String = "2.0.4",
        kdfParams: CryptoEngine.KdfParams = CryptoEngine.KdfParams(),
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        assetPathResolver: (itemId: String) -> Pair<String?, String?> = { null to null },
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit = { _, _ -> },
        assetExpectations: Map<String, AuthoritativeAssetExpectation> = emptyMap(),
        exportScope: BackupExportScope = BackupExportScope.ALL_DATA
    ) {
        val prepared = prepareVerifiedBackupExport(
            items = items,
            backupPasswordChars = backupPasswordChars,
            appFilesDir = appFilesDir,
            appVersionName = appVersionName,
            schemaVersion = 3,
            kdfParams = kdfParams,
            exportedAtEpochMs = exportedAtEpochMs,
            assetPathResolver = assetPathResolver,
            assetExpectations = assetExpectations,
            exportScope = exportScope,
            isCancelled = isCancelled
        )

        val totalAssetBytes = VaultFileSafety.checkedSizeTotal(
            prepared.mediaSources.flatMap { listOf(it.mediaLen, it.thumbLen) } +
                prepared.docSources.map { it.docLen },
            MAX_SUPPORTED_BACKUP_BYTES,
            "2 GiB"
        )
        val totalPlainStreamBytes = VaultFileSafety.checkedSizeTotal(
            listOf(4L, prepared.manifestPlaintext.size.toLong(), totalAssetBytes),
            MAX_SUPPORTED_BACKUP_BYTES,
            "2 GiB"
        )
        val totalChunks = (totalPlainStreamBytes - 1L) / V3_CHUNK_PLAIN_BYTES.toLong() + 1L
        val totalFramingOverhead = totalChunks * V3_FRAME_OVERHEAD_BYTES.toLong()
        val totalContainerBytes = VaultFileSafety.checkedSizeTotal(
            listOf(HEADER_BYTES_V3.toLong(), totalFramingOverhead, totalPlainStreamBytes),
            MAX_SUPPORTED_BACKUP_BYTES,
            "2 GiB"
        )

        val salt = CryptoEngine.generateSalt()
        val backupKek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPasswordChars,
            salt = salt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        if (isCancelled()) {
            Arrays.fill(backupKek, 0.toByte())
            Arrays.fill(prepared.manifestPlaintext, 0.toByte())
            throw CancellationException("Backup export cancelled by user.")
        }

        val chunkBuffer = ByteArray(V3_CHUNK_PLAIN_BYTES)
        var chunkBufPos = 0
        var chunkIndex = 0
        var plainBytesFed = 0L
        var containerBytesWritten = 0L

        fun flushChunk(isLast: Boolean) {
            require(chunkBufPos in 1..V3_CHUNK_PLAIN_BYTES) { "Invalid V3 chunk size ($chunkBufPos)." }
            if (!isLast) {
                require(chunkBufPos == V3_CHUNK_PLAIN_BYTES) { "Intermediate V3 chunk size must be full." }
            }
            val plainSlice = if (chunkBufPos == V3_CHUNK_PLAIN_BYTES) chunkBuffer else chunkBuffer.copyOf(chunkBufPos)
            val nonce = CryptoEngine.generateNonce()
            val aad = buildV3ChunkAad(kdfParams, chunkIndex, isLast, chunkBufPos)
            val ciphertext = CryptoEngine.encryptAesGcm(
                keyBytes = backupKek,
                nonce = nonce,
                plaintext = plainSlice,
                aad = aad
            )
            if (plainSlice !== chunkBuffer) {
                Arrays.fill(plainSlice, 0.toByte())
            }

            val frameHeader = ByteArray(V3_FRAME_HEADER_BYTES)
            ByteBuffer.wrap(frameHeader).apply {
                putInt(chunkBufPos)
                put(if (isLast) 1.toByte() else 0.toByte())
                put(nonce)
            }
            outputStream.write(frameHeader)
            outputStream.write(ciphertext)
            containerBytesWritten += V3_FRAME_HEADER_BYTES.toLong() + ciphertext.size.toLong()
            chunkBufPos = 0
            chunkIndex++
            onProgress(containerBytesWritten, totalContainerBytes)
        }

        fun feedPlainBytes(src: ByteArray, off: Int, len: Int) {
            var pos = off
            var rem = len
            while (rem > 0) {
                if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
                val space = V3_CHUNK_PLAIN_BYTES - chunkBufPos
                val take = minOf(space, rem)
                System.arraycopy(src, pos, chunkBuffer, chunkBufPos, take)
                chunkBufPos += take
                plainBytesFed += take.toLong()
                pos += take
                rem -= take
                val isAtStreamEnd = (plainBytesFed == totalPlainStreamBytes)
                if (chunkBufPos == V3_CHUNK_PLAIN_BYTES && !isAtStreamEnd) {
                    flushChunk(isLast = false)
                }
            }
        }

        fun feedVerifiedFile(sourceFile: File, expectedLen: Long, expectedShaHex: String) {
            val md = MessageDigest.getInstance("SHA-256")
            val ioBuf = ByteArray(BUFFER_SIZE)
            var fileReadTotal = 0L
            try {
                FileInputStream(sourceFile).use { fis ->
                    var r: Int
                    while (fis.read(ioBuf).also { r = it } != -1) {
                        if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
                        fileReadTotal += r.toLong()
                        require(fileReadTotal <= expectedLen) {
                            "Backup assets changed while exporting. Please retry."
                        }
                        md.update(ioBuf, 0, r)
                        feedPlainBytes(ioBuf, 0, r)
                    }
                }
                require(fileReadTotal == expectedLen) {
                    "Backup assets changed while exporting. Please retry."
                }
                val streamedSha = md.digest().joinToString("") { "%02x".format(it) }
                require(streamedSha.equals(expectedShaHex, ignoreCase = true)) {
                    "Backup payload integrity changed while exporting ${sourceFile.name}."
                }
            } finally {
                Arrays.fill(ioBuf, 0.toByte())
            }
        }

        try {
            val headerBytes = ByteArray(HEADER_BYTES_V3)
            ByteBuffer.wrap(headerBytes).apply {
                put(V3_MAGIC_BYTES)
                putInt(3)
                putInt(kdfParams.memoryKb)
                putInt(kdfParams.iterations)
                putInt(kdfParams.parallelism)
                put(salt)
            }
            outputStream.write(headerBytes)
            containerBytesWritten += HEADER_BYTES_V3.toLong()
            onProgress(containerBytesWritten, totalContainerBytes)

            val manifestLenBuf = ByteBuffer.allocate(4).putInt(prepared.manifestPlaintext.size).array()
            feedPlainBytes(manifestLenBuf, 0, 4)
            feedPlainBytes(prepared.manifestPlaintext, 0, prepared.manifestPlaintext.size)

            for (m in prepared.mediaSources) {
                feedVerifiedFile(m.mediaFile, m.mediaLen, m.mediaSha256)
                feedVerifiedFile(m.thumbFile, m.thumbLen, m.thumbSha256)
            }
            for (d in prepared.docSources) {
                feedVerifiedFile(d.docFile, d.docLen, d.docSha256)
            }

            require(plainBytesFed == totalPlainStreamBytes) {
                "Backup stream length mismatch during export."
            }
            if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
            if (chunkBufPos > 0) {
                flushChunk(isLast = true)
            }
            require(chunkIndex.toLong() == totalChunks) {
                "Backup chunk framing mismatch."
            }
            require(containerBytesWritten == totalContainerBytes) {
                "Backup output size mismatch."
            }
            if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
            outputStream.flush()
        } finally {
            Arrays.fill(backupKek, 0.toByte())
            Arrays.fill(chunkBuffer, 0.toByte())
            Arrays.fill(prepared.manifestPlaintext, 0.toByte())
        }
    }

    private data class PreparedBackupExport(
        val manifestPlaintext: ByteArray,
        val mediaSources: List<VerifiedMediaSource>,
        val docSources: List<VerifiedDocSource>
    )

    private fun prepareVerifiedBackupExport(
        items: List<VaultItem>,
        backupPasswordChars: CharArray,
        appFilesDir: File,
        appVersionName: String,
        schemaVersion: Int,
        kdfParams: CryptoEngine.KdfParams,
        exportedAtEpochMs: Long,
        assetPathResolver: (itemId: String) -> Pair<String?, String?>,
        assetExpectations: Map<String, AuthoritativeAssetExpectation>,
        exportScope: BackupExportScope,
        isCancelled: () -> Boolean
    ): PreparedBackupExport {
        require(backupPasswordChars.isNotEmpty()) { "Backup password cannot be empty." }
        require(items.size <= MAX_RECORDS) { "Record count exceeds maximum supported ($MAX_RECORDS)." }
        require(exportedAtEpochMs >= 0L) { "Invalid backup timestamp." }
        kdfParams.validateBounds()
        if (isCancelled()) throw CancellationException("Backup export cancelled by user.")

        val seenIds = HashSet<String>(items.size)
        val mediaRecords = mutableListOf<MediaRecord>()
        val documentRecords = mutableListOf<DocumentRecord>()

        val manifestRoot = JSONObject().apply {
            put("backupSchemaVersion", schemaVersion)
            put("exportedAtEpochMs", exportedAtEpochMs)
            put("appVersionName", appVersionName)
            put("applicationId", "com.tpeditz.privatevault.v2")
            put("exportScope", exportScope.name)
        }

        val recordsArray = JSONArray()
        for (item in items) {
            if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
            require(item.id.isNotBlank()) { "Cannot export vault record with blank ID." }
            require(seenIds.add(item.id)) { "Duplicate vault record ID detected: ${item.id}." }
            when (item) {
                is MediaRecord -> {
                    VaultFileSafety.requireAssetId(item.id)
                    mediaRecords.add(item)
                }
                is DocumentRecord -> {
                    VaultFileSafety.requireAssetId(item.id)
                    documentRecords.add(item)
                }
                else -> {}
            }
            val encodedBytes = VaultRecordCodec.encode(item)
            val itemJson = JSONObject(String(encodedBytes, StandardCharsets.UTF_8))
            Arrays.fill(encodedBytes, 0.toByte())
            recordsArray.put(itemJson)
        }
        manifestRoot.put("records", recordsArray)

        val mediaSources = ArrayList<VerifiedMediaSource>(mediaRecords.size)
        val mediaEntriesArray = JSONArray()
        for (media in mediaRecords) {
            if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
            val exp = assetExpectations[media.id]
            val (resolvedMediaRel, resolvedThumbRel) = assetPathResolver(media.id)
            val mediaRel = exp?.relativeMediaOrDocPath ?: resolvedMediaRel
            val thumbRel = exp?.relativeThumbPath ?: resolvedThumbRel

            val mediaCandidates = buildList {
                if (!mediaRel.isNullOrBlank()) add(File(appFilesDir, mediaRel))
                add(File(appFilesDir, "media/${media.id}.pvmedia"))
                add(File(appFilesDir, "encrypted_media_${media.id}.pvm"))
            }
            val mediaFile = mediaCandidates.firstOrNull { it.exists() } ?: mediaCandidates.first()
            VaultFileSafety.requireWithin(appFilesDir, mediaFile)
            require(mediaFile.exists() && mediaFile.length() > 0L) {
                "Backup aborted: missing encrypted media payload for ${media.title.ifBlank { media.id }}."
            }

            val thumbCandidates = buildList {
                if (!thumbRel.isNullOrBlank()) add(File(appFilesDir, thumbRel))
                add(File(appFilesDir, "thumbs/${media.id}.pvthumb"))
                add(File(appFilesDir, "encrypted_thumb_${media.id}.pvt"))
            }
            val thumbFile = thumbCandidates.firstOrNull { it.exists() } ?: thumbCandidates.first()
            VaultFileSafety.requireWithin(appFilesDir, thumbFile)
            require(thumbFile.exists() && thumbFile.length() > 0L) {
                "Backup aborted: missing encrypted thumbnail payload for ${media.title.ifBlank { media.id }}."
            }

            val mediaLen = mediaFile.length()
            val thumbLen = thumbFile.length()
            if (exp?.expectedEncryptedBytes != null && exp.expectedEncryptedBytes > 0L) {
                require(mediaLen == exp.expectedEncryptedBytes) {
                    "Backup aborted: media asset size mismatch for ${media.title.ifBlank { media.id }}."
                }
            }

            val authSlot = exp?.vaultSlotForAuth
            val actualMediaSha: String
            val thumbSha: String
            if (authSlot != null) {
                val mediaKey = runCatching {
                    org.bouncycastle.util.encoders.Base64.decode(media.fileMediaKeyBase64)
                }.getOrNull()
                require(mediaKey != null && mediaKey.size == CryptoEngine.DEK_BYTES) {
                    "Backup aborted: invalid media encryption key for ${media.title.ifBlank { media.id }}."
                }
                val thumbKey = runCatching {
                    org.bouncycastle.util.encoders.Base64.decode(media.thumbnailKeyBase64)
                }.getOrNull()
                require(thumbKey != null && thumbKey.size == CryptoEngine.DEK_BYTES) {
                    "Backup aborted: invalid thumbnail encryption key for ${media.title.ifBlank { media.id }}."
                }
                try {
                    val report = StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                        encryptedFile = mediaFile,
                        mediaId = media.id,
                        vaultSlot = authSlot,
                        fileMediaKey = mediaKey,
                        isCancelled = isCancelled
                    )
                    require(report.encryptedFileBytes == mediaLen) {
                        "Backup aborted: media size changed during verification for ${media.title.ifBlank { media.id }}."
                    }
                    val expectedChunks = exp.expectedChunkCount ?: media.chunkCount
                    if (expectedChunks > 0) {
                        require(report.header.totalChunks == expectedChunks) {
                            "Backup aborted: media chunk count mismatch for ${media.title.ifBlank { media.id }}."
                        }
                    }
                    if (media.originalSizeBytes > 0L) {
                        require(report.header.totalPlaintextBytes == media.originalSizeBytes) {
                            "Backup aborted: media plaintext size mismatch for ${media.title.ifBlank { media.id }}."
                        }
                    }
                    actualMediaSha = report.ciphertextSha256Hex
                    val (_, verifiedThumbSha) = StreamingMediaCryptoEngine.verifyEncryptedThumbnailFile(
                        thumbFile = thumbFile,
                        mediaId = media.id,
                        vaultSlot = authSlot,
                        thumbKey = thumbKey,
                        isCancelled = isCancelled
                    )
                    thumbSha = verifiedThumbSha
                } finally {
                    Arrays.fill(mediaKey, 0.toByte())
                    Arrays.fill(thumbKey, 0.toByte())
                }
            } else {
                if (exp?.verifyPvm1Integrity == true) {
                    verifyPvm1ContainerStructure(mediaFile, exp.expectedChunkCount ?: media.chunkCount, media.originalSizeBytes)
                }
                actualMediaSha = computeFileSha256(mediaFile, isCancelled)
                thumbSha = computeFileSha256(thumbFile, isCancelled)
            }

            if (media.ciphertextSha256Hex.length == 64) {
                require(actualMediaSha.equals(media.ciphertextSha256Hex, ignoreCase = true)) {
                    "Backup aborted: SHA-256 verification failed for media ${media.title.ifBlank { media.id }}."
                }
            }
            if (!exp?.expectedSha256Hex.isNullOrBlank()) {
                require(actualMediaSha.equals(exp!!.expectedSha256Hex, ignoreCase = true)) {
                    "Backup aborted: authoritative SHA-256 mismatch for media ${media.title.ifBlank { media.id }}."
                }
            }
            val mObj = JSONObject().apply {
                put("mediaId", media.id)
                put("mediaSizeBytes", mediaLen)
                put("thumbSizeBytes", thumbLen)
                put("chunkCount", media.chunkCount.coerceAtLeast(1))
                put("ciphertextSha256", actualMediaSha)
                put("thumbSha256", thumbSha)
            }
            mediaEntriesArray.put(mObj)
            mediaSources.add(
                VerifiedMediaSource(
                    mediaId = media.id,
                    mediaFile = mediaFile,
                    mediaLen = mediaLen,
                    mediaSha256 = actualMediaSha,
                    thumbFile = thumbFile,
                    thumbLen = thumbLen,
                    thumbSha256 = thumbSha,
                    chunkCount = media.chunkCount.coerceAtLeast(1)
                )
            )
        }
        manifestRoot.put("mediaEntries", mediaEntriesArray)

        val docSources = ArrayList<VerifiedDocSource>(documentRecords.size)
        val docEntriesArray = JSONArray()
        for (doc in documentRecords) {
            if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
            val exp = assetExpectations[doc.id]
            val (resolvedDocRel, _) = assetPathResolver(doc.id)
            val docRel = exp?.relativeMediaOrDocPath ?: resolvedDocRel

            val docCandidates = buildList {
                if (!docRel.isNullOrBlank()) add(File(appFilesDir, docRel))
                add(File(appFilesDir, "documents/${doc.id}.pvdoc"))
                add(File(appFilesDir, "docs/${doc.id}.pvdoc"))
                add(File(appFilesDir, "encrypted_doc_${doc.id}.pvm"))
            }
            val docFile = docCandidates.firstOrNull { it.exists() } ?: docCandidates.first()
            VaultFileSafety.requireWithin(appFilesDir, docFile)
            require(docFile.exists() && docFile.length() > 0L) {
                "Backup aborted: missing encrypted document payload for ${doc.title.ifBlank { doc.id }}."
            }

            val docLen = docFile.length()
            if (exp?.expectedEncryptedBytes != null && exp.expectedEncryptedBytes > 0L) {
                require(docLen == exp.expectedEncryptedBytes) {
                    "Backup aborted: document asset size mismatch for ${doc.title.ifBlank { doc.id }}."
                }
            }

            val docAuthSlot = exp?.vaultSlotForAuth
            val actualDocSha: String
            if (docAuthSlot != null) {
                val docKey = runCatching {
                    org.bouncycastle.util.encoders.Base64.decode(doc.fileKeyBase64)
                }.getOrNull()
                require(docKey != null && docKey.size == CryptoEngine.DEK_BYTES) {
                    "Backup aborted: invalid document encryption key for ${doc.title.ifBlank { doc.id }}."
                }
                actualDocSha = try {
                    val report = StreamingMediaCryptoEngine.verifyEncryptedMediaFileEveryChunk(
                        encryptedFile = docFile,
                        mediaId = doc.id,
                        vaultSlot = docAuthSlot,
                        fileMediaKey = docKey,
                        isCancelled = isCancelled
                    )
                    require(report.encryptedFileBytes == docLen) {
                        "Backup aborted: document size changed during verification for ${doc.title.ifBlank { doc.id }}."
                    }
                    val expectedChunks = exp.expectedChunkCount ?: doc.chunkCount
                    if (expectedChunks > 0) {
                        require(report.header.totalChunks == expectedChunks) {
                            "Backup aborted: document chunk count mismatch for ${doc.title.ifBlank { doc.id }}."
                        }
                    }
                    if (doc.originalSizeBytes > 0L) {
                        require(report.header.totalPlaintextBytes == doc.originalSizeBytes) {
                            "Backup aborted: document plaintext size mismatch for ${doc.title.ifBlank { doc.id }}."
                        }
                    }
                    report.ciphertextSha256Hex
                } finally {
                    Arrays.fill(docKey, 0.toByte())
                }
            } else {
                if (exp?.verifyPvm1Integrity == true) {
                    verifyPvm1ContainerStructure(docFile, exp.expectedChunkCount ?: doc.chunkCount, doc.originalSizeBytes)
                }
                actualDocSha = computeFileSha256(docFile, isCancelled)
            }

            if (doc.ciphertextSha256Hex.length == 64) {
                require(actualDocSha.equals(doc.ciphertextSha256Hex, ignoreCase = true)) {
                    "Backup aborted: SHA-256 verification failed for document ${doc.title.ifBlank { doc.id }}."
                }
            }
            if (!exp?.expectedSha256Hex.isNullOrBlank()) {
                require(actualDocSha.equals(exp!!.expectedSha256Hex, ignoreCase = true)) {
                    "Backup aborted: authoritative SHA-256 mismatch for document ${doc.title.ifBlank { doc.id }}."
                }
            }

            val dObj = JSONObject().apply {
                put("documentId", doc.id)
                put("docSizeBytes", docLen)
                put("chunkCount", doc.chunkCount.coerceAtLeast(1))
                put("ciphertextSha256", actualDocSha)
            }
            docEntriesArray.put(dObj)
            docSources.add(
                VerifiedDocSource(
                    documentId = doc.id,
                    docFile = docFile,
                    docLen = docLen,
                    docSha256 = actualDocSha,
                    chunkCount = doc.chunkCount.coerceAtLeast(1)
                )
            )
        }
        manifestRoot.put("docEntries", docEntriesArray)

        val manifestPlaintext = manifestRoot.toString().toByteArray(StandardCharsets.UTF_8)
        require(manifestPlaintext.size in 1..MAX_MANIFEST_PLAINTEXT_BYTES) {
            Arrays.fill(manifestPlaintext, 0.toByte())
            "Backup metadata exceeds maximum supported limit (32 MiB)."
        }

        return PreparedBackupExport(
            manifestPlaintext = manifestPlaintext,
            mediaSources = mediaSources,
            docSources = docSources
        )
    }

    private fun verifyPvm1ContainerStructure(file: File, expectedChunkCount: Int, expectedOriginalSize: Long) {
        val headerBuf = ByteArray(StreamingMediaCryptoEngine.HEADER_BYTES)
        FileInputStream(file).use { fis ->
            var read = 0
            while (read < headerBuf.size) {
                val r = fis.read(headerBuf, read, headerBuf.size - read)
                require(r > 0) { "Encrypted asset ${file.name} header is truncated." }
                read += r
            }
        }
        val parsed = StreamingMediaCryptoEngine.parseHeaderBytes(headerBuf)
        if (expectedChunkCount > 0) {
            require(parsed.totalChunks == expectedChunkCount) {
                "Encrypted asset ${file.name} chunk count mismatch."
            }
        }
        if (expectedOriginalSize > 0L) {
            require(parsed.totalPlaintextBytes == expectedOriginalSize) {
                "Encrypted asset ${file.name} plaintext size mismatch."
            }
        }
        val expectedEncryptedLen = StreamingMediaCryptoEngine.HEADER_BYTES.toLong() +
            parsed.totalChunks.toLong() * StreamingMediaCryptoEngine.CHUNK_FRAME_OVERHEAD_BYTES.toLong() +
            parsed.totalPlaintextBytes
        require(file.length() == expectedEncryptedLen) {
            "Encrypted asset ${file.name} container length does not match PVM1 header."
        }
    }

    /**
     * Reads and verifies the encrypted backup manifest (supports both `PVAULTB2` and `PVAULTB3`)
     * without loading media/document payloads into RAM.
     */
    fun decryptAndVerifyManifest(
        inputStream: InputStream,
        backupPasswordChars: CharArray,
        isCancelled: () -> Boolean = { false }
    ): Pair<BackupPayload, KdfParamsAndPayloadEntries> {
        val magic = readExactStreamBytes(inputStream, 8, "Truncated backup header.", isCancelled)
        return when {
            magic.contentEquals(V3_MAGIC_BYTES) -> decryptAndVerifyV3ManifestOnly(inputStream, backupPasswordChars, isCancelled)
            magic.contentEquals(V2_MAGIC_BYTES) -> decryptAndVerifyV2ManifestAfterMagic(inputStream, backupPasswordChars, isCancelled)
            else -> throw IllegalArgumentException("Invalid backup magic header.")
        }
    }

    private fun decryptAndVerifyV2ManifestAfterMagic(
        inputStream: InputStream,
        backupPasswordChars: CharArray,
        isCancelled: () -> Boolean = { false }
    ): Pair<BackupPayload, KdfParamsAndPayloadEntries> {
        val remHeader = readExactStreamBytes(inputStream, HEADER_BYTES - 8, "Truncated V2 backup header.", isCancelled)
        val bb = ByteBuffer.wrap(remHeader)
        val schemaVersion = bb.int
        require(schemaVersion == 2) { "Unsupported backup schema version: $schemaVersion" }

        val kdfParams = CryptoEngine.KdfParams(
            algorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
            memoryKb = bb.int,
            iterations = bb.int,
            parallelism = bb.int
        )
        kdfParams.validateBounds()

        val salt = ByteArray(CryptoEngine.SALT_BYTES).also { bb.get(it) }
        val manifestNonce = ByteArray(CryptoEngine.NONCE_BYTES).also { bb.get(it) }
        val manifestLen = bb.int
        require(manifestLen in 1..MAX_MANIFEST_CIPHERTEXT_BYTES) {
            "Invalid manifest size ($manifestLen bytes)."
        }

        val manifestCiphertext = readExactStreamBytes(inputStream, manifestLen, "Truncated manifest ciphertext.")
        var backupKek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPasswordChars,
            salt = salt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        var decryptedManifest: ByteArray? = null
        try {
            val aad = CryptoEngine.buildBackupAad(2, kdfParams)
            decryptedManifest = try {
                CryptoEngine.decryptAesGcm(backupKek, manifestNonce, manifestCiphertext, aad)
            } catch (tagEx: AEADBadTagException) {
                if (CryptoEngine.hasDistinctLegacyNonNfcForm(backupPasswordChars)) {
                    val fallbackKek = CryptoEngine.deriveKeyArgon2id(
                        passwordChars = backupPasswordChars,
                        salt = salt,
                        params = kdfParams,
                        normalizeUnicodeNfc = false
                    )
                    try {
                        CryptoEngine.decryptAesGcm(fallbackKek, manifestNonce, manifestCiphertext, aad)
                    } finally {
                        Arrays.fill(fallbackKek, 0.toByte())
                    }
                } else {
                    throw tagEx
                }
            }

            val (payload, details) = parseAndValidateManifestBytes(
                manifestPlaintext = decryptedManifest,
                expectedSchemaVersion = 2,
                kdfParams = kdfParams,
                manifestOverheadBytes = HEADER_BYTES.toLong() + manifestLen.toLong(),
                v3OverheadPerAsset = false
            )
            return Pair(payload, details.copy(manifestWireBytes = manifestLen.toLong()))
        } finally {
            Arrays.fill(backupKek, 0.toByte())
            decryptedManifest?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun decryptAndVerifyV3ManifestOnly(
        inputStream: InputStream,
        backupPasswordChars: CharArray,
        isCancelled: () -> Boolean = { false }
    ): Pair<BackupPayload, KdfParamsAndPayloadEntries> {
        val remHeader = readExactStreamBytes(inputStream, HEADER_BYTES_V3 - 8, "Truncated V3 backup header.", isCancelled)
        val bb = ByteBuffer.wrap(remHeader)
        val schemaVersion = bb.int
        require(schemaVersion == 3) { "Unsupported backup schema version: $schemaVersion" }

        val kdfParams = CryptoEngine.KdfParams(
            algorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
            memoryKb = bb.int,
            iterations = bb.int,
            parallelism = bb.int
        )
        kdfParams.validateBounds()
        val salt = ByteArray(CryptoEngine.SALT_BYTES).also { bb.get(it) }

        val chunkReader = V3AuthenticatedChunkReader(
            inputStream = inputStream,
            backupPasswordChars = backupPasswordChars,
            salt = salt,
            kdfParams = kdfParams,
            isCancelled = isCancelled
        )
        return try {
            val manifestLenBuf = chunkReader.readExactBytes(4)
            val manifestLen = ByteBuffer.wrap(manifestLenBuf).int
            require(manifestLen in 1..MAX_MANIFEST_PLAINTEXT_BYTES) {
                "Invalid manifest size ($manifestLen bytes)."
            }
            val manifestPlain = chunkReader.readExactBytes(manifestLen)
            try {
                parseAndValidateManifestBytes(
                    manifestPlaintext = manifestPlain,
                    expectedSchemaVersion = 3,
                    kdfParams = kdfParams,
                    manifestOverheadBytes = HEADER_BYTES_V3.toLong() + 4L + manifestLen.toLong(),
                    v3OverheadPerAsset = true
                )
            } finally {
                Arrays.fill(manifestPlain, 0.toByte())
            }
        } finally {
            chunkReader.close()
        }
    }

    private fun parseAndValidateManifestBytes(
        manifestPlaintext: ByteArray,
        expectedSchemaVersion: Int,
        kdfParams: CryptoEngine.KdfParams,
        manifestOverheadBytes: Long,
        v3OverheadPerAsset: Boolean
    ): Pair<BackupPayload, KdfParamsAndPayloadEntries> {
        val root = JSONObject(String(manifestPlaintext, StandardCharsets.UTF_8))
        val schemaVer = root.optInt("backupSchemaVersion", expectedSchemaVersion)
        require(schemaVer == expectedSchemaVersion) {
            "Backup manifest schema version ($schemaVer) does not match container header ($expectedSchemaVersion)."
        }
        val exportedAt = root.optLong("exportedAtEpochMs", 0L)
        require(exportedAt >= 0L) { "Invalid backup export timestamp." }
        val appVer = root.optString("appVersionName", "2.0.0")
        val appId = root.optString("applicationId", "com.tpeditz.privatevault.v2")
        val scopeStr = root.optString("exportScope", "").trim()
        val parsedScope = if (scopeStr.isNotBlank()) {
            BackupExportScope.values().firstOrNull { it.name == scopeStr }
        } else {
            null
        }
        val exportScope = parsedScope ?: BackupExportScope.ALL_DATA
        val isExportScopeKnown = parsedScope != null

        val recordsArr = root.getJSONArray("records")
        require(recordsArr.length() <= MAX_RECORDS) {
            "Backup record count (${recordsArr.length()}) exceeds maximum supported ($MAX_RECORDS)."
        }

        val items = ArrayList<VaultItem>(recordsArr.length())
        val seenIds = HashSet<String>(recordsArr.length())
        val expectedMediaIds = HashSet<String>()
        val expectedDocIds = HashSet<String>()
        for (i in 0 until recordsArr.length()) {
            val obj = recordsArr.getJSONObject(i)
            val decoded = VaultRecordCodec.decodeFromJsonObject(obj)
            require(decoded.id.isNotBlank()) { "Backup manifest contains record with blank ID." }
            require(seenIds.add(decoded.id)) { "Backup manifest contains duplicate record ID ${decoded.id}." }
            when (decoded) {
                is MediaRecord -> {
                    VaultFileSafety.requireAssetId(decoded.id)
                    require(decoded.chunkCount >= 1) { "Invalid chunk count on media record ${decoded.id}." }
                    expectedMediaIds.add(decoded.id)
                }
                is DocumentRecord -> {
                    VaultFileSafety.requireAssetId(decoded.id)
                    require(decoded.chunkCount >= 1) { "Invalid chunk count on document record ${decoded.id}." }
                    expectedDocIds.add(decoded.id)
                }
                is AuthenticatorRecord -> {
                    require(decoded.digits in 6..8) { "Invalid authenticator digit count in backup." }
                    require(decoded.periodSeconds in 5..300) { "Invalid authenticator period in backup." }
                }
                else -> {}
            }
            items.add(decoded)
        }

        val mediaEntries = root.optJSONArray("mediaEntries")
        val mediaPayloadDescs = mutableListOf<MediaPayloadEntry>()
        val seenMediaEntryIds = HashSet<String>()
        if (mediaEntries != null) {
            for (i in 0 until mediaEntries.length()) {
                val m = mediaEntries.getJSONObject(i)
                val mediaId = m.getString("mediaId")
                VaultFileSafety.requireAssetId(mediaId)
                require(seenMediaEntryIds.add(mediaId)) { "Duplicate media entry $mediaId in backup manifest." }
                val mediaSize = m.getLong("mediaSizeBytes")
                val thumbSize = m.getLong("thumbSizeBytes")
                val chunkCount = m.getInt("chunkCount")
                require(mediaSize > 0L && thumbSize >= 0L && chunkCount >= 1) {
                    "Invalid media asset descriptor for $mediaId."
                }
                val sha = m.getString("ciphertextSha256")
                require(sha.length == 64) { "Invalid SHA-256 digest in media entry $mediaId." }
                mediaPayloadDescs.add(
                    MediaPayloadEntry(
                        mediaId = mediaId,
                        mediaSizeBytes = mediaSize,
                        thumbSizeBytes = thumbSize,
                        chunkCount = chunkCount,
                        ciphertextSha256 = sha,
                        thumbSha256 = m.optString("thumbSha256", "")
                    )
                )
            }
        }

        val docEntries = root.optJSONArray("docEntries")
        val docPayloadDescs = mutableListOf<DocPayloadEntry>()
        val seenDocEntryIds = HashSet<String>()
        if (docEntries != null) {
            for (i in 0 until docEntries.length()) {
                val d = docEntries.getJSONObject(i)
                val docId = d.getString("documentId")
                VaultFileSafety.requireAssetId(docId)
                require(seenDocEntryIds.add(docId)) { "Duplicate document entry $docId in backup manifest." }
                val docSize = d.getLong("docSizeBytes")
                val chunkCount = d.getInt("chunkCount")
                require(docSize > 0L && chunkCount >= 1) {
                    "Invalid document asset descriptor for $docId."
                }
                val sha = d.getString("ciphertextSha256")
                require(sha.length == 64) { "Invalid SHA-256 digest in document entry $docId." }
                docPayloadDescs.add(
                    DocPayloadEntry(
                        documentId = docId,
                        docSizeBytes = docSize,
                        chunkCount = chunkCount,
                        ciphertextSha256 = sha
                    )
                )
            }
        }

        val rawParts = listOf(manifestOverheadBytes) +
            mediaPayloadDescs.flatMap { listOf(it.mediaSizeBytes, it.thumbSizeBytes) } +
            docPayloadDescs.map { it.docSizeBytes }
        val baseTotal = VaultFileSafety.checkedSizeTotal(rawParts, MAX_SUPPORTED_BACKUP_BYTES, "2 GiB")
        if (v3OverheadPerAsset) {
            val plainTotal = VaultFileSafety.checkedSizeTotal(
                listOf(4L, manifestPlaintext.size.toLong()) +
                    mediaPayloadDescs.flatMap { listOf(it.mediaSizeBytes, it.thumbSizeBytes) } +
                    docPayloadDescs.map { it.docSizeBytes },
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )
            val chunks = (plainTotal - 1L) / V3_CHUNK_PLAIN_BYTES.toLong() + 1L
            val fullV3Size = VaultFileSafety.checkedSizeTotal(
                listOf(HEADER_BYTES_V3.toLong(), chunks * V3_FRAME_OVERHEAD_BYTES.toLong(), plainTotal),
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )
            require(fullV3Size <= MAX_SUPPORTED_BACKUP_BYTES) {
                "Backup container exceeds maximum supported size (2 GiB)."
            }
        } else {
            require(baseTotal <= MAX_SUPPORTED_BACKUP_BYTES) {
                "Backup container exceeds maximum supported size (2 GiB)."
            }
        }

        require(seenMediaEntryIds == expectedMediaIds) {
            "Backup manifest media records and media payload descriptors do not match."
        }
        require(seenDocEntryIds == expectedDocIds) {
            "Backup manifest document records and document payload descriptors do not match."
        }

        val payload = BackupPayload(
            exportedAtEpochMs = exportedAt,
            appVersionName = appVer,
            applicationId = appId,
            items = items,
            exportScope = exportScope,
            isExportScopeKnown = isExportScopeKnown
        )
        val details = KdfParamsAndPayloadEntries(
            kdfParams = kdfParams,
            mediaEntries = mediaPayloadDescs,
            docEntries = docPayloadDescs
        )
        return Pair(payload, details)
    }

    /**
     * Inspects, authenticates, and stages all media and document files from either `PVAULTB2`
     * or `PVAULTB3` backup streams into `stagingDir`.
     */
    fun inspectAndStageBackup(
        inputStream: InputStream,
        backupPasswordChars: CharArray,
        stagingDir: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesRead: Long, totalEstimatedBytes: Long) -> Unit = { _, _ -> }
    ): StagedBackupRestore {
        stagingDir.mkdirs()
        try {
            if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
            val magic = readExactStreamBytes(inputStream, 8, "Truncated backup header.")
            return when {
                magic.contentEquals(V3_MAGIC_BYTES) -> inspectAndStageV3BackupAfterMagic(
                    inputStream, backupPasswordChars, stagingDir, isCancelled, onProgress
                )
                magic.contentEquals(V2_MAGIC_BYTES) -> inspectAndStageV2BackupAfterMagic(
                    inputStream, backupPasswordChars, stagingDir, isCancelled, onProgress
                )
                else -> throw IllegalArgumentException("Invalid backup magic header.")
            }
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
    }

    private fun inspectAndStageV2BackupAfterMagic(
        inputStream: InputStream,
        backupPasswordChars: CharArray,
        stagingDir: File,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ): StagedBackupRestore {
        val (payload, details) = decryptAndVerifyV2ManifestAfterMagic(inputStream, backupPasswordChars)
        if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")

        val assetBytes = VaultFileSafety.checkedSizeTotal(
            details.mediaEntries.flatMap { listOf(it.mediaSizeBytes, it.thumbSizeBytes) } +
                details.docEntries.map { it.docSizeBytes },
            MAX_SUPPORTED_BACKUP_BYTES,
            "2 GiB"
        )
        val totalContainerBytes = VaultFileSafety.checkedSizeTotal(
            listOf(HEADER_BYTES.toLong(), details.manifestWireBytes, assetBytes),
            MAX_SUPPORTED_BACKUP_BYTES,
            "2 GiB"
        )

        checkStagingStorage(stagingDir, assetBytes)

        val mediaStagingDir = File(stagingDir, "media").apply { mkdirs() }
        val thumbStagingDir = File(stagingDir, "thumbs").apply { mkdirs() }
        val docStagingDir = File(stagingDir, "docs").apply { mkdirs() }

        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytesRead = HEADER_BYTES.toLong() + details.manifestWireBytes
        onProgress(totalBytesRead, totalContainerBytes)

        try {
            for (mEntry in details.mediaEntries) {
                if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                if (mEntry.mediaSizeBytes > 0L) {
                    val stagedMediaFile = VaultFileSafety.requireWithin(mediaStagingDir, File(mediaStagingDir, "${mEntry.mediaId}.pvmedia"))
                    copyExactBytesAndVerifySha256(
                        inputStream = inputStream,
                        targetFile = stagedMediaFile,
                        byteCount = mEntry.mediaSizeBytes,
                        expectedSha256Hex = mEntry.ciphertextSha256,
                        buffer = buffer,
                        isCancelled = isCancelled,
                        onChunkCopied = { deltaTotal -> onProgress(totalBytesRead + deltaTotal, totalContainerBytes) }
                    )
                    totalBytesRead += mEntry.mediaSizeBytes
                    onProgress(totalBytesRead, totalContainerBytes)
                }
                if (mEntry.thumbSizeBytes > 0L) {
                    if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                    val stagedThumbFile = VaultFileSafety.requireWithin(thumbStagingDir, File(thumbStagingDir, "${mEntry.mediaId}.pvthumb"))
                    if (mEntry.thumbSha256.isNotBlank()) {
                        copyExactBytesAndVerifySha256(
                            inputStream = inputStream,
                            targetFile = stagedThumbFile,
                            byteCount = mEntry.thumbSizeBytes,
                            expectedSha256Hex = mEntry.thumbSha256,
                            buffer = buffer,
                            isCancelled = isCancelled,
                            onChunkCopied = { deltaTotal -> onProgress(totalBytesRead + deltaTotal, totalContainerBytes) }
                        )
                    } else {
                        copyExactBytes(
                            inputStream = inputStream,
                            targetFile = stagedThumbFile,
                            byteCount = mEntry.thumbSizeBytes,
                            buffer = buffer,
                            isCancelled = isCancelled,
                            onChunkCopied = { deltaTotal -> onProgress(totalBytesRead + deltaTotal, totalContainerBytes) }
                        )
                    }
                    totalBytesRead += mEntry.thumbSizeBytes
                    onProgress(totalBytesRead, totalContainerBytes)
                }
            }

            for (dEntry in details.docEntries) {
                if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                if (dEntry.docSizeBytes > 0L) {
                    val stagedDocFile = VaultFileSafety.requireWithin(docStagingDir, File(docStagingDir, "${dEntry.documentId}.pvdoc"))
                    copyExactBytesAndVerifySha256(
                        inputStream = inputStream,
                        targetFile = stagedDocFile,
                        byteCount = dEntry.docSizeBytes,
                        expectedSha256Hex = dEntry.ciphertextSha256,
                        buffer = buffer,
                        isCancelled = isCancelled,
                        onChunkCopied = { deltaTotal -> onProgress(totalBytesRead + deltaTotal, totalContainerBytes) }
                    )
                    totalBytesRead += dEntry.docSizeBytes
                    onProgress(totalBytesRead, totalContainerBytes)
                }
            }

            val trailing = inputStream.read()
            require(trailing == -1) {
                "Corrupted backup: unexpected trailing bytes after declared payloads."
            }
            require(totalBytesRead == totalContainerBytes) {
                "Incomplete backup: read $totalBytesRead of $totalContainerBytes bytes."
            }
            if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
            return StagedBackupRestore(payload = payload, stagingDir = stagingDir)
        } finally {
            Arrays.fill(buffer, 0.toByte())
        }
    }

    private fun inspectAndStageV3BackupAfterMagic(
        inputStream: InputStream,
        backupPasswordChars: CharArray,
        stagingDir: File,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ): StagedBackupRestore {
        val remHeader = readExactStreamBytes(inputStream, HEADER_BYTES_V3 - 8, "Truncated V3 backup header.")
        val bb = ByteBuffer.wrap(remHeader)
        val schemaVersion = bb.int
        require(schemaVersion == 3) { "Unsupported backup schema version: $schemaVersion" }
        val kdfParams = CryptoEngine.KdfParams(
            algorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
            memoryKb = bb.int,
            iterations = bb.int,
            parallelism = bb.int
        )
        kdfParams.validateBounds()
        val salt = ByteArray(CryptoEngine.SALT_BYTES).also { bb.get(it) }

        val chunkReader = V3AuthenticatedChunkReader(
            inputStream = inputStream,
            backupPasswordChars = backupPasswordChars,
            salt = salt,
            kdfParams = kdfParams,
            isCancelled = isCancelled
        )

        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val manifestLenBuf = chunkReader.readExactBytes(4)
            val manifestLen = ByteBuffer.wrap(manifestLenBuf).int
            require(manifestLen in 1..MAX_MANIFEST_PLAINTEXT_BYTES) {
                "Invalid manifest size ($manifestLen bytes)."
            }
            val manifestPlain = chunkReader.readExactBytes(manifestLen)
            val (payload, details) = try {
                parseAndValidateManifestBytes(
                    manifestPlaintext = manifestPlain,
                    expectedSchemaVersion = 3,
                    kdfParams = kdfParams,
                    manifestOverheadBytes = HEADER_BYTES_V3.toLong() + 4L + manifestLen.toLong(),
                    v3OverheadPerAsset = true
                )
            } finally {
                Arrays.fill(manifestPlain, 0.toByte())
            }

            val assetPlainBytes = VaultFileSafety.checkedSizeTotal(
                details.mediaEntries.flatMap { listOf(it.mediaSizeBytes, it.thumbSizeBytes) } +
                    details.docEntries.map { it.docSizeBytes },
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )
            val totalPlainBytes = VaultFileSafety.checkedSizeTotal(
                listOf(4L, manifestLen.toLong(), assetPlainBytes),
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )
            val totalChunks = (totalPlainBytes - 1L) / V3_CHUNK_PLAIN_BYTES.toLong() + 1L
            val totalContainerBytes = VaultFileSafety.checkedSizeTotal(
                listOf(HEADER_BYTES_V3.toLong(), totalChunks * V3_FRAME_OVERHEAD_BYTES.toLong(), totalPlainBytes),
                MAX_SUPPORTED_BACKUP_BYTES,
                "2 GiB"
            )

            checkStagingStorage(stagingDir, assetPlainBytes)
            onProgress(chunkReader.totalRawBytesRead, totalContainerBytes)

            val mediaStagingDir = File(stagingDir, "media").apply { mkdirs() }
            val thumbStagingDir = File(stagingDir, "thumbs").apply { mkdirs() }
            val docStagingDir = File(stagingDir, "docs").apply { mkdirs() }

            for (mEntry in details.mediaEntries) {
                if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                if (mEntry.mediaSizeBytes > 0L) {
                    val stagedMediaFile = VaultFileSafety.requireWithin(mediaStagingDir, File(mediaStagingDir, "${mEntry.mediaId}.pvmedia"))
                    copyExactBytesFromReaderAndVerifySha256(
                        reader = chunkReader,
                        targetFile = stagedMediaFile,
                        byteCount = mEntry.mediaSizeBytes,
                        expectedSha256Hex = mEntry.ciphertextSha256,
                        buffer = buffer,
                        isCancelled = isCancelled,
                        onProgress = { onProgress(chunkReader.totalRawBytesRead, totalContainerBytes) }
                    )
                }
                if (mEntry.thumbSizeBytes > 0L) {
                    if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                    val stagedThumbFile = VaultFileSafety.requireWithin(thumbStagingDir, File(thumbStagingDir, "${mEntry.mediaId}.pvthumb"))
                    copyExactBytesFromReaderAndVerifySha256(
                        reader = chunkReader,
                        targetFile = stagedThumbFile,
                        byteCount = mEntry.thumbSizeBytes,
                        expectedSha256Hex = mEntry.thumbSha256,
                        buffer = buffer,
                        isCancelled = isCancelled,
                        onProgress = { onProgress(chunkReader.totalRawBytesRead, totalContainerBytes) }
                    )
                }
            }

            for (dEntry in details.docEntries) {
                if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                if (dEntry.docSizeBytes > 0L) {
                    val stagedDocFile = VaultFileSafety.requireWithin(docStagingDir, File(docStagingDir, "${dEntry.documentId}.pvdoc"))
                    copyExactBytesFromReaderAndVerifySha256(
                        reader = chunkReader,
                        targetFile = stagedDocFile,
                        byteCount = dEntry.docSizeBytes,
                        expectedSha256Hex = dEntry.ciphertextSha256,
                        buffer = buffer,
                        isCancelled = isCancelled,
                        onProgress = { onProgress(chunkReader.totalRawBytesRead, totalContainerBytes) }
                    )
                }
            }

            chunkReader.finishAndVerifyEof()
            require(chunkReader.totalRawBytesRead == totalContainerBytes) {
                "Corrupted V3 backup size (${chunkReader.totalRawBytesRead} vs expected $totalContainerBytes)."
            }
            onProgress(totalContainerBytes, totalContainerBytes)
            if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
            return StagedBackupRestore(payload = payload, stagingDir = stagingDir)
        } finally {
            Arrays.fill(buffer, 0.toByte())
            chunkReader.close()
        }
    }

    private fun checkStagingStorage(stagingDir: File, assetBytes: Long) {
        val availableSpace = stagingDir.usableSpace
        val requiredSpace = assetBytes + 10L * 1024L * 1024L // 10 MiB safety margin
        if (availableSpace > 0L && availableSpace < requiredSpace) {
            throw IOException(
                "Insufficient device storage to stage backup. Available: ${availableSpace / (1024 * 1024)} MiB, required: ${requiredSpace / (1024 * 1024)} MiB."
            )
        }
    }

    private class V3AuthenticatedChunkReader(
        private val inputStream: InputStream,
        private val backupPasswordChars: CharArray,
        private val salt: ByteArray,
        private val kdfParams: CryptoEngine.KdfParams,
        private val isCancelled: () -> Boolean
    ) {
        private var backupKek: ByteArray = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPasswordChars,
            salt = salt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        private var nextChunkIndex = 0
        private var sawLastChunk = false
        private var currentPlain: ByteArray = ByteArray(0)
        private var currentPos = 0
        private var currentLen = 0
        var totalRawBytesRead: Long = HEADER_BYTES_V3.toLong()
            private set

        private fun readNextChunk(): Boolean {
            if (sawLastChunk) return false
            if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")

            val frameHeader = readExactStreamBytes(inputStream, V3_FRAME_HEADER_BYTES, "Truncated V3 backup chunk header.", isCancelled)
            totalRawBytesRead += V3_FRAME_HEADER_BYTES.toLong()
            val bb = ByteBuffer.wrap(frameHeader)
            val plainLen = bb.int
            val flag = bb.get().toInt()
            require(flag == 0 || flag == 1) { "Invalid V3 chunk finality flag." }
            val isLast = (flag == 1)
            if (isLast) {
                require(plainLen in 1..V3_CHUNK_PLAIN_BYTES) { "Invalid final V3 chunk length ($plainLen)." }
            } else {
                require(plainLen == V3_CHUNK_PLAIN_BYTES) { "Invalid intermediate V3 chunk length ($plainLen)." }
            }
            val nonce = ByteArray(CryptoEngine.NONCE_BYTES).also { bb.get(it) }
            val ciphertext = readExactStreamBytes(inputStream, plainLen + CryptoEngine.GCM_TAG_BYTES, "Truncated V3 backup chunk payload.", isCancelled)
            totalRawBytesRead += ciphertext.size.toLong()

            val aad = buildV3ChunkAad(kdfParams, nextChunkIndex, isLast, plainLen)
            val decrypted = try {
                CryptoEngine.decryptAesGcm(backupKek, nonce, ciphertext, aad)
            } catch (tagEx: AEADBadTagException) {
                if (nextChunkIndex == 0 && CryptoEngine.hasDistinctLegacyNonNfcForm(backupPasswordChars)) {
                    val fallbackKek = CryptoEngine.deriveKeyArgon2id(
                        passwordChars = backupPasswordChars,
                        salt = salt,
                        params = kdfParams,
                        normalizeUnicodeNfc = false
                    )
                    try {
                        val altPlain = CryptoEngine.decryptAesGcm(fallbackKek, nonce, ciphertext, aad)
                        Arrays.fill(backupKek, 0.toByte())
                        backupKek = fallbackKek.copyOf()
                        altPlain
                    } finally {
                        Arrays.fill(fallbackKek, 0.toByte())
                    }
                } else {
                    throw tagEx
                }
            }
            Arrays.fill(currentPlain, 0.toByte())
            currentPlain = decrypted
            currentPos = 0
            currentLen = decrypted.size
            nextChunkIndex++
            if (isLast) sawLastChunk = true
            return true
        }

        fun read(dest: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (currentPos >= currentLen) {
                if (!readNextChunk()) return -1
            }
            val avail = currentLen - currentPos
            val copyLen = minOf(avail, len)
            System.arraycopy(currentPlain, currentPos, dest, off, copyLen)
            currentPos += copyLen
            return copyLen
        }

        fun readExactBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var total = 0
            while (total < count) {
                if (isCancelled()) throw CancellationException("Backup staging cancelled by user.")
                val r = read(out, total, count - total)
                if (r <= 0) throw EOFException("Unexpected end of encrypted V3 backup stream.")
                total += r
            }
            return out
        }

        fun finishAndVerifyEof() {
            require(currentPos == currentLen) {
                "Corrupted V3 backup: unread bytes remain in final chunk."
            }
            if (!sawLastChunk) {
                require(!readNextChunk() && sawLastChunk) {
                    "Truncated V3 backup: missing final chunk marker."
                }
            }
            require(sawLastChunk) { "Truncated V3 backup: missing final chunk." }
            val trailing = inputStream.read()
            require(trailing == -1) { "Corrupted V3 backup: unexpected trailing bytes at end of stream." }
        }

        fun close() {
            Arrays.fill(backupKek, 0.toByte())
            Arrays.fill(currentPlain, 0.toByte())
            currentPlain = ByteArray(0)
            currentPos = 0
            currentLen = 0
        }
    }

    private fun buildV3ChunkAad(
        kdfParams: CryptoEngine.KdfParams,
        chunkIndex: Int,
        isLast: Boolean,
        chunkPlainLen: Int
    ): ByteArray {
        val lastFlag = if (isLast) 1 else 0
        return "PV3-CHK|ver=3|idx=$chunkIndex|last=$lastFlag|len=$chunkPlainLen|m=${kdfParams.memoryKb}|t=${kdfParams.iterations}|p=${kdfParams.parallelism}"
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun readExactStreamBytes(
        inputStream: InputStream,
        count: Int,
        eofMessage: String,
        isCancelled: () -> Boolean = { false }
    ): ByteArray {
        val buf = ByteArray(count)
        var totalRead = 0
        while (totalRead < count) {
            if (isCancelled()) throw CancellationException("Operation cancelled.")
            val r = inputStream.read(buf, totalRead, count - totalRead)
            if (r <= 0) throw EOFException(eofMessage)
            totalRead += r
        }
        return buf
    }

    private fun streamFileWithLiveShaVerification(
        sourceFile: File,
        expectedLength: Long,
        expectedSha256Hex: String,
        outputStream: OutputStream,
        buffer: ByteArray,
        isCancelled: () -> Boolean,
        onBytesProgress: (writtenInFile: Long) -> Unit
    ): Long {
        val md = MessageDigest.getInstance("SHA-256")
        var fileBytes = 0L
        FileInputStream(sourceFile).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                if (isCancelled()) throw CancellationException("Backup export cancelled by user.")
                fileBytes += read.toLong()
                require(fileBytes <= expectedLength) {
                    "Backup assets changed while exporting. Please retry."
                }
                md.update(buffer, 0, read)
                outputStream.write(buffer, 0, read)
                onBytesProgress(fileBytes)
            }
        }
        require(fileBytes == expectedLength) {
            "Backup assets changed while exporting. Please retry."
        }
        val actualSha = md.digest().joinToString("") { "%02x".format(it) }
        require(actualSha.equals(expectedSha256Hex, ignoreCase = true)) {
            "Backup payload integrity changed while exporting ${sourceFile.name}."
        }
        return fileBytes
    }

    private fun copyExactBytesFromReaderAndVerifySha256(
        reader: V3AuthenticatedChunkReader,
        targetFile: File,
        byteCount: Long,
        expectedSha256Hex: String,
        buffer: ByteArray,
        isCancelled: () -> Boolean,
        onProgress: () -> Unit
    ) {
        val md = MessageDigest.getInstance("SHA-256")
        var remaining = byteCount
        try {
            targetFile.outputStream().use { fos ->
                while (remaining > 0L) {
                    if (isCancelled()) throw CancellationException("Operation cancelled.")
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val r = reader.read(buffer, 0, toRead)
                    if (r <= 0) {
                        throw EOFException("Unexpected end of stream while reading ${targetFile.name}. Missing $remaining bytes.")
                    }
                    fos.write(buffer, 0, r)
                    md.update(buffer, 0, r)
                    remaining -= r.toLong()
                    onProgress()
                }
                fos.flush()
            }
            if (expectedSha256Hex.isNotBlank()) {
                val computedHex = md.digest().joinToString("") { "%02x".format(it) }
                require(computedHex.equals(expectedSha256Hex, ignoreCase = true)) {
                    "Payload integrity check failed: SHA-256 mismatch for ${targetFile.name}."
                }
            }
        } catch (t: Throwable) {
            targetFile.delete()
            throw t
        }
    }

    private fun copyExactBytesAndVerifySha256(
        inputStream: InputStream,
        targetFile: File,
        byteCount: Long,
        expectedSha256Hex: String,
        buffer: ByteArray,
        isCancelled: () -> Boolean = { false },
        onChunkCopied: (bytesCopiedForFile: Long) -> Unit = {}
    ) {
        val md = MessageDigest.getInstance("SHA-256")
        var remaining = byteCount
        var copiedForFile = 0L
        try {
            targetFile.outputStream().use { fos ->
                while (remaining > 0L) {
                    if (isCancelled()) throw CancellationException("Operation cancelled.")
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val r = inputStream.read(buffer, 0, toRead)
                    if (r <= 0) {
                        throw EOFException("Unexpected end of stream while reading ${targetFile.name}. Missing $remaining bytes.")
                    }
                    fos.write(buffer, 0, r)
                    md.update(buffer, 0, r)
                    remaining -= r.toLong()
                    copiedForFile += r.toLong()
                    onChunkCopied(copiedForFile)
                }
                fos.flush()
            }
            val computedHex = md.digest().joinToString("") { "%02x".format(it) }
            require(computedHex.equals(expectedSha256Hex, ignoreCase = true)) {
                "Payload integrity check failed: SHA-256 mismatch for ${targetFile.name}."
            }
        } catch (t: Throwable) {
            targetFile.delete()
            throw t
        }
    }

    private fun copyExactBytes(
        inputStream: InputStream,
        targetFile: File,
        byteCount: Long,
        buffer: ByteArray,
        isCancelled: () -> Boolean = { false },
        onChunkCopied: (bytesCopiedForFile: Long) -> Unit = {}
    ) {
        var remaining = byteCount
        var copiedForFile = 0L
        try {
            targetFile.outputStream().use { fos ->
                while (remaining > 0L) {
                    if (isCancelled()) throw CancellationException("Operation cancelled.")
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val r = inputStream.read(buffer, 0, toRead)
                    if (r <= 0) {
                        throw EOFException("Unexpected end of stream while reading ${targetFile.name}. Missing $remaining bytes.")
                    }
                    fos.write(buffer, 0, r)
                    remaining -= r.toLong()
                    copiedForFile += r.toLong()
                    onChunkCopied(copiedForFile)
                }
                fos.flush()
            }
        } catch (t: Throwable) {
            targetFile.delete()
            throw t
        }
    }

    private fun computeFileSha256(file: File, isCancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        try {
            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    if (isCancelled()) throw CancellationException("Operation cancelled.")
                    md.update(buf, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally {
            Arrays.fill(buf, 0.toByte())
        }
    }

    data class MediaPayloadEntry(
        val mediaId: String,
        val mediaSizeBytes: Long,
        val thumbSizeBytes: Long,
        val chunkCount: Int,
        val ciphertextSha256: String,
        val thumbSha256: String = ""
    )

    data class DocPayloadEntry(
        val documentId: String,
        val docSizeBytes: Long,
        val chunkCount: Int,
        val ciphertextSha256: String
    )

    data class KdfParamsAndPayloadEntries(
        val kdfParams: CryptoEngine.KdfParams,
        val mediaEntries: List<MediaPayloadEntry>,
        val docEntries: List<DocPayloadEntry>,
        val manifestWireBytes: Long = 0L
    )
}
