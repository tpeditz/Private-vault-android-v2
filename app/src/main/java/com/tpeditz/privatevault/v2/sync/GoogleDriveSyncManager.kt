package com.tpeditz.privatevault.v2.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
        const val MAX_RETAINED_SNAPSHOTS = 3
    }

    data class CloudBackupMetadata(
        val fileId: String,
        val fileName: String,
        val sizeBytes: Long,
        val modifiedTimeEpochMs: Long
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

    suspend fun queryCloudBackups(accessToken: String): SyncResult<List<CloudBackupMetadata>> =
        withContext(Dispatchers.IO) {
            try {
                require(accessToken.isNotBlank()) { "Google authentication token is missing." }
                val url = "$DRIVE_API_BASE/files?spaces=$APPDATA_FOLDER" +
                    "&q=trashed=false" +
                    "&fields=files(id,name,size,modifiedTime)" +
                    "&pageSize=20"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SyncResult.Error(
                            message = "Could not load backups (HTTP ${response.code}): ${response.message}",
                            httpCode = response.code
                        )
                    }
                    val bodyString = response.body?.string().orEmpty()
                    val json = JSONObject(bodyString)
                    val filesArray = json.optJSONArray("files") ?: JSONArray()
                    val resultList = mutableListOf<CloudBackupMetadata>()

                    for (i in 0 until filesArray.length()) {
                        val fileObj = filesArray.getJSONObject(i)
                        val id = fileObj.getString("id")
                        val name = fileObj.getString("name")
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
                    resultList.sortByDescending { it.modifiedTimeEpochMs }
                    SyncResult.Success(resultList, "${resultList.size} snapshots found.")
                }
            } catch (e: Exception) {
                SyncResult.Error("Network error while searching Google Drive: ${e.message}", e)
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
                pruneOldSnapshots(accessToken)

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
        customSnapshotName: String? = null
    ): SyncResult<CloudBackupMetadata> = withContext(Dispatchers.IO) {
        try {
            require(accessToken.isNotBlank()) { "Google authentication token is missing." }
            require(backupFile.exists() && backupFile.length() > 0L) { "Cannot upload empty or non-existent backup file." }

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
                .addPart(backupFile.asRequestBody(mediaTypeOctet))
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
                val size = respJson.optLong("size", backupFile.length())
                require(newId.isNotBlank() && size > 0L) { "Drive upload returned invalid snapshot metadata." }
                val modTime = parseRfc3339(respJson.optString("modifiedTime", ""))

                val uploadedMetadata = CloudBackupMetadata(newId, name, size, modTime)
                pruneOldSnapshots(accessToken)
                SyncResult.Success(uploadedMetadata, "Cloud backup uploaded successfully.")
            }
        } catch (e: Exception) {
            SyncResult.Error("Network error during cloud upload: ${e.message}", e)
        }
    }

    suspend fun downloadBackupSnapshotToFile(
        accessToken: String,
        fileId: String,
        targetFile: File
    ): SyncResult<File> = withContext(Dispatchers.IO) {
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
                        "Google Drive download failed (HTTP ${response.code}): ${response.message}"
                    )
                }
                val body = response.body
                    ?: return@withContext SyncResult.Error("Empty download response from Google Drive.")

                targetFile.parentFile?.mkdirs()
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        var r: Int
                        while (input.read(buffer).also { r = it } != -1) {
                            output.write(buffer, 0, r)
                        }
                    }
                }
                SyncResult.Success(targetFile, "Downloaded ${targetFile.length()} bytes.")
            }
        } catch (e: Exception) {
            SyncResult.Error("Network error during cloud download: ${e.message}", e)
        }
    }

    suspend fun deleteCloudBackup(
        accessToken: String,
        fileId: String
    ): SyncResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$DRIVE_API_BASE/files/$fileId"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    return@withContext SyncResult.Error("Delete failed with HTTP ${response.code}.")
                }
                SyncResult.Success(true, "Cloud snapshot deleted.")
            }
        } catch (e: Exception) {
            SyncResult.Error("Network error during deletion: ${e.message}", e)
        }
    }

    private suspend fun pruneOldSnapshots(accessToken: String) {
        val queryRes = queryCloudBackups(accessToken)
        if (queryRes is SyncResult.Success) {
            val all = queryRes.data
            if (all.size > MAX_RETAINED_SNAPSHOTS) {
                val toDelete = all.subList(MAX_RETAINED_SNAPSHOTS, all.size)
                for (oldFile in toDelete) {
                    deleteCloudBackup(accessToken, oldFile.fileId)
                }
            }
        }
    }

    private fun parseRfc3339(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(dateStr)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
