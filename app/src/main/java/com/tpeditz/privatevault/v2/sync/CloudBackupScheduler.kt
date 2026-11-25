package com.tpeditz.privatevault.v2.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Coordinates WorkManager scheduling and immutable staged snapshot lifecycle management
 * for opt-in automatic Google Drive backups.
 *
 * Concurrency Guarantees:
 * - Each prepared encrypted `.pvault` snapshot has a unique `snapshotId`, SHA-256 digest,
 *   account binding, and atomic publication (`.tmp` + `fd.sync()` -> `.meta.json`).
 * - Concurrent snapshot preparation cannot overwrite an actively uploading snapshot, and
 *   uploading completion only deletes the specific `snapshotId` that finished uploading.
 */
object CloudBackupScheduler {

    const val UNIQUE_PERIODIC_WORK_NAME = "private_vault_auto_cloud_backup"
    const val UNIQUE_ONE_TIME_WORK_NAME = "private_vault_one_time_cloud_upload"
    private const val PENDING_DIR_NAME = "pending_cloud_backup"
    private const val STATE_READY = "READY"

    val snapshotCoordinatorMutex = Mutex()

    @Volatile
    private var activeUploadingSnapshotId: String? = null

    data class PendingSnapshotMeta(
        val snapshotId: String,
        val accountEmail: String,
        val preparedAtEpochMs: Long,
        val sizeBytes: Long,
        val sha256Hex: String,
        val state: String = STATE_READY
    )

    data class StagedCloudSnapshot(
        val meta: PendingSnapshotMeta,
        val file: File,
        val metaFile: File
    )

    fun pendingSnapshotsDir(context: Context): File {
        return File(context.filesDir, PENDING_DIR_NAME).apply { mkdirs() }
    }

    internal fun snapshotFile(dir: File, snapshotId: String): File = File(dir, "snap_$snapshotId.pvault")
    internal fun snapshotMetaFile(dir: File, snapshotId: String): File = File(dir, "snap_$snapshotId.meta.json")

