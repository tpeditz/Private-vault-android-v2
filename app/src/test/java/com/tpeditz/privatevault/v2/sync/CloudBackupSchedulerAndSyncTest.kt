package com.tpeditz.privatevault.v2.sync

import com.tpeditz.privatevault.v2.data.CloudBackupFrequency
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloudBackupSchedulerAndSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSchedulerFrequenciesAndConstraints() {
        assertEquals(1L, CloudBackupScheduler.intervalDaysFor(CloudBackupFrequency.DAILY))
        assertEquals(7L, CloudBackupScheduler.intervalDaysFor(CloudBackupFrequency.WEEKLY))
        assertEquals(30L, CloudBackupScheduler.intervalDaysFor(CloudBackupFrequency.MONTHLY))

        val wifiOnly = CloudBackupScheduler.buildConstraints(wifiOnly = true)
        assertEquals(androidx.work.NetworkType.UNMETERED, wifiOnly.requiredNetworkType)
        assertTrue(wifiOnly.requiresBatteryNotLow())

        val meteredAllowed = CloudBackupScheduler.buildConstraints(wifiOnly = false)
        assertEquals(androidx.work.NetworkType.CONNECTED, meteredAllowed.requiredNetworkType)
        assertTrue(meteredAllowed.requiresBatteryNotLow())
    }

    @Test
    fun testGoogleDriveSyncManagerUsesMultipartRelatedAndDetectsAuthErrors() = runBlocking {
        val dummyBackup = File(tempFolder.root, "snapshot.pvault").apply {
            writeBytes("PVLT2-encrypted-dummy-payload".toByteArray())
        }

        var capturedContentType = ""
        var capturedRequestBody = ""
        var capturedAuthHeader = ""

        val successClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val responseJson = if (req.method.equals("POST", ignoreCase = true)) {
                    capturedContentType = req.body?.contentType()?.toString().orEmpty()
                    capturedAuthHeader = req.header("Authorization").orEmpty()
                    val buf = Buffer()
                    req.body?.writeTo(buf)
                    capturedRequestBody = buf.readUtf8()
                    """{"id":"drive_file_123","name":"private_vault_v2_backup.pvault","size":"${dummyBackup.length()}"}"""
                } else {
                    """{"files":[]}"""
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val syncManager = GoogleDriveSyncManager(successClient)
        val result = syncManager.uploadBackupSnapshotFile("test-oauth-token", dummyBackup)
        assertTrue(result is GoogleDriveSyncManager.SyncResult.Success)
        assertTrue(
            "Drive v3 uploadType=multipart must use multipart/related, got $capturedContentType",
            capturedContentType.startsWith("multipart/related")
        )
        assertEquals("Bearer test-oauth-token", capturedAuthHeader)
        assertTrue(capturedRequestBody.contains("appDataFolder"))
        assertTrue(capturedRequestBody.contains("PVLT2-encrypted-dummy-payload"))

        val authFailClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"error":"invalid_token"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val expiredManager = GoogleDriveSyncManager(authFailClient)
        val errRes = expiredManager.queryCloudBackups("expired-token")
        assertTrue(errRes is GoogleDriveSyncManager.SyncResult.Error)
        val err = errRes as GoogleDriveSyncManager.SyncResult.Error
        assertEquals(401, err.httpCode)
        assertTrue(err.isAuthError)
        assertFalse(err.message.isBlank())
    }
}
