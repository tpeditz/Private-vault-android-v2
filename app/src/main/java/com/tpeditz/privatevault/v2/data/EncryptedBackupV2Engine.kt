package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.AEADBadTagException

/**
 * Modern streaming encrypted backup engine for PRIVATE VAULT V2.
 * Supports up to 2 GiB (2,147,483,648 bytes) container sizes with strictly bounded RAM (< 1 MiB).
 *
 * Binary Layout (`PVAULTB2`):
 * - `[0..7]`   8B Magic `"PVAULTB2"`
 * - `[8..11]`  4B `backupSchemaVersion` (`2`)
 * - `[12..15]` 4B `kdfMemoryKb`
 * - `[16..19]` 4B `kdfIterations`
 * - `[20..23]` 4B `kdfParallelism`
 * - `[24..39]` 16B `backupKdfSalt`
 * - `[40..51]` 12B `backupManifestNonce`
 * - `[52..55]` 4B `manifestCiphertextLength`
 * - `[56..56 + M]` AES-256-GCM encrypted metadata manifest
 * - `[Remainder]` Sequentially framed encrypted media and document file payloads
 */
object EncryptedBackupV2Engine {

    val V2_MAGIC_BYTES = "PVAULTB2".toByteArray(StandardCharsets.US_ASCII)
    const val HEADER_BYTES = 56
    const val MAX_SUPPORTED_BACKUP_BYTES = 2L * 1024L * 1024L * 1024L // 2 GiB target
    const val MAX_RECORDS = 50_000
    private const val BUFFER_SIZE = 64 * 1024 // 64 KiB streaming buffer