    internal fun computeFileSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var r: Int
            while (fis.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun stageEncryptedSnapshot(
        context: Context,
        accountEmail: String,
        writer: suspend (FileOutputStream) -> Unit
    ): PendingSnapshotMeta = stageSnapshotToDir(pendingSnapshotsDir(context), accountEmail, writer)

    internal suspend fun stageSnapshotToDir(
        dir: File,
        accountEmail: String,
        writer: suspend (FileOutputStream) -> Unit
    ): PendingSnapshotMeta {
        require(accountEmail.isNotBlank()) { "Account email is required to stage cloud snapshot." }
        dir.mkdirs()
        val snapshotId = UUID.randomUUID().toString()
        val tmpDataFile = File(dir, "snap_$snapshotId.pvault.tmp")
        val finalDataFile = snapshotFile(dir, snapshotId)
        val tmpMetaFile = File(dir, "snap_$snapshotId.meta.json.tmp")
        val finalMetaFile = snapshotMetaFile(dir, snapshotId)

        try {
            FileOutputStream(tmpDataFile).use { fos ->
                writer(fos)
                fos.flush()
                fos.fd.sync()
            }
            val sizeBytes = tmpDataFile.length()
            require(sizeBytes > 0L) { "Cannot stage empty cloud backup snapshot." }
            val shaHex = computeFileSha256Hex(tmpDataFile)
            val now = System.currentTimeMillis()

            val meta = PendingSnapshotMeta(
                snapshotId = snapshotId,
                accountEmail = accountEmail,
                preparedAtEpochMs = now,
                sizeBytes = sizeBytes,
                sha256Hex = shaHex,
                state = STATE_READY
            )

            return snapshotCoordinatorMutex.withLock {
                if (!tmpDataFile.renameTo(finalDataFile)) {
                    throw IOException("Failed to publish staged cloud snapshot file.")
                }
                val metaJson = JSONObject().apply {
                    put("snapshotId", meta.snapshotId)
                    put("accountEmail", meta.accountEmail)
                    put("preparedAtEpochMs", meta.preparedAtEpochMs)
                    put("sizeBytes", meta.sizeBytes)
                    put("sha256Hex", meta.sha256Hex)
                    put("state", meta.state)
                }
                FileOutputStream(tmpMetaFile).use { fos ->
                    fos.write(metaJson.toString().toByteArray(StandardCharsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                if (!tmpMetaFile.renameTo(finalMetaFile)) {
                    throw IOException("Failed to publish staged cloud snapshot metadata.")
                }

                // Prune any superseded un-uploading READY snapshots cleanly while protecting activeUploadingSnapshotId
                listValidSnapshotsInDirLocked(dir)
                    .filter { it.meta.snapshotId != snapshotId && it.meta.snapshotId != activeUploadingSnapshotId }
                    .forEach { old ->
                        runCatching { old.metaFile.delete() }
                        runCatching { old.file.delete() }
                    }

                meta
            }
        } catch (t: Throwable) {
            runCatching { tmpDataFile.delete() }
            runCatching { tmpMetaFile.delete() }
            runCatching { finalMetaFile.delete() }
            runCatching { finalDataFile.delete() }
            throw t
        }
    }

    suspend fun acquireLatestReadySnapshot(
        context: Context,
        accountEmail: String
    ): StagedCloudSnapshot? = acquireLatestReadySnapshotFromDir(pendingSnapshotsDir(context), accountEmail)

    internal suspend fun acquireLatestReadySnapshotFromDir(
        dir: File,
        accountEmail: String
    ): StagedCloudSnapshot? = snapshotCoordinatorMutex.withLock {
        if (activeUploadingSnapshotId != null || !dir.exists() || accountEmail.isBlank()) return@withLock null
        val candidates = listValidSnapshotsInDirLocked(dir)
            .filter { it.meta.accountEmail.equals(accountEmail, ignoreCase = true) }
            .sortedByDescending { it.meta.preparedAtEpochMs }

        for (candidate in candidates) {
            if (candidate.file.exists() &&
                candidate.file.length() == candidate.meta.sizeBytes &&
                candidate.meta.sizeBytes > 0L &&
                computeFileSha256Hex(candidate.file).equals(candidate.meta.sha256Hex, ignoreCase = true)
            ) {
                activeUploadingSnapshotId = candidate.meta.snapshotId
                return@withLock candidate
            } else {
                runCatching { candidate.metaFile.delete() }
                runCatching { candidate.file.delete() }
            }
        }
        null
    }

    suspend fun completeUploadedSnapshot(
        context: Context,
        snapshotId: String
    ) = completeUploadedSnapshotInDir(pendingSnapshotsDir(context), snapshotId)

    internal suspend fun completeUploadedSnapshotInDir(
        dir: File,
        snapshotId: String
    ) = snapshotCoordinatorMutex.withLock {
        runCatching { snapshotMetaFile(dir, snapshotId).delete() }
        runCatching { snapshotFile(dir, snapshotId).delete() }
        if (activeUploadingSnapshotId == snapshotId) {
            activeUploadingSnapshotId = null
        }
    }

    suspend fun releaseUploadClaim(snapshotId: String) = snapshotCoordinatorMutex.withLock {
        if (activeUploadingSnapshotId == snapshotId) {
            activeUploadingSnapshotId = null
        }
    }

    private fun listValidSnapshotsInDirLocked(dir: File): List<StagedCloudSnapshot> {
        val metaFiles = dir.listFiles { _, name ->
            name.startsWith("snap_") && name.endsWith(".meta.json")
        } ?: return emptyList()

        return metaFiles.mapNotNull { metaFile ->
            runCatching {
                val json = JSONObject(metaFile.readText(StandardCharsets.UTF_8))
                val id = json.getString("snapshotId")
                val state = json.optString("state", "")
                if (state != STATE_READY) return@mapNotNull null
                val meta = PendingSnapshotMeta(
                    snapshotId = id,
                    accountEmail = json.getString("accountEmail"),
                    preparedAtEpochMs = json.getLong("preparedAtEpochMs"),
                    sizeBytes = json.getLong("sizeBytes"),
                    sha256Hex = json.getString("sha256Hex"),
                    state = state
                )
                StagedCloudSnapshot(meta = meta, file = snapshotFile(dir, id), metaFile = metaFile)
            }.getOrNull()
        }
    }

    suspend fun clearPendingSnapshots(context: Context) {
        snapshotCoordinatorMutex.withLock {
            activeUploadingSnapshotId = null
            runCatching { File(context.filesDir, PENDING_DIR_NAME).deleteRecursively() }
        }
    }

    fun clearPendingSnapshotBlocking(context: Context) {
        runCatching { File(context.filesDir, PENDING_DIR_NAME).deleteRecursively() }
    }

    fun buildConstraints(wifiOnly: Boolean): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    fun intervalDaysFor(frequency: CloudBackupFrequency): Long = when (frequency) {
        CloudBackupFrequency.DAILY -> 1L
        CloudBackupFrequency.WEEKLY -> 7L
        CloudBackupFrequency.MONTHLY -> 30L
    }

    fun schedulePeriodicBackup(
        context: Context,
        frequency: CloudBackupFrequency,
        wifiOnly: Boolean
    ) {
        val intervalDays = intervalDaysFor(frequency)
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(intervalDays, TimeUnit.DAYS)
            .setConstraints(buildConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun triggerImmediatePendingUpload(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setConstraints(buildConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelAllCloudWork(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
    }
}
