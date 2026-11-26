package com.tpeditz.privatevault.v2.data

import com.tpeditz.privatevault.v2.security.CryptoEngine
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.bouncycastle.util.encoders.Base64
import javax.crypto.AEADBadTagException

/**
 * 100% backward-compatible reader for legacy V1 `.pvault` backups produced by Private Vault 1.0.x.
 * Correctly parses `PVAULTB1` containers, legacy Unicode passwords, notes, credentials, folders,
 * and legacy embedded media blobs.
 */
object LegacyV1BackupReader {

    val V1_MAGIC_BYTES = "PVAULTB1".toByteArray(StandardCharsets.US_ASCII)
    const val HEADER_BYTES = 52
    const val MAX_V1_BACKUP_BYTES = 32 * 1024 * 1024 // 32 MiB ceiling of old container
    const val MAX_V1_RECORDS = 50_000

    fun isLegacyV1Backup(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < V1_MAGIC_BYTES.size) return false
        val slice = headerBytes.copyOfRange(0, V1_MAGIC_BYTES.size)
        return slice.contentEquals(V1_MAGIC_BYTES)
    }

    @Throws(
        IllegalArgumentException::class,
        AEADBadTagException::class
    )
    fun decryptAndVerifyLegacyV1Backup(
        backupBytes: ByteArray,
        backupPasswordChars: CharArray
    ): BackupPayload {
        require(backupPasswordChars.isNotEmpty()) { "Backup password is required." }
        require(backupBytes.size >= HEADER_BYTES + CryptoEngine.GCM_TAG_BYTES) {
            "Invalid or truncated legacy .pvault backup file."
        }
        require(backupBytes.size <= MAX_V1_BACKUP_BYTES) {
            "Legacy backup file exceeds maximum supported V1 container size (32 MiB)."
        }

        val buffer = ByteBuffer.wrap(backupBytes)
        val fileMagic = ByteArray(V1_MAGIC_BYTES.size)
        buffer.get(fileMagic)
        require(fileMagic.contentEquals(V1_MAGIC_BYTES)) {
            "Unrecognized file header. Expected PVAULTB1 legacy format."
        }

        val schemaVersion = buffer.int
        require(schemaVersion == 1) {
            "Unsupported legacy backup schema version: $schemaVersion"
        }

        val memoryKb = buffer.int
        val iterations = buffer.int
        val parallelism = buffer.int

        val kdfParams = CryptoEngine.KdfParams(
            algorithm = CryptoEngine.KDF_ALGORITHM_ARGON2ID,
            memoryKb = memoryKb,
            iterations = iterations,
            parallelism = parallelism
        )
        kdfParams.validateBounds()

        val salt = ByteArray(CryptoEngine.SALT_BYTES)
        buffer.get(salt)

        val nonce = ByteArray(CryptoEngine.NONCE_BYTES)
        buffer.get(nonce)

        val ciphertextLength = backupBytes.size - HEADER_BYTES
        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)

        val aad = CryptoEngine.buildBackupAad(schemaVersion, kdfParams)
        var backupKek = CryptoEngine.deriveKeyArgon2id(
            passwordChars = backupPasswordChars,
            salt = salt,
            params = kdfParams,
            normalizeUnicodeNfc = true
        )
        var decryptedBytes = ByteArray(0)
        try {
            decryptedBytes = try {
                CryptoEngine.decryptAesGcm(
                    keyBytes = backupKek,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    aad = aad
                )
            } catch (tagEx: AEADBadTagException) {
                if (CryptoEngine.hasDistinctLegacyNonNfcForm(backupPasswordChars)) {
                    Arrays.fill(backupKek, 0.toByte())
                    backupKek = CryptoEngine.deriveKeyArgon2id(
                        passwordChars = backupPasswordChars,
                        salt = salt,
                        params = kdfParams,
                        normalizeUnicodeNfc = false
                    )
                    CryptoEngine.decryptAesGcm(
                        keyBytes = backupKek,
                        nonce = nonce,
                        ciphertext = ciphertext,
                        aad = aad
                    )
                } else {
                    throw tagEx
                }
            }

            val root = try {
                JSONObject(String(decryptedBytes, StandardCharsets.UTF_8))
            } catch (_: Exception) {
                throw IllegalArgumentException("Malformed legacy backup payload: invalid JSON structure.")
            }

            val exportedAtEpochMs = root.optLong("exportedAtEpochMs", 0L)
            require(exportedAtEpochMs >= 0L) { "Invalid legacy backup timestamp." }
            val appVersionName = root.optString("appVersionName", "1.0.x")

            val recordsArray = root.optJSONArray("records")
                ?: throw IllegalArgumentException("Legacy backup payload is missing \"records\" array.")
            require(recordsArray.length() <= MAX_V1_RECORDS) {
                "Legacy backup record count exceeds maximum supported."
            }

            val items = ArrayList<VaultItem>(recordsArray.length())
            val seenIds = HashSet<String>(recordsArray.length())
            for (i in 0 until recordsArray.length()) {
                val recordObj = recordsArray.optJSONObject(i)
                    ?: throw IllegalArgumentException("Malformed record entry in legacy backup payload.")
                val decoded = VaultRecordCodec.decodeFromJsonObject(recordObj)
                require(decoded.id.isNotBlank()) { "Legacy backup record is missing required ID." }
                require(seenIds.add(decoded.id)) { "Legacy backup contains duplicate record ID ${decoded.id}." }
                items.add(decoded)
            }

            val mediaPayloads = HashMap<String, EmbeddedMediaPayload>()
            val blobsArray = root.optJSONArray("mediaBlobs")
            if (blobsArray != null) {
                for (i in 0 until blobsArray.length()) {
                    val blobObj = blobsArray.optJSONObject(i) ?: continue
                    val mediaId = blobObj.getString("mediaId")
                    val formatVer = blobObj.optInt("formatVersion", 1)
                    val chunkCount = blobObj.optInt("chunkCount", 1)
                    val shaHex = blobObj.optString("ciphertextSha256", "")
                    val mediaBytes = Base64.decode(blobObj.getString("mediaCiphertextBase64"))
                    val thumbBytes = Base64.decode(blobObj.getString("thumbCiphertextBase64"))
                    mediaPayloads[mediaId] = EmbeddedMediaPayload(
                        mediaId = mediaId,
                        formatVersion = formatVer,
                        chunkCount = chunkCount,
                        ciphertextSha256 = shaHex,
                        mediaCiphertextBytes = mediaBytes,
                        thumbCiphertextBytes = thumbBytes
                    )
                }
            } else {
                val blobsObj = root.optJSONObject("mediaBlobs")
                if (blobsObj != null) {
                    val keys = blobsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val blobObj = blobsObj.optJSONObject(key) ?: continue
                        val mediaId = blobObj.optString("mediaId", key)
                        val formatVer = blobObj.optInt("formatVersion", 1)
                        val chunkCount = blobObj.optInt("chunkCount", 1)
                        val shaHex = blobObj.optString("ciphertextSha256", "")
                        val mediaBytes = Base64.decode(blobObj.getString("mediaCiphertextBase64"))
                        val thumbBytes = Base64.decode(blobObj.getString("thumbCiphertextBase64"))
                        mediaPayloads[mediaId] = EmbeddedMediaPayload(
                            mediaId = mediaId,
                            formatVersion = formatVer,
                            chunkCount = chunkCount,
                            ciphertextSha256 = shaHex,
                            mediaCiphertextBytes = mediaBytes,
                            thumbCiphertextBytes = thumbBytes
                        )
                    }
                }
            }

            // Validate 1-to-1 correspondence between authentic MediaRecords and embedded media blobs.
            // Never invent missing keys: all restored media must possess authentic encryption metadata.
            val mediaRecords = items.filterIsInstance<MediaRecord>()
            val mediaRecordIds = mediaRecords.map { it.id }.toSet()
            val mediaBlobIds = mediaPayloads.keys

            for (media in mediaRecords) {
                val blob = mediaPayloads[media.id]
                    ?: throw IllegalArgumentException("Legacy backup is missing media payload blob for record ${media.id}.")
                require(blob.mediaCiphertextBytes.isNotEmpty()) {
                    "Legacy backup media payload for ${media.id} is empty."
                }
                if (media.ciphertextSha256Hex.length == 64) {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val computedSha = md.digest(blob.mediaCiphertextBytes).joinToString("") { "%02x".format(it) }
                    require(computedSha.equals(media.ciphertextSha256Hex, ignoreCase = true)) {
                        "Legacy backup media payload for ${media.id} failed SHA-256 verification."
                    }
                }
                require(media.fileMediaKeyBase64.isNotBlank() && media.thumbnailKeyBase64.isNotBlank()) {
                    "Legacy media record ${media.id} is missing authentic encryption metadata."
                }
                val decodedMediaKey = runCatching { Base64.decode(media.fileMediaKeyBase64) }.getOrNull()
                require(decodedMediaKey != null && decodedMediaKey.size == 32) {
                    "Legacy media record ${media.id} contains invalid 256-bit media encryption key."
                }
                val decodedThumbKey = runCatching { Base64.decode(media.thumbnailKeyBase64) }.getOrNull()
                require(decodedThumbKey != null && decodedThumbKey.size == 32) {
                    "Legacy media record ${media.id} contains invalid 256-bit thumbnail encryption key."
                }
            }

            for (blobId in mediaBlobIds) {
                require(mediaRecordIds.contains(blobId)) {
                    "Legacy backup rejected: contains orphaned media blob $blobId without authentic record metadata."
                }
            }

            return BackupPayload(
                exportedAtEpochMs = exportedAtEpochMs,
                appVersionName = appVersionName,
                applicationId = "com.tpeditz.privatevault",
                items = items,
                mediaPayloads = mediaPayloads,
                exportScope = BackupExportScope.ALL_DATA,
                isExportScopeKnown = false
            )
        } finally {
            Arrays.fill(backupKek, 0.toByte())
            if (decryptedBytes.isNotEmpty()) {
                Arrays.fill(decryptedBytes, 0.toByte())
            }
        }
    }

    /**
     * Inspects a legacy V1 backup from an InputStream, enforces 32 MiB ceiling before full allocation,
     * decrypts, and stages any embedded media blobs into `stagingDir`.
     */
    fun inspectAndStageLegacyBackup(
        inputStream: java.io.InputStream,
        backupPasswordChars: CharArray,
        stagingDir: java.io.File,
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): StagedBackupRestore {
        stagingDir.mkdirs()
        try {
            if (isCancelled()) throw java.util.concurrent.CancellationException("Restore cancelled by user.")
            val baos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var totalRead = 0
            var r: Int
            while (inputStream.read(buf).also { r = it } != -1) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Restore cancelled by user.")
                totalRead += r
                require(totalRead <= MAX_V1_BACKUP_BYTES) {
                    "Legacy backup file exceeds maximum supported V1 container size (32 MiB)."
                }
                baos.write(buf, 0, r)
            }
            val backupBytes = baos.toByteArray()
            val payload = decryptAndVerifyLegacyV1Backup(backupBytes, backupPasswordChars)

            val mediaStagingDir = java.io.File(stagingDir, "media").apply { mkdirs() }
            val thumbStagingDir = java.io.File(stagingDir, "thumbs").apply { mkdirs() }

            val totalAssetBytes = payload.mediaPayloads.values.sumOf {
                it.mediaCiphertextBytes.size.toLong() + it.thumbCiphertextBytes.size.toLong()
            }
            var stagedBytes = 0L
            for ((mediaId, blob) in payload.mediaPayloads) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Restore cancelled by user.")
                VaultFileSafety.requireAssetId(mediaId)
                if (blob.mediaCiphertextBytes.isNotEmpty()) {
                    val mediaFile = VaultFileSafety.requireWithin(mediaStagingDir, java.io.File(mediaStagingDir, "$mediaId.pvmedia"))
                    mediaFile.writeBytes(blob.mediaCiphertextBytes)
                    stagedBytes += blob.mediaCiphertextBytes.size
                }
                if (blob.thumbCiphertextBytes.isNotEmpty()) {
                    val thumbFile = VaultFileSafety.requireWithin(thumbStagingDir, java.io.File(thumbStagingDir, "$mediaId.pvthumb"))
                    thumbFile.writeBytes(blob.thumbCiphertextBytes)
                    stagedBytes += blob.thumbCiphertextBytes.size
                }
                if (totalAssetBytes > 0L) {
                    onProgress(stagedBytes, totalAssetBytes)
                }
            }

            return StagedBackupRestore(payload = payload, stagingDir = stagingDir)
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
    }
}
