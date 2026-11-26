package com.tpeditz.privatevault.v2.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Zero-Knowledge Google Drive Cloud Sync Engine for PRIVATE VAULT V2.
 * Strictly uploads and manages encrypted `.pvault` snapshots in `appDataFolder`.
 */
class GoogleDriveSyncManager(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        const val BACKUP_FILE_PREFIX = "private_vault_v2_backup"
        const val BACKUP_FILENAME = "private_vault_v2_backup.pvault"
        const val APPDATA_FOLDER = "appDataFolder"
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val MAX_CLOUD_BACKUP_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_RETAINED_SNAPSHOTS = 3
    }

    data class CloudBackupMetadata(
        val fileId: String,
        val fileName: String,
        val sizeBytes: Long,
        val modifiedTimeEpochMs: Long
    )

    data class CloudBackupListing(
        val snapshots: List<CloudBackupMetadata>,
        val isComplete: Boolean
    )

    sealed class SyncResult<out T> {
        data class Success<out T>(val data: T, val message: String = "") : SyncResult<T>()
        data class Error(
            val message: String,
            val throwable: Throwable? = null,
            val httpCode: Int? = null
        ) : SyncResult<Nothing>() {
            val isAuthError: Boolean get() = httpCode == 401 || httpCode == 403
        }
    }

    suspend fun queryCloudBackups(accessToken: String): SyncResult<List<CloudBackupMetadata>> {
        return when (val listing = queryCloudBackupsWithStatus(accessToken)) {
            is SyncResult.Success -> SyncResult.Success(listing.data.snapshots, listing.message)
            is SyncResult.Error -> listing
        }
    }

    suspend fun queryCloudBackupsWithStatus(accessToken: String): SyncResult<CloudBackupListing> =
        withContext(Dispatchers.IO) {
            try {
                require(accessToken.isNotBlank()) { "Google authentication token is missing." }
                val resultList = mutableListOf<CloudBackupMetadata>()
                var nextPageToken: String? = null
                var pageCount = 0
                val maxPages = 20

                do {
                    currentCoroutineContext().ensureActive()
                    val baseUrl = "$DRIVE_API_BASE/files?spaces=$APPDATA_FOLDER" +
                        "&q=trashed=false" +
                        "&orderBy=modifiedTime%20desc" +
                        "&fields=nextPageToken,files(id,name,size,modifiedTime)" +
                        "&pageSize=50"
                    val url = if (!nextPageToken.isNullOrBlank()) {
                        "$baseUrl&pageToken=${java.net.URLEncoder.encode(nextPageToken, "UTF-8")}"
                    } else {
                        baseUrl
                    }

                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()

                    executeCancellable(request).use { response ->
                        if (!response.isSuccessful) {
                            return@withContext SyncResult.Error(
                                message = mapHttpSyncMessage("load backups", response.code, response.message),
                                httpCode = response.code
                            )
                        }
                        val bodyString = response.body?.string().orEmpty()
                        val json = JSONObject(bodyString)
                        val filesArray = json.optJSONArray("files") ?: JSONArray()

                        for (i in 0 until filesArray.length()) {
                            val fileObj = filesArray.getJSONObject(i)
                            val id = fileObj.optString("id", "").trim()
                            val name = fileObj.optString("name", "").trim()
                            if (id.isBlank() || name.isBlank()) continue
                            val size = fileObj.optLong("size", 0L)
                            val modifiedTimeStr = fileObj.optString("modifiedTime", "")
                            val modifiedEpochMs = parseRfc3339(modifiedTimeStr)

                            if (name.contains(BACKUP_FILE_PREFIX) || name.endsWith(".pvault")) {
                                resultList.add(
                                    CloudBackupMetadata(
                                        fileId = id,
                                        fileName = name,
                                        sizeBytes = size,
                                        modifiedTimeEpochMs = modifiedEpochMs
                                    )
                                )
                            }
                        }
                        nextPageToken = json.optString("nextPageToken", "").takeIf { it.isNotBlank() }
                    }
                    pageCount++
                } while (!nextPageToken.isNullOrBlank() && pageCount < maxPages)

                val listingComplete = nextPageToken.isNullOrBlank()
                resultList.sortByDescending { it.modifiedTimeEpochMs }
                SyncResult.Success(
                    CloudBackupListing(snapshots = resultList, isComplete = listingComplete),
                    "${resultList.size} snapshots found."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Error("Could not search Google Drive. Check your connection and try again.", e)
            }
        }

    suspend fun uploadBackupSnapshot(
        accessToken: String,
        backupCiphertextBytes: ByteArray,
        customSnapshotName: String? = null
    ): SyncResult<CloudBackupMetadata> = withContext(Dispatchers.IO) {
        try {
            require(accessToken.isNotBlank()) { "Google authentication token is missing." }
            require(backupCiphertextBytes.isNotEmpty()) { "Cannot upload empty backup." }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val filename = customSnapshotName ?: "${BACKUP_FILE_PREFIX}_$timestamp.pvault"

            val metadataJson = JSONObject().apply {
                put("name", filename)
                put("parents", JSONArray().put(APPDATA_FOLDER))
                put("description", "Private Vault V2 Encrypted Backup Snapshot")
            }

            val mediaTypeOctet = "application/octet-stream".toMediaType()
            val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()

            val requestBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadataJson.toString().toRequestBody(mediaTypeJson))
                .addPart(backupCiphertextBytes.toRequestBody(mediaTypeOctet))
                .build()

            val url = "$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,size,modifiedTime"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SyncResult.Error(
                        message = "Google Drive upload failed (HTTP ${response.code}): ${response.message}",
                        httpCode = response.code
                    )
                }
                val bodyString = response.body?.string().orEmpty()
                val respJson = JSONObject(bodyString)
                val newId = respJson.getString("id")
                val name = respJson.getString("name")
                val size = respJson.optLong("size", backupCiphertextBytes.size.toLong())
                require(newId.isNotBlank() && size > 0L) { "Drive upload returned invalid snapshot metadata." }
                val modTime = parseRfc3339(respJson.optString("modifiedTime", ""))

                val uploadedMetadata = CloudBackupMetadata(newId, name, size, modTime)

                // Clean older snapshots exceeding retention (keep latest 3, never delete before verified replacement)
                pruneOldSnapshots(accessToken, protectedFileId = newId)

                SyncResult.Success(uploadedMetadata, "Cloud backup uploaded successfully.")
            }
        } catch (e: Exception) {
            SyncResult.Error("Network error during cloud upload: ${e.message}", e)
        }
    }

    suspend fun downloadBackupSnapshot(
        accessToken: String,
        fileId: String
    ): SyncResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            require(accessToken.isNotBlank()) { "Google authentication token is missing." }
            val url = "$DRIVE_API_BASE/files/$fileId?alt=media"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SyncResult.Error(
                        message = "Google Drive download failed (HTTP ${response.code}): ${response.message}",
                        httpCode = response.code
                    )
                }
                val bytes = response.body?.bytes()
                    ?: return@withContext SyncResult.Error("Empty download response from Google Drive.")
                SyncResult.Success(bytes, "Downloaded ${bytes.size} bytes.")
            }
        } catch (e: Exception) {
            SyncResult.Error("Network error during cloud download: ${e.message}", e)
        }
    }

    suspend fun uploadBackupSnapshotFile(
        accessToken: String,
        backupFile: File,
        customSnapshotName: String? = null,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): SyncResult<CloudBackupMetadata> = withContext(Dispatchers.IO) {
        try {
            require(accessToken.isNotBlank()) { "Google authentication token is missing." }
            require(backupFile.exists() && backupFile.length() > 0L) { "Cannot upload empty or non-existent backup file." }

            val totalBytes = backupFile.length()
            if (totalBytes > MAX_CLOUD_BACKUP_BYTES) {
                return@withContext SyncResult.Error("Backup exceeds the maximum 2 GiB upload limit.")
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val filename = customSnapshotName ?: "${BACKUP_FILE_PREFIX}_$timestamp.pvault"

            val metadataJson = JSONObject().apply {
                put("name", filename)
                put("parents", JSONArray().put(APPDATA_FOLDER))
                put("description", "Private Vault V2 Encrypted Backup Snapshot")
            }

            // Use resumable upload protocol for files > 5 MiB so large encrypted snapshots stream safely
            // with chunk progress and server-offset status recovery without holding payloads in RAM.
            val useResumable = totalBytes > 5L * 1024L * 1024L
            val uploaded = if (useResumable) {
                uploadViaResumableProtocol(
                    accessToken = accessToken,
                    backupFile = backupFile,
                    totalBytes = totalBytes,
                    metadataJson = metadataJson,
                    onProgress = onProgress
                )
            } else {
                uploadViaMultipartStream(
                    accessToken = accessToken,
                    backupFile = backupFile,
                    totalBytes = totalBytes,
                    metadataJson = metadataJson,
                    onProgress = onProgress
                )
            }
            if (uploaded is SyncResult.Success && uploaded.data.sizeBytes == totalBytes && uploaded.data.fileId.isNotBlank()) {
                currentCoroutineContext().ensureActive()
                runCatching { pruneOldSnapshots(accessToken, protectedFileId = uploaded.data.fileId) }
            }
            uploaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error("Network error during cloud upload. Check your connection and try again.", e)
        }
    }

    private suspend fun uploadViaMultipartStream(
        accessToken: String,
        backupFile: File,
        totalBytes: Long,
        metadataJson: JSONObject,
        onProgress: (Long, Long) -> Unit
    ): SyncResult<CloudBackupMetadata> {
        val mediaTypeOctet = "application/octet-stream".toMediaType()
        val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()
        val url = "$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,size,modifiedTime"
        val maxAttempts = 2
        val callerContext = currentCoroutineContext()

        for (attempt in 1..maxAttempts) {
            callerContext.ensureActive()
            val streamingBody = object : RequestBody() {
                override fun contentType() = mediaTypeOctet
                override fun contentLength() = totalBytes
                override fun writeTo(sink: okio.BufferedSink) {
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    backupFile.inputStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            callerContext.ensureActive()
                            sink.write(buf, 0, read)
                            sent += read
                            onProgress(sent, totalBytes)
                        }
                    }
                }
            }
            val requestBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadataJson.toString().toRequestBody(mediaTypeJson))
                .addPart(streamingBody)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            try {
                executeCancellable(request).use { response ->
                    if (response.isSuccessful) {
                        val validated = parseAndValidateUploadedMetadata(response.body?.string().orEmpty(), totalBytes)
                            ?: return SyncResult.Error("Google Drive upload verification failed: snapshot metadata mismatch.")
                        onProgress(totalBytes, totalBytes)
                        return SyncResult.Success(validated, "Cloud backup uploaded successfully.")
                    }
                    if (response.code == 401 || response.code == 403) {
                        return SyncResult.Error(
                            message = mapHttpSyncMessage("upload backup", response.code, response.message),
                            httpCode = response.code
                        )
                    }
                    if ((response.code == 429 || response.code >= 500) && attempt < maxAttempts) {
                        kotlinx.coroutines.delay(400L * attempt)
                        return@use
                    }
                    return SyncResult.Error(
                        message = mapHttpSyncMessage("upload backup", response.code, response.message),
                        httpCode = response.code
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= maxAttempts) {
                    return SyncResult.Error("Network error during cloud upload. Check your connection and try again.", e)
                }
                kotlinx.coroutines.delay(400L * attempt)
            }
        }
        return SyncResult.Error("Cloud upload timed out. Please try again.")
    }

    private suspend fun uploadViaResumableProtocol(
        accessToken: String,
        backupFile: File,
        totalBytes: Long,
        metadataJson: JSONObject,
        onProgress: (Long, Long) -> Unit
    ): SyncResult<CloudBackupMetadata> {
        val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()
        val mediaTypeOctet = "application/octet-stream".toMediaType()
        val initUrl = "$DRIVE_UPLOAD_BASE/files?uploadType=resumable&fields=id,name,size,modifiedTime"
        val initReq = Request.Builder()
            .url(initUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", totalBytes.toString())
            .post(metadataJson.toString().toRequestBody(mediaTypeJson))
            .build()

        val sessionUri = try {
            executeCancellable(initReq).use { resp ->
                if (!resp.isSuccessful) {
                    return SyncResult.Error(
                        message = mapHttpSyncMessage("start upload", resp.code, resp.message),
                        httpCode = resp.code
                    )
                }
                val rawLocation = resp.header("Location")?.trim().orEmpty()
                if (!isTrustedGoogleSessionUrl(rawLocation)) {
                    return SyncResult.Error("Google Drive did not return a valid resumable upload session URL.")
                }
                rawLocation
            }
        } catch (e: CancellationException) {
            throw e
        }

        val chunkSize = 4 * 1024 * 1024 // 4 MiB aligned (256 KiB multiple required by Drive resumable API)
        var offset = 0L
        var transientErrors = 0
        val maxTransientErrors = 5
        var stalledProgressCount = 0
        val maxStalledProgress = 3
        val chunkBuffer = ByteArray(chunkSize)

        try {
            java.io.RandomAccessFile(backupFile, "r").use { raf ->
                while (offset < totalBytes) {
                    currentCoroutineContext().ensureActive()
                    val toRead = minOf(chunkSize.toLong(), totalBytes - offset).toInt()
                    raf.seek(offset)
                    raf.readFully(chunkBuffer, 0, toRead)
                    val endByte = offset + toRead - 1L
                    val rangeHeader = "bytes $offset-$endByte/$totalBytes"

                    val chunkReq = Request.Builder()
                        .url(sessionUri)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Range", rangeHeader)
                        .put(chunkBuffer.toRequestBody(mediaTypeOctet, 0, toRead))
                        .build()

                    val recoveryOutcome: ResumableRecoveryOutcome? = try {
                        executeCancellable(chunkReq).use { putResp ->
                            when (putResp.code) {
                                200, 201 -> {
                                    val validated = parseAndValidateUploadedMetadata(putResp.body?.string().orEmpty(), totalBytes)
                                        ?: return SyncResult.Error("Google Drive upload verification failed: snapshot metadata mismatch.")
                                    onProgress(totalBytes, totalBytes)
                                    return SyncResult.Success(validated, "Cloud backup uploaded successfully.")
                                }
                                308 -> {
                                    transientErrors = 0
                                    val serverRange = putResp.header("Range")
                                    val acceptedOffset = if (serverRange != null) {
                                        parseResumableRangeEndExclusive(serverRange, totalBytes)
                                            ?: return SyncResult.Error("Google Drive returned an invalid resumable upload offset.")
                                    } else {
                                        // An absent Range confirms no received bytes. Query the
                                        // server before retrying; never advance optimistically.
                                        0L
                                    }
                                    if (acceptedOffset <= offset) {
                                        stalledProgressCount++
                                        if (stalledProgressCount >= maxStalledProgress) {
                                            return SyncResult.Error("Google Drive upload made no progress after repeated attempts. Please try again.")
                                        }
                                        ResumableRecoveryOutcome.RetryStatus
                                    } else if (acceptedOffset >= totalBytes) {
                                        stalledProgressCount = 0
                                        offset = totalBytes
                                        onProgress(totalBytes, totalBytes)
                                        ResumableRecoveryOutcome.RetryStatus
                                    } else {
                                        stalledProgressCount = 0
                                        offset = acceptedOffset
                                        onProgress(offset, totalBytes)
                                        ResumableRecoveryOutcome.ContinueUploading
                                    }
                                }
                                401, 403, 404, 410 -> {
                                    return SyncResult.Error(
                                        message = mapHttpSyncMessage("upload backup", putResp.code, putResp.message),
                                        httpCode = putResp.code
                                    )
                                }
                                429, in 500..599 -> {
                                    transientErrors++
                                    if (transientErrors > maxTransientErrors) {
                                        return SyncResult.Error(
                                            message = mapHttpSyncMessage("upload backup", putResp.code, putResp.message),
                                            httpCode = putResp.code
                                        )
                                    }
                                    ResumableRecoveryOutcome.RetryStatus
                                }
                                else -> {
                                    return SyncResult.Error(
                                        message = mapHttpSyncMessage("upload backup", putResp.code, putResp.message),
                                        httpCode = putResp.code
                                    )
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        transientErrors++
                        if (transientErrors > maxTransientErrors) {
                            return SyncResult.Error("Network error during cloud upload. Check your connection and try again.", e)
                        }
                        ResumableRecoveryOutcome.RetryStatus
                    }

                    if (recoveryOutcome == ResumableRecoveryOutcome.RetryStatus) {
                        kotlinx.coroutines.delay(minOf(4000L, 400L shl (transientErrors.coerceAtLeast(1) - 1)))
                        when (val status = queryResumableUploadStatus(sessionUri, accessToken, totalBytes)) {
                            is ResumableStatusResult.Completed -> {
                                onProgress(totalBytes, totalBytes)
                                return SyncResult.Success(status.metadata, "Cloud backup uploaded successfully.")
                            }
                            is ResumableStatusResult.ResumeFrom -> {
                                val nextOffset = status.confirmedOffset
                                if (nextOffset > offset) {
                                    stalledProgressCount = 0
                                }
                                offset = nextOffset
                                onProgress(offset, totalBytes)
                                if (offset >= totalBytes) {
                                    return SyncResult.Error("Cloud upload reached end of file without completion confirmation.")
                                }
                            }
                            is ResumableStatusResult.PermanentError -> {
                                return status.error
                            }
                            is ResumableStatusResult.TransientFailure -> {
                                transientErrors++
                                if (transientErrors > maxTransientErrors) {
                                    return status.error
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(chunkBuffer, 0.toByte())
        }
        return SyncResult.Error("Cloud upload finished without confirmation. Check Google Drive backups.")
    }

    private enum class ResumableRecoveryOutcome {
        ContinueUploading,
        RetryStatus
    }

    private sealed class ResumableStatusResult {
        data class Completed(val metadata: CloudBackupMetadata) : ResumableStatusResult()
        data class ResumeFrom(val confirmedOffset: Long) : ResumableStatusResult()
        data class PermanentError(val error: SyncResult.Error) : ResumableStatusResult()
        data class TransientFailure(val error: SyncResult.Error) : ResumableStatusResult()
    }

    private suspend fun queryResumableUploadStatus(
        sessionUri: String,
        accessToken: String,
        totalBytes: Long
    ): ResumableStatusResult {
        val statusReq = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Range", "bytes */$totalBytes")
            .put(ByteArray(0).toRequestBody(null, 0, 0))
            .build()
        return try {
            executeCancellable(statusReq).use { resp ->
                when (resp.code) {
                    200, 201 -> {
                        val validated = parseAndValidateUploadedMetadata(resp.body?.string().orEmpty(), totalBytes)
                        if (validated != null) {
                            ResumableStatusResult.Completed(validated)
                        } else {
                            ResumableStatusResult.PermanentError(
                                SyncResult.Error("Google Drive upload verification failed: snapshot metadata mismatch.")
                            )
                        }
                    }
                    308 -> {
                        val rangeHeader = resp.header("Range")
                        val accepted = if (rangeHeader != null) {
                            parseResumableRangeEndExclusive(rangeHeader, totalBytes)
                                ?: return ResumableStatusResult.PermanentError(
                                    SyncResult.Error("Google Drive returned an invalid resumable status range.")
                                )
                        } else {
                            0L
                        }
                        ResumableStatusResult.ResumeFrom(accepted)
                    }
                    401, 403, 404, 410 -> ResumableStatusResult.PermanentError(
                        SyncResult.Error(mapHttpSyncMessage("resume upload", resp.code, resp.message), httpCode = resp.code)
                    )
                    429, in 500..599 -> ResumableStatusResult.TransientFailure(
                        SyncResult.Error(mapHttpSyncMessage("resume upload", resp.code, resp.message), httpCode = resp.code)
                    )
                    else -> ResumableStatusResult.PermanentError(
                        SyncResult.Error(mapHttpSyncMessage("resume upload", resp.code, resp.message), httpCode = resp.code)
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ResumableStatusResult.TransientFailure(
                SyncResult.Error("Network error while checking cloud upload progress.", e)
            )
        }
    }

    internal fun isTrustedGoogleSessionUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        val host = parsed.host.lowercase(Locale.US)
        return host == "googleapis.com" ||
            host.endsWith(".googleapis.com") ||
            host == "google.com" ||
            host.endsWith(".google.com")
    }

    internal fun parseResumableRangeEndExclusive(rangeHeader: String, totalBytes: Long): Long? {
        val match = Regex("""^bytes\s*=\s*0-(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(rangeHeader.trim())
            ?: return null
        val endByte = match.groupValues[1].toLongOrNull() ?: return null
        if (endByte < 0L || endByte >= totalBytes) return null
        return endByte + 1L
    }

    private fun parseAndValidateUploadedMetadata(bodyString: String, expectedTotalBytes: Long): CloudBackupMetadata? {
        val respJson = runCatching { JSONObject(bodyString) }.getOrNull() ?: return null
        val newId = respJson.optString("id", "").trim()
        val name = respJson.optString("name", "").trim()
        val size = respJson.optLong("size", -1L)
        if (newId.isBlank() || name.isBlank() || size != expectedTotalBytes) {
            return null
        }
        val modTime = parseRfc3339(respJson.optString("modifiedTime", ""))
        return CloudBackupMetadata(newId, name, size, modTime)
    }

    private suspend fun executeCancellable(request: Request): Response {
        val call: Call = client.newCall(request)
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { throwable ->
            if (throwable != null) {
                call.cancel()
            }
        }
        return try {
            currentCoroutineContext().ensureActive()
            call.execute()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            handle?.dispose()
        }
    }

    suspend fun downloadBackupSnapshotToFile(
        accessToken: String,
        fileId: String,
        targetFile: File
    ): SyncResult<File> = withContext(Dispatchers.IO) {
        val tempFile = File(
            targetFile.parentFile ?: File("."),
            "${targetFile.name}.${UUID.randomUUID()}.download.tmp"
        )
        try {
            require(accessToken.isNotBlank()) { "Google authentication token is missing." }
            require(fileId.isNotBlank() && !fileId.contains('/') && !fileId.contains("..")) {
                "Invalid cloud backup identifier."
            }
            val encodedId = java.net.URLEncoder.encode(fileId, "UTF-8").replace("+", "%20")
            val url = "$DRIVE_API_BASE/files/$encodedId?alt=media"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            executeCancellable(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext SyncResult.Error(
                        message = mapHttpSyncMessage("download backup", response.code, response.message),
                        httpCode = response.code
                    )
                }
                val body = response.body
                    ?: return@withContext SyncResult.Error("Empty download response from Google Drive.")
                val expectedLength = body.contentLength()
                if (expectedLength == 0L || expectedLength > MAX_CLOUD_BACKUP_BYTES) {
                    return@withContext SyncResult.Error("Cloud backup size is invalid or exceeds the 2 GiB limit.")
                }
                val parentDir = targetFile.parentFile ?: File(".")
                parentDir.mkdirs()
                val usableBytes = parentDir.usableSpace
                if (expectedLength > 0L && usableBytes in 1L until expectedLength) {
                    return@withContext SyncResult.Error("Not enough storage space to download this cloud backup.")
                }

                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var r: Int
                        while (input.read(buffer).also { r = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            totalRead += r
                            if (totalRead > MAX_CLOUD_BACKUP_BYTES) {
                                return@withContext SyncResult.Error("Cloud backup exceeds the 2 GiB size limit.")
                            }
                            output.write(buffer, 0, r)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (totalRead <= 0L || (expectedLength > 0L && totalRead != expectedLength)) {
                    return@withContext SyncResult.Error("Cloud download was interrupted before completion. Try again.")
                }
                atomicReplaceFile(tempFile, targetFile)
                SyncResult.Success(targetFile, "Downloaded $totalRead bytes.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error("Network error during cloud download. Check your connection and try again.", e)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun atomicReplaceFile(sourceTemp: File, destination: File) {
        if (sourceTemp.renameTo(destination)) return
        val osRenamed = try {
            val osClass = Class.forName("android.system.Os")
            val renameMethod = osClass.getMethod("rename", String::class.java, String::class.java)
            renameMethod.invoke(null, sourceTemp.absolutePath, destination.absolutePath)
            true
        } catch (_: Throwable) {
            false
        }
        if (osRenamed) return
        val nioMoved = runCatching {
            val filesCls = Class.forName("java.nio.file.Files")
            val pathCls = Class.forName("java.nio.file.Path")
            val copyOptCls = Class.forName("java.nio.file.CopyOption")
            val stdOptCls = Class.forName("java.nio.file.StandardCopyOption")
            val replaceExisting = java.lang.Enum.valueOf(stdOptCls.asSubclass(Enum::class.java), "REPLACE_EXISTING")
            val options = java.lang.reflect.Array.newInstance(copyOptCls, 1)
            java.lang.reflect.Array.set(options, 0, replaceExisting)
            val toPath = File::class.java.getMethod("toPath")
            val moveMethod = filesCls.getMethod("move", pathCls, pathCls, options.javaClass)
            moveMethod.invoke(null, toPath.invoke(sourceTemp), toPath.invoke(destination), options)
            true
        }.getOrDefault(false)
        if (!nioMoved) {
            throw IOException("Could not atomically replace downloaded backup file.")
        }
    }

    suspend fun deleteCloudBackup(
        accessToken: String,
        fileId: String
    ): SyncResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            require(fileId.isNotBlank() && !fileId.contains('/') && !fileId.contains("..")) {
                "Invalid cloud backup identifier."
            }
            val encodedId = java.net.URLEncoder.encode(fileId, "UTF-8").replace("+", "%20")
            val url = "$DRIVE_API_BASE/files/$encodedId"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()

            executeCancellable(request).use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    return@withContext SyncResult.Error(
                        message = mapHttpSyncMessage("delete snapshot", response.code, response.message),
                        httpCode = response.code
                    )
                }
                SyncResult.Success(true, "Cloud snapshot deleted.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error("Network error during deletion. Check your connection and try again.", e)
        }
    }

    private suspend fun pruneOldSnapshots(accessToken: String, protectedFileId: String) {
        val queryRes = queryCloudBackupsWithStatus(accessToken)
        if (queryRes !is SyncResult.Success) return
        val listing = queryRes.data
        // Never prune against a partial/truncated remote listing because newer snapshots may be on unqueried pages.
        if (!listing.isComplete) return

        // Only prune snapshots with confirmed timestamps; never auto-delete uncertain-age snapshots (<= 0L).
        val confirmedOthers = listing.snapshots
            .filter { it.modifiedTimeEpochMs > 0L && it.fileId != protectedFileId }
            .sortedByDescending { it.modifiedTimeEpochMs }
        val keepOthersCount = (MAX_RETAINED_SNAPSHOTS - 1).coerceAtLeast(0)
        if (confirmedOthers.size > keepOthersCount) {
            val toDelete = confirmedOthers.drop(keepOthersCount)
            for (oldFile in toDelete) {
                currentCoroutineContext().ensureActive()
                deleteCloudBackup(accessToken, oldFile.fileId)
            }
        }
    }

    private fun mapHttpSyncMessage(action: String, code: Int, reason: String): String {
        return when (code) {
            401 -> "Reconnect your Google account to continue."
            403 -> if (reason.contains("quota", ignoreCase = true) || reason.contains("storage", ignoreCase = true)) {
                "Google Drive storage quota reached. Free up space in Google Drive and try again."
            } else {
                "Google Drive permission is missing or expired. Reconnect your Google account."
            }
            404 -> "Selected backup was not found in Google Drive."
            413 -> "Backup exceeds the allowed Google Drive upload size."
            429 -> "Google Drive is temporarily busy. Please wait a moment and try again."
            507 -> "Your Google Drive storage is full. Free up space and try again."
            in 500..599 -> "Google Drive is temporarily unavailable. Please try again."
            else -> "Could not $action right now (status $code)."
        }
    }

    internal fun parseRfc3339(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val normalized = dateStr.trim()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ"
        )
        val candidates = listOf(
            normalized,
            normalized.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        )
        for (candidate in candidates) {
            for (pattern in formats) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                        isLenient = false
                    }.parse(candidate)?.time
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return 0L
    }
}
