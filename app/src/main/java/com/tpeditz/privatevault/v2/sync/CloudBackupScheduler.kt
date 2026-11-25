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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Coordinates WorkManager scheduling and staged pre-encrypted snapshot management
 * for opt-in automatic Google Drive backups.
 */
object CloudBackupScheduler {

    const val UNIQUE_PERIODIC_WORK_NAME = "private_vault_auto_cloud_backup"
    const val UNIQUE_ONE_TIME_WORK_NAME = "private_vault_one_time_cloud_upload"
    private const val PENDING_DIR_NAME = "pending_cloud_backup"
    private const val SNAPSHOT_FILE_NAME = "auto_snapshot.pvault"
    private const val META_FILE_NAME = "auto_snapshot.meta.json"

    data class PendingSnapshotMeta(
        val accountEmail: String,
        val preparedAtEpochMs: Long,
        val sizeBytes: Long
    )

    fun pendingSnapshotFile(context: Context): File {
        val dir = File(context.filesDir, PENDING_DIR_NAME).apply { mkdirs() }
        return File(dir, SNAPSHOT_FILE_NAME)
    }

    fun pendingSnapshotMetaFile(context: Context): File {
        val dir = File(context.filesDir, PENDING_DIR_NAME).apply { mkdirs() }
        return File(dir, META_FILE_NAME)
    }

    fun readPendingSnapshotMeta(context: Context): PendingSnapshotMeta? {
        val file = pendingSnapshotMetaFile(context)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            PendingSnapshotMeta(
                accountEmail = json.optString("accountEmail", ""),
                preparedAtEpochMs = json.optLong("preparedAtEpochMs", 0L),
                sizeBytes = json.optLong("sizeBytes", 0L)
            )
        }.getOrNull()
    }

    fun writePendingSnapshotMeta(context: Context, meta: PendingSnapshotMeta) {
        val file = pendingSnapshotMetaFile(context)
        val tmp = File(file.parentFile, "$META_FILE_NAME.tmp")
        val json = JSONObject().apply {
            put("accountEmail", meta.accountEmail)
            put("preparedAtEpochMs", meta.preparedAtEpochMs)
            put("sizeBytes", meta.sizeBytes)
        }
        FileOutputStream(tmp).use { fos ->
            fos.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    fun clearPendingSnapshot(context: Context) {
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
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelAllCloudWork(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
    }
}
