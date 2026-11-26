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

    @Test
    fun testImmutableSnapshotsDoNotOverwriteActiveUploadAndProtectPruneTarget() = runBlocking {
        val stageDir = tempFolder.newFolder("immutable_snapshots")
        val meta1 = CloudBackupScheduler.stageSnapshotToDir(stageDir, "user@example.test") { fos ->
            fos.write("SNAPSHOT_PAYLOAD_ONE".toByteArray())
        }
        val acquired1 = CloudBackupScheduler.acquireLatestReadySnapshotFromDir(stageDir, "user@example.test")
        requireNotNull(acquired1)
        assertEquals(meta1.snapshotId, acquired1.meta.snapshotId)
        assertTrue(acquired1.file.exists())

        // While snapshot 1 is actively uploading, staging snapshot 2 must NOT delete snapshot 1!
        val meta2 = CloudBackupScheduler.stageSnapshotToDir(stageDir, "user@example.test") { fos ->
            fos.write("SNAPSHOT_PAYLOAD_TWO".toByteArray())
        }
        assertTrue(meta1.snapshotId != meta2.snapshotId)
        assertTrue("Active uploading snapshot must remain intact", acquired1.file.exists())
        assertTrue(
            "Newly prepared snapshot must exist alongside active upload",
            CloudBackupScheduler.snapshotFile(stageDir, meta2.snapshotId).exists()
        )

        // Finishing upload 1 deletes ONLY snapshot 1, preserving snapshot 2 for the next upload cycle
        CloudBackupScheduler.completeUploadedSnapshotInDir(stageDir, meta1.snapshotId)
        assertFalse(acquired1.file.exists())
        assertTrue(CloudBackupScheduler.snapshotFile(stageDir, meta2.snapshotId).exists())

        val acquired2 = CloudBackupScheduler.acquireLatestReadySnapshotFromDir(stageDir, "user@example.test")
        requireNotNull(acquired2)
        assertEquals(meta2.snapshotId, acquired2.meta.snapshotId)
        CloudBackupScheduler.completeUploadedSnapshotInDir(stageDir, meta2.snapshotId)
    }

    @Test
    fun testPaginationRfc3339ZeroFallbackDownload401AndResumableUpload() = runBlocking {
        // 1. Paginated queryCloudBackups & missing/invalid dates must become 0L (not "now")
        var listCalls = 0
        val paginatedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                listCalls++
                val body = if (!url.contains("pageToken=page2")) {
                    """{"nextPageToken":"page2","files":[{"id":"f_old","name":"private_vault_v2_backup_old.pvault","size":"100","modifiedTime":""}]}"""
                } else {
                    """{"files":[{"id":"f_new","name":"private_vault_v2_backup_new.pvault","size":"200","modifiedTime":"2026-11-25T10:00:00Z"}]}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val syncPaginated = GoogleDriveSyncManager(paginatedClient)
        assertEquals(0L, syncPaginated.parseRfc3339(""))
        assertEquals(0L, syncPaginated.parseRfc3339("not-a-timestamp"))
        val listed = syncPaginated.queryCloudBackups("valid-token")
        assertTrue(listed is GoogleDriveSyncManager.SyncResult.Success)
        val items = (listed as GoogleDriveSyncManager.SyncResult.Success).data
        assertEquals(2, listCalls)
        assertEquals(2, items.size)
        // Newest valid backup comes first; missing modifiedTime resolves to 0L (not newer!)
        assertEquals("f_new", items[0].fileId)
        assertEquals(0L, items[1].modifiedTimeEpochMs)

        // 2. Download & Delete propagate httpCode=401 and clean partial target files
        val authFailClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"error":"unauthorized"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val authSync = GoogleDriveSyncManager(authFailClient)
        val dlTarget = File(tempFolder.root, "dl_401.pvault")
        val dlRes = authSync.downloadBackupSnapshotToFile("expired", "id1", dlTarget)
        assertTrue(dlRes is GoogleDriveSyncManager.SyncResult.Error)
        assertEquals(401, (dlRes as GoogleDriveSyncManager.SyncResult.Error).httpCode)
        assertTrue((dlRes).isAuthError)
        assertFalse(dlTarget.exists())

        val delRes = authSync.deleteCloudBackup("expired", "id1")
        assertTrue(delRes is GoogleDriveSyncManager.SyncResult.Error)
        assertEquals(401, (delRes as GoogleDriveSyncManager.SyncResult.Error).httpCode)

        // 3. Large file (> 5 MiB) triggers uploadType=resumable with Location chunk PUTs
        val bigFile = File(tempFolder.root, "large_backup.pvault").apply {
            writeBytes(ByteArray(5 * 1024 * 1024 + 128) { 0x5A })
        }
        val methodsSeen = mutableListOf<String>()
        val resumableClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                methodsSeen.add("${req.method}:${req.url}")
                when {
                    req.method == "POST" && req.url.toString().contains("uploadType=resumable") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=sess123")
                            .body("".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    req.method == "PUT" && req.header("Content-Range")?.startsWith("bytes 0-") == true -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(308)
                            .message("Resume Incomplete")
                            .header("Range", "bytes=0-${4 * 1024 * 1024 - 1}")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    req.method == "PUT" -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"id":"resumable_ok","name":"large.pvault","size":"${bigFile.length()}","modifiedTime":"2026-11-25T10:00:00Z"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"files":[]}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            }
            .build()
        var lastReportedSent = 0L
        val resLarge = GoogleDriveSyncManager(resumableClient).uploadBackupSnapshotFile(
            accessToken = "valid-token",
            backupFile = bigFile,
            onProgress = { sent, _ -> lastReportedSent = sent }
        )
        assertTrue(resLarge is GoogleDriveSyncManager.SyncResult.Success)
        assertEquals(bigFile.length(), lastReportedSent)
        assertTrue(methodsSeen.any { it.contains("uploadType=resumable") })
        assertTrue(methodsSeen.count { it.startsWith("PUT:") } == 2)
    }

    @Test
    fun testResumableUploadMissingRangeDoesNotAdvanceUntilConfirmed() = runBlocking {
        val file = tempFolder.newFile("interrupted-backup.pvault").apply {
            writeBytes(ByteArray(5 * 1024 * 1024 + 128))
        }
        val total = file.length()
        val firstChunkEnd = 4L * 1024 * 1024 - 1
        val firstRange = "bytes 0-$firstChunkEnd/$total"
        val secondRange = "bytes ${firstChunkEnd + 1}-${total - 1}/$total"
        val ranges = mutableListOf<String>()
        val progress = mutableListOf<Long>()
        var firstChunkAttempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            val range = req.header("Content-Range")
            when {
                req.method == "POST" -> response.code(200).message("OK")
                    .header("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=test-session")
                    .body("".toResponseBody("application/json".toMediaType())).build()
                req.method == "PUT" -> {
                    ranges.add(range.orEmpty())
                    when (range) {
                        firstRange -> {
                            firstChunkAttempts++
                            val builder = response.code(308).message("Resume Incomplete")
                            if (firstChunkAttempts > 1) {
                                builder.header("Range", "bytes=0-$firstChunkEnd")
                            }
                            builder.body("".toResponseBody("text/plain".toMediaType())).build()
                        }
                        "bytes */$total" -> {
                            assertTrue(progress.all { it == 0L })
                            response.code(308).message("Resume Incomplete")
                                .body("".toResponseBody("text/plain".toMediaType())).build()
                        }
                        secondRange -> response.code(200).message("OK")
                            .body("""{"id":"completed","name":"backup.pvault","size":"$total","modifiedTime":"2026-11-25T10:00:00Z"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                        else -> error("Unexpected upload range in normal interrupted-transfer regression test")
                    }
                }
                else -> response.code(200).message("OK")
                    .body("""{"files":[]}""".toResponseBody("application/json".toMediaType())).build()
            }
        }.build()
        val result = GoogleDriveSyncManager(client).uploadBackupSnapshotFile(
            accessToken = "fixture-token",
            backupFile = file,
            onProgress = { sent, _ -> progress.add(sent) }
        )
        assertTrue(result is GoogleDriveSyncManager.SyncResult.Success)
        assertEquals(listOf(firstRange, "bytes */$total", firstRange, secondRange), ranges)
        assertEquals(total, progress.last())
    }

    @Test
    fun testResumableUploadRecoversFromPartial308RangeAnd503StatusProbe() = runBlocking {
        val bigFile = File(tempFolder.root, "partial_recovery.pvault").apply {
            writeBytes(ByteArray(5 * 1024 * 1024 + 512) { idx -> (idx % 251).toByte() })
        }
        val totalLen = bigFile.length()
        val partialAcceptedEnd = 2L * 1024L * 1024L - 1L // 2 MiB - 1
        val probeAcceptedEnd = 3L * 1024L * 1024L - 1L // 3 MiB - 1
        var putCallCount = 0
        val contentRangesSeen = mutableListOf<String>()
        val progressOffsets = mutableListOf<Long>()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                when {
                    req.method == "POST" && req.url.toString().contains("uploadType=resumable") -> {
                        Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .header("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=part1")
                            .body("".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    req.method == "PUT" -> {
                        putCallCount++
                        val cr = req.header("Content-Range").orEmpty()
                        contentRangesSeen.add(cr)
                        when {
                            putCallCount == 1 -> {
                                // Chunk 0: server only commits 2 MiB via Range header
                                Response.Builder()
                                    .request(req).protocol(Protocol.HTTP_1_1).code(308).message("Resume Incomplete")
                                    .header("Range", "bytes=0-$partialAcceptedEnd")
                                    .body("".toResponseBody("text/plain".toMediaType()))
                                    .build()
                            }
                            putCallCount == 2 -> {
                                // Next PUT must start at 2 MiB; simulate transient 503 outage
                                assertTrue("Expected resume from 2 MiB, got $cr", cr.startsWith("bytes ${partialAcceptedEnd + 1}-"))
                                Response.Builder()
                                    .request(req).protocol(Protocol.HTTP_1_1).code(503).message("Service Unavailable")
                                    .body("".toResponseBody("text/plain".toMediaType()))
                                    .build()
                            }
                            putCallCount == 3 -> {
                                // Client must query server status with Content-Range: bytes */<total>
                                assertEquals("bytes */$totalLen", cr)
                                Response.Builder()
                                    .request(req).protocol(Protocol.HTTP_1_1).code(308).message("Resume Incomplete")
                                    .header("Range", "bytes=0-$probeAcceptedEnd")
                                    .body("".toResponseBody("text/plain".toMediaType()))
                                    .build()
                            }
                            putCallCount == 4 -> {
                                // Must resume from confirmed 3 MiB, finishing whole file
                                assertEquals("bytes ${probeAcceptedEnd + 1}-${totalLen - 1}/$totalLen", cr)
                                Response.Builder()
                                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                                    .body("""{"id":"recovered_id","name":"partial_recovery.pvault","size":"$totalLen","modifiedTime":"2026-11-25T10:00:00Z"}""".toResponseBody("application/json".toMediaType()))
                                    .build()
                            }
                            else -> {
                                Response.Builder()
                                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                                    .body("""{"files":[]}""".toResponseBody("application/json".toMediaType()))
                                    .build()
                            }
                        }
                    }
                    else -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"files":[]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val result = GoogleDriveSyncManager(client).uploadBackupSnapshotFile(
            accessToken = "valid-token",
            backupFile = bigFile,
            onProgress = { sent, _ -> progressOffsets.add(sent) }
        )
        assertTrue(result is GoogleDriveSyncManager.SyncResult.Success)
        assertEquals("recovered_id", (result as GoogleDriveSyncManager.SyncResult.Success).data.fileId)
        assertTrue(progressOffsets.contains(partialAcceptedEnd + 1))
        assertTrue(progressOffsets.contains(probeAcceptedEnd + 1))
        assertEquals(totalLen, progressOffsets.last())
        assertTrue(contentRangesSeen.any { it == "bytes */$totalLen" })
    }

    @Test
    fun testResumableUploadRejectsUntrustedLocationStops401AndRejectsMismatchedSize() = runBlocking {
        val bigFile = File(tempFolder.root, "security_checks.pvault").apply {
            writeBytes(ByteArray(5 * 1024 * 1024 + 64) { 0x3C })
        }

        // 1. Rejects non-Google / insecure Location URI
        val badHostClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Location", "https://evil.example.test/upload")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
        val badLocRes = GoogleDriveSyncManager(badHostClient).uploadBackupSnapshotFile("tok", bigFile)
        assertTrue(badLocRes is GoogleDriveSyncManager.SyncResult.Error)

        // 2. Stops immediately on 401 without retries
        var chunkAttempts = 0
        val unauthChunkClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "POST") {
                    Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Location", "https://www.googleapis.com/upload/drive/v3/files?upload_id=u1")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    chunkAttempts++
                    Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }.build()
        val authRes = GoogleDriveSyncManager(unauthChunkClient).uploadBackupSnapshotFile("tok", bigFile)
        assertTrue(authRes is GoogleDriveSyncManager.SyncResult.Error && authRes.isAuthError)
        assertEquals(1, chunkAttempts)

        // 3. Rejects mismatched final metadata size & does NOT prune existing cloud backups
        var pruneDeleteCalled = false
        val mismatchSizeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                when {
                    req.method == "DELETE" -> {
                        pruneDeleteCalled = true
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("".toResponseBody("text/plain".toMediaType())).build()
                    }
                    req.method == "POST" -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Location", "https://www.googleapis.com/upload/drive/v3/files?upload_id=u2")
                        .body("".toResponseBody("application/json".toMediaType())).build()
                    req.method == "PUT" && req.header("Content-Range")?.startsWith("bytes 0-") == true -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(308).message("Resume Incomplete")
                        .header("Range", "bytes=0-${4 * 1024 * 1024 - 1}")
                        .body("".toResponseBody("text/plain".toMediaType())).build()
                    else -> Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"id":"bad_size","name":"security_checks.pvault","size":"123"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }.build()
        val sizeErr = GoogleDriveSyncManager(mismatchSizeClient).uploadBackupSnapshotFile("tok", bigFile)
        assertTrue(sizeErr is GoogleDriveSyncManager.SyncResult.Error)
        assertFalse("Pruning must never run when upload verification fails", pruneDeleteCalled)
    }

    @Test
    fun testRetentionPruningSkipsIncompleteListingProtectsUnknownAgeAndNewUpload() = runBlocking {
        val smallFile = File(tempFolder.root, "small_prune_check.pvault").apply {
            writeBytes("PVLT2-payload".toByteArray())
        }
        val deletedOnIncomplete = mutableListOf<String>()

        // 1. Truncated listing (20 pages all with nextPageToken) -> MUST NOT prune
        val incompleteListClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                when (req.method) {
                    "POST" -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"id":"f_new","name":"private_vault_v2_backup_new.pvault","size":"${smallFile.length()}"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                    "DELETE" -> {
                        deletedOnIncomplete.add(req.url.toString())
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("".toResponseBody("text/plain".toMediaType())).build()
                    }
                    else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"nextPageToken":"more","files":[{"id":"old_1","name":"private_vault_v2_backup_1.pvault","size":"10","modifiedTime":"2026-10-01T00:00:00Z"},{"id":"old_2","name":"private_vault_v2_backup_2.pvault","size":"10","modifiedTime":"2026-10-02T00:00:00Z"},{"id":"old_3","name":"private_vault_v2_backup_3.pvault","size":"10","modifiedTime":"2026-10-03T00:00:00Z"},{"id":"old_4","name":"private_vault_v2_backup_4.pvault","size":"10","modifiedTime":"2026-10-04T00:00:00Z"}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }.build()
        val incompleteUpload = GoogleDriveSyncManager(incompleteListClient).uploadBackupSnapshotFile("tok", smallFile)
        assertTrue(incompleteUpload is GoogleDriveSyncManager.SyncResult.Success)
        assertTrue("Incomplete remote listing must abort retention pruning", deletedOnIncomplete.isEmpty())

        // 2. Complete listing: protects newly uploaded snapshot AND uncertain-age (modifiedTimeEpochMs == 0L) snapshots
        val deletedIds = mutableListOf<String>()
        val completeListClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                when (req.method) {
                    "POST" -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"id":"f_new","name":"private_vault_v2_backup_new.pvault","size":"${smallFile.length()}","modifiedTime":"2026-11-25T12:00:00Z"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                    "DELETE" -> {
                        deletedIds.add(req.url.pathSegments.last())
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("".toResponseBody("text/plain".toMediaType())).build()
                    }
                    else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(
                            """{"files":[
                                {"id":"f_new","name":"private_vault_v2_backup_new.pvault","size":"10","modifiedTime":"2026-11-25T12:00:00Z"},
                                {"id":"c_3","name":"private_vault_v2_backup_c3.pvault","size":"10","modifiedTime":"2026-11-24T12:00:00Z"},
                                {"id":"c_2","name":"private_vault_v2_backup_c2.pvault","size":"10","modifiedTime":"2026-11-23T12:00:00Z"},
                                {"id":"c_1","name":"private_vault_v2_backup_c1.pvault","size":"10","modifiedTime":"2026-11-22T12:00:00Z"},
                                {"id":"unknown_1","name":"private_vault_v2_backup_u1.pvault","size":"10","modifiedTime":""},
                                {"id":"unknown_2","name":"private_vault_v2_backup_u2.pvault","size":"10","modifiedTime":"not-a-date"}
                            ]}""".trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            }.build()
        val completeUpload = GoogleDriveSyncManager(completeListClient).uploadBackupSnapshotFile("tok", smallFile)
        assertTrue(completeUpload is GoogleDriveSyncManager.SyncResult.Success)
        assertEquals(listOf("c_1"), deletedIds)
        assertFalse(deletedIds.contains("unknown_1"))
        assertFalse(deletedIds.contains("unknown_2"))
        assertFalse(deletedIds.contains("f_new"))
    }

    @Test
    fun testDownloadPreservesPreExistingTargetFileOnFailureAndCleansTempFile() = runBlocking {
        val dir = tempFolder.newFolder("download_atomic")
        val targetFile = File(dir, "existing.pvault").apply {
            writeBytes("GOOD_PREEXISTING_BACKUP".toByteArray())
        }
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Report Content-Length 1000 while sending only 10 bytes before EOF
                val truncatedBody = object : okhttp3.ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = 1000L
                    override fun source(): okio.BufferedSource = Buffer().writeUtf8("ONLY_TEN__")
                }
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Length", "1000")
                    .body(truncatedBody)
                    .build()
            }.build()
        val res = GoogleDriveSyncManager(failingClient).downloadBackupSnapshotToFile("tok", "id1", targetFile)
        assertTrue(res is GoogleDriveSyncManager.SyncResult.Error)
        assertTrue("Pre-existing target file must remain intact on failed download", targetFile.exists())
        assertEquals("GOOD_PREEXISTING_BACKUP", targetFile.readText())
        assertTrue(
            "Operation temp files must be cleaned up",
            dir.listFiles().orEmpty().none { it.name.contains(".download.tmp") }
        )
    }
}