    fun isV2Backup(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < V2_MAGIC_BYTES.size) return false
        val slice = headerBytes.copyOfRange(0, V2_MAGIC_BYTES.size)
        return slice.contentEquals(V2_MAGIC_BYTES)
    }

    /**
     * Streams an encrypted V2 backup directly to `outputStream`.
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
        onProgress: (bytesWritten: Long, totalEstimatedBytes: Long) -> Unit = { _, _ -> }
    ) {
        require(backupPasswordChars.isNotEmpty()) { "Backup password cannot be empty." }
        require(items.size <= MAX_RECORDS) { "Record count exceeds maximum supported." }
        kdfParams.validateBounds()
        if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")

        val salt = CryptoEngine.generateSalt()
        val manifestNonce = CryptoEngine.generateNonce()
        val backupKek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPasswordChars,
            salt = salt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")

        try {
            val manifestRoot = JSONObject()
            manifestRoot.put("backupSchemaVersion", 2)
            manifestRoot.put("exportedAtEpochMs", exportedAtEpochMs)
            manifestRoot.put("appVersionName", appVersionName)
            manifestRoot.put("applicationId", "com.tpeditz.privatevault.v2")

            val recordsArray = JSONArray()
            val mediaRecords = mutableListOf<MediaRecord>()
            val documentRecords = mutableListOf<DocumentRecord>()

            items.forEach { item ->
                when (item) {
                    is MediaRecord -> mediaRecords.add(item)
                    is DocumentRecord -> documentRecords.add(item)
                    else -> {}
                }
                val encodedBytes = VaultRecordCodec.encode(item)
                val itemJson = JSONObject(String(encodedBytes, StandardCharsets.UTF_8))
                recordsArray.put(itemJson)
            }
            manifestRoot.put("records", recordsArray)

            var totalEstimatedBytes = HEADER_BYTES.toLong()

            val mediaEntriesArray = JSONArray()
            val mediaFileList = mutableListOf<Pair<File, File>>()
            for (media in mediaRecords) {
                val (resolvedMediaRel, resolvedThumbRel) = assetPathResolver(media.id)
                val mediaCandidates = buildList {
                    if (!resolvedMediaRel.isNullOrBlank()) {
                        add(File(appFilesDir, resolvedMediaRel))
                    }
                    add(File(appFilesDir, "media/${media.id}.pvmedia"))
                    add(File(appFilesDir, "encrypted_media_${media.id}.pvm"))
                }
                val mediaFile = mediaCandidates.firstOrNull { it.exists() } ?: mediaCandidates.first()
                require(VaultFileSafety.isWithin(appFilesDir, mediaFile)) {
                    "Backup aborted: media asset path escapes private directory."
                }
                require(mediaFile.exists() && mediaFile.length() > 0L) {
                    "Backup aborted: missing encrypted media payload for ${media.title.ifBlank { media.id }}."
                }

                val thumbCandidates = buildList {
                    if (!resolvedThumbRel.isNullOrBlank()) {
                        add(File(appFilesDir, resolvedThumbRel))
                    }
                    add(File(appFilesDir, "thumbs/${media.id}.pvthumb"))
                    add(File(appFilesDir, "encrypted_thumb_${media.id}.pvt"))
                }
                val thumbFile = thumbCandidates.firstOrNull { it.exists() } ?: thumbCandidates.first()
                require(VaultFileSafety.isWithin(appFilesDir, thumbFile)) {
                    "Backup aborted: thumbnail asset path escapes private directory."
                }
                require(thumbFile.exists() && thumbFile.length() > 0L) {
                    "Backup aborted: missing encrypted thumbnail payload for ${media.title.ifBlank { media.id }}."
                }

                val mediaLen = mediaFile.length()
                val thumbLen = thumbFile.length()
                val thumbSha = computeFileSha256(thumbFile)

                val mObj = JSONObject()
                mObj.put("mediaId", media.id)
                mObj.put("mediaSizeBytes", mediaLen)
                mObj.put("thumbSizeBytes", thumbLen)
                mObj.put("chunkCount", media.chunkCount)
                mObj.put("ciphertextSha256", media.ciphertextSha256Hex)
                mObj.put("thumbSha256", thumbSha)
                mediaEntriesArray.put(mObj)

                mediaFileList.add(Pair(mediaFile, thumbFile))
                totalEstimatedBytes += mediaLen + thumbLen
            }
            manifestRoot.put("mediaEntries", mediaEntriesArray)

            val docEntriesArray = JSONArray()
            val docFileList = mutableListOf<File>()
            for (doc in documentRecords) {
                val (resolvedDocRel, _) = assetPathResolver(doc.id)
                val docCandidates = buildList {
                    if (!resolvedDocRel.isNullOrBlank()) {
                        add(File(appFilesDir, resolvedDocRel))
                    }
                    add(File(appFilesDir, "documents/${doc.id}.pvdoc"))
                    add(File(appFilesDir, "docs/${doc.id}.pvdoc"))
                    add(File(appFilesDir, "encrypted_doc_${doc.id}.pvm"))
                }
                val docFile = docCandidates.firstOrNull { it.exists() } ?: docCandidates.first()
                require(VaultFileSafety.isWithin(appFilesDir, docFile)) {
                    "Backup aborted: document asset path escapes private directory."
                }
                require(docFile.exists() && docFile.length() > 0L) {
                    "Backup aborted: missing encrypted document payload for ${doc.title.ifBlank { doc.id }}."
                }

                val docLen = docFile.length()

                val dObj = JSONObject()
                dObj.put("documentId", doc.id)
                dObj.put("docSizeBytes", docLen)
                dObj.put("chunkCount", doc.chunkCount)
                dObj.put("ciphertextSha256", doc.ciphertextSha256Hex)
                docEntriesArray.put(dObj)

                docFileList.add(docFile)
                totalEstimatedBytes += docLen
            }
            manifestRoot.put("docEntries", docEntriesArray)

            val manifestPlaintext = manifestRoot.toString().toByteArray(StandardCharsets.UTF_8)
            val manifestAad = CryptoEngine.buildBackupAad(2, kdfParams)
            val manifestCiphertext = CryptoEngine.encryptAesGcm(
                keyBytes = backupKek,
                nonce = manifestNonce,
                plaintext = manifestPlaintext,
                aad = manifestAad
            )

            totalEstimatedBytes += manifestCiphertext.size.toLong()
            require(totalEstimatedBytes <= MAX_SUPPORTED_BACKUP_BYTES) {
                "Backup container exceeds maximum supported size (2 GiB). Estimated: $totalEstimatedBytes bytes."
            }

            // Write 56-byte V2 Header
            val headerBytes = ByteArray(HEADER_BYTES)
            ByteBuffer.wrap(headerBytes).apply {
                put(V2_MAGIC_BYTES)
                putInt(2) // schema version 2
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

            // Stream media files
            for ((mediaFile, thumbFile) in mediaFileList) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")
                if (mediaFile.exists()) {
                    FileInputStream(mediaFile).use { fis ->
                        var read: Int
                        while (fis.read(streamBuffer).also { read = it } != -1) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")
                            outputStream.write(streamBuffer, 0, read)
                            totalBytesWritten += read.toLong()
                            onProgress(totalBytesWritten, totalEstimatedBytes)
                        }
                    }
                }
                if (thumbFile.exists()) {
                    FileInputStream(thumbFile).use { fis ->
                        var read: Int
                        while (fis.read(streamBuffer).also { read = it } != -1) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")
                            outputStream.write(streamBuffer, 0, read)
                            totalBytesWritten += read.toLong()
                            onProgress(totalBytesWritten, totalEstimatedBytes)
                        }
                    }
                }
            }

            // Stream document files
            for (docFile in docFileList) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")
                if (docFile.exists()) {
                    FileInputStream(docFile).use { fis ->
                        var read: Int
                        while (fis.read(streamBuffer).also { read = it } != -1) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException("Backup export cancelled by user.")
                            outputStream.write(streamBuffer, 0, read)
                            totalBytesWritten += read.toLong()
                            onProgress(totalBytesWritten, totalEstimatedBytes)
                        }
                    }
                }
            }
            require(totalBytesWritten == totalEstimatedBytes) { "Backup assets changed while exporting. Please retry." }
            outputStream.flush()
        } finally {
            Arrays.fill(backupKek, 0.toByte())
        }
    }

    /**
     * Reads and verifies the V2 backup manifest without loading large files into RAM.
     */
    fun decryptAndVerifyManifest(
        inputStream: InputStream,
        backupPasswordChars: CharArray
    ): Pair<BackupPayload, KdfParamsAndPayloadEntries> {
        val headerBuf = ByteArray(HEADER_BYTES)
        var totalRead = 0
        while (totalRead < HEADER_BYTES) {
            val r = inputStream.read(headerBuf, totalRead, HEADER_BYTES - totalRead)
            require(r > 0) { "Truncated V2 backup header." }
            totalRead += r
        }

        val bb = ByteBuffer.wrap(headerBuf)
        val magic = ByteArray(V2_MAGIC_BYTES.size)
        bb.get(magic)
        require(magic.contentEquals(V2_MAGIC_BYTES)) { "Invalid V2 magic header." }

        val schemaVersion = bb.int
        require(schemaVersion == 2) { "Unsupported backup schema version: $schemaVersion" }

        val memoryKb = bb.int
        val iterations = bb.int
        val parallelism = bb.int

        val kdfParams = CryptoEngine.KdfParams(
            algorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
            memoryKb = memoryKb,
            iterations = iterations,
            parallelism = parallelism
        )
        kdfParams.validateBounds()

        val salt = ByteArray(CryptoEngine.SALT_BYTES)
        bb.get(salt)

        val manifestNonce = ByteArray(CryptoEngine.NONCE_BYTES)
        bb.get(manifestNonce)

        val manifestLen = bb.int
        require(manifestLen in 1..32 * 1024 * 1024) { "Invalid manifest size ($manifestLen bytes)." }

        val manifestCiphertext = ByteArray(manifestLen)
        var manifestRead = 0
        while (manifestRead < manifestLen) {
            val r = inputStream.read(manifestCiphertext, manifestRead, manifestLen - manifestRead)
            require(r > 0) { "Truncated manifest ciphertext." }
            manifestRead += r
        }

        val backupKek = CryptoEngine.deriveKeyArgon2id(
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

            val root = JSONObject(String(decryptedManifest, StandardCharsets.UTF_8))
            val exportedAt = root.optLong("exportedAtEpochMs", 0L)
            val appVer = root.optString("appVersionName", "2.0.0")
            val appId = root.optString("applicationId", "com.tpeditz.privatevault.v2")

            val recordsArr = root.getJSONArray("records")
            val items = ArrayList<VaultItem>(recordsArr.length())
            for (i in 0 until recordsArr.length()) {
                val obj = recordsArr.getJSONObject(i)
                items.add(VaultRecordCodec.decodeFromJsonObject(obj))
            }

            val mediaEntries = root.optJSONArray("mediaEntries")
            val mediaPayloadDescs = mutableListOf<MediaPayloadEntry>()
            if (mediaEntries != null) {
                for (i in 0 until mediaEntries.length()) {
                    val m = mediaEntries.getJSONObject(i)
                    VaultFileSafety.requireAssetId(m.getString("mediaId"))
                    mediaPayloadDescs.add(
                        MediaPayloadEntry(
                            mediaId = m.getString("mediaId"),
                            mediaSizeBytes = m.getLong("mediaSizeBytes"),
                            thumbSizeBytes = m.getLong("thumbSizeBytes"),
                            chunkCount = m.getInt("chunkCount"),
                            ciphertextSha256 = m.getString("ciphertextSha256"),
                            thumbSha256 = m.optString("thumbSha256", "")
                        )
                    )
                }
            }

            val docEntries = root.optJSONArray("docEntries")
            val docPayloadDescs = mutableListOf<DocPayloadEntry>()
            if (docEntries != null) {
                for (i in 0 until docEntries.length()) {
                    val d = docEntries.getJSONObject(i)
                    VaultFileSafety.requireAssetId(d.getString("documentId"))
                    docPayloadDescs.add(
                        DocPayloadEntry(
                            documentId = d.getString("documentId"),
                            docSizeBytes = d.getLong("docSizeBytes"),
                            chunkCount = d.getInt("chunkCount"),
                            ciphertextSha256 = d.getString("ciphertextSha256")
                        )
                    )
                }
            }

            val payload = BackupPayload(
                exportedAtEpochMs = exportedAt,
                appVersionName = appVer,
                applicationId = appId,
                items = items
            )

            val details = KdfParamsAndPayloadEntries(
                kdfParams = kdfParams,
                mediaEntries = mediaPayloadDescs,
                docEntries = docPayloadDescs
            )

            val totalDeclaredPayloadBytes = VaultFileSafety.checkedSizeTotal(
                listOf(HEADER_BYTES.toLong(), manifestLen.toLong()) +
                    mediaPayloadDescs.flatMap { listOf(it.mediaSizeBytes, it.thumbSizeBytes) } +
                    docPayloadDescs.map { it.docSizeBytes }, MAX_SUPPORTED_BACKUP_BYTES, "2 GiB"
            )
            require(totalDeclaredPayloadBytes <= MAX_SUPPORTED_BACKUP_BYTES) {
                "Backup container exceeds maximum supported size (2 GiB). Declared: $totalDeclaredPayloadBytes bytes."
            }

            return Pair(payload, details)
        } finally {
            Arrays.fill(backupKek, 0.toByte())
            decryptedManifest?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Inspects, authenticates, and stages all media and document files from the backup stream.
     * Peak memory is strictly bounded (< 1 MiB) regardless of container size.
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
            val (payload, details) = decryptAndVerifyManifest(inputStream, backupPasswordChars)

            val totalExpectedPayloadBytes = VaultFileSafety.checkedSizeTotal(
                listOf(HEADER_BYTES.toLong()) + details.mediaEntries.flatMap { listOf(it.mediaSizeBytes, it.thumbSizeBytes) } +
                    details.docEntries.map { it.docSizeBytes }, MAX_SUPPORTED_BACKUP_BYTES, "2 GiB"
            )

            require(totalExpectedPayloadBytes <= MAX_SUPPORTED_BACKUP_BYTES) {
                "Backup container exceeds maximum supported size (2 GiB)."
            }

            val availableSpace = stagingDir.usableSpace
            val requiredSpace = totalExpectedPayloadBytes + 10L * 1024L * 1024L // 10 MiB safety margin
            if (availableSpace < requiredSpace) {
                throw java.io.IOException(
                    "Insufficient device storage to stage backup. Available: ${availableSpace / (1024 * 1024)} MiB, required: ${requiredSpace / (1024 * 1024)} MiB."
                )
            }

            val mediaStagingDir = File(stagingDir, "media").apply { mkdirs() }
            val thumbStagingDir = File(stagingDir, "thumbs").apply { mkdirs() }
            val docStagingDir = File(stagingDir, "docs").apply { mkdirs() }

            val buffer = ByteArray(BUFFER_SIZE)
            var totalBytesRead = HEADER_BYTES.toLong()

            // Read and stage each media file and thumbnail
            for (mEntry in details.mediaEntries) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Backup staging cancelled by user.")
                if (mEntry.mediaSizeBytes > 0L) {
                    val stagedMediaFile = VaultFileSafety.requireWithin(mediaStagingDir, File(mediaStagingDir, "${mEntry.mediaId}.pvmedia"))
                    copyExactBytesAndVerifySha256(
                        inputStream = inputStream,
                        targetFile = stagedMediaFile,
                        byteCount = mEntry.mediaSizeBytes,
                        expectedSha256Hex = mEntry.ciphertextSha256,
                        buffer = buffer,
                        isCancelled = isCancelled
                    )
                    totalBytesRead += mEntry.mediaSizeBytes
                    onProgress(totalBytesRead, totalExpectedPayloadBytes)
                }
                if (mEntry.thumbSizeBytes > 0L) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException("Backup staging cancelled by user.")
                    val stagedThumbFile = VaultFileSafety.requireWithin(thumbStagingDir, File(thumbStagingDir, "${mEntry.mediaId}.pvthumb"))
                    if (mEntry.thumbSha256.isNotBlank()) {
                        copyExactBytesAndVerifySha256(
                            inputStream = inputStream,
                            targetFile = stagedThumbFile,
                            byteCount = mEntry.thumbSizeBytes,
                            expectedSha256Hex = mEntry.thumbSha256,
                            buffer = buffer,
                            isCancelled = isCancelled
                        )
                    } else {
                        copyExactBytes(
                            inputStream = inputStream,
                            targetFile = stagedThumbFile,
                            byteCount = mEntry.thumbSizeBytes,
                            buffer = buffer,
                            isCancelled = isCancelled
                        )
                    }
                    totalBytesRead += mEntry.thumbSizeBytes
                    onProgress(totalBytesRead, totalExpectedPayloadBytes)
                }
            }

            // Read and stage each document file
            for (dEntry in details.docEntries) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Backup staging cancelled by user.")
                if (dEntry.docSizeBytes > 0L) {
                    val stagedDocFile = VaultFileSafety.requireWithin(docStagingDir, File(docStagingDir, "${dEntry.documentId}.pvdoc"))
                    copyExactBytesAndVerifySha256(
                        inputStream = inputStream,
                        targetFile = stagedDocFile,
                        byteCount = dEntry.docSizeBytes,
                        expectedSha256Hex = dEntry.ciphertextSha256,
                        buffer = buffer,
                        isCancelled = isCancelled
                    )
                    totalBytesRead += dEntry.docSizeBytes
                    onProgress(totalBytesRead, totalExpectedPayloadBytes)
                }
            }

            return StagedBackupRestore(payload = payload, stagingDir = stagingDir)
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
    }

    private fun copyExactBytesAndVerifySha256(
        inputStream: InputStream,
        targetFile: File,
        byteCount: Long,
        expectedSha256Hex: String,
        buffer: ByteArray,
        isCancelled: () -> Boolean = { false }
    ) {
        val md = MessageDigest.getInstance("SHA-256")
        var remaining = byteCount
        try {
            FileOutputStream(targetFile).use { fos ->
                while (remaining > 0L) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException("Operation cancelled.")
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val r = inputStream.read(buffer, 0, toRead)
                    if (r <= 0) {
                        throw java.io.EOFException("Unexpected end of stream while reading ${targetFile.name}. Missing $remaining bytes.")
                    }
                    fos.write(buffer, 0, r)
                    md.update(buffer, 0, r)
                    remaining -= r.toLong()
                }
            }
            val computedHex = md.digest().joinToString("") { "%02x".format(it) }
            require(computedHex.equals(expectedSha256Hex, ignoreCase = true)) {
                targetFile.delete()
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
        isCancelled: () -> Boolean = { false }
    ) {
        var remaining = byteCount
        try {
            FileOutputStream(targetFile).use { fos ->
                while (remaining > 0L) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException("Operation cancelled.")
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val r = inputStream.read(buffer, 0, toRead)
                    if (r <= 0) {
                        throw java.io.EOFException("Unexpected end of stream while reading ${targetFile.name}. Missing $remaining bytes.")
                    }
                    fos.write(buffer, 0, r)
                    remaining -= r.toLong()
                }
            }
        } catch (t: Throwable) {
            targetFile.delete()
            throw t
        }
    }

    private fun computeFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
        val docEntries: List<DocPayloadEntry>
    )
}
