package com.tpeditz.privatevault.v2.ota

import com.tpeditz.privatevault.v2.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class OtaManifestTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val officialAppId = "com.tpeditz.privatevault.v2"
    private val expectedCertSha256 =
        "3B:EE:FF:13:8B:71:61:68:B2:F1:66:98:3D:42:49:6B:9D:24:F6:29:B7:5E:E2:5B:DA:FD:16:67:6F:06:A7:70"
    private val canonicalManifestUrl =
        "https://github.com/tpeditz/Private-vault-android-v2/releases/latest/download/update.json"

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun sampleValidManifestJson(
        versionCode: Int = 2,
        versionName: String = "2.0.1",
        apkUrl: String = "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.0.1/private-vault.apk",
        sha256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        sizeBytes: Long = 15420100L,
        certSha256: String = expectedCertSha256
    ): String {
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("applicationId", officialAppId)
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkUrl", apkUrl)
            put("sha256", sha256)
            put("sizeBytes", sizeBytes)
            put("publishedAt", "2026-11-25T12:00:00Z")
            put("releaseNotes", "Security update with performance enhancements.")
            put("signingCertSha256", certSha256)
        }.toString()
    }

    private fun createTestOtaManager(
        installedVersionCode: Long = 1L,
        interceptor: Interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }
    ): OtaUpdateManager {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(interceptor)
            .build()
        return OtaUpdateManager(
            context = null,
            installedSignerDigestsProvider = { setOf(expectedCertSha256.replace(":", "").lowercase()) },
            installedVersionCodeProvider = { installedVersionCode },
            archiveInfoParser = { _, _ -> null },
            cacheDirProvider = { tempFolder.root },
            canRequestPackageInstallsProvider = { true },
            httpClient = client
        )
    }

    @Test
    fun testBuildConfigUsesCanonicalReleaseEndpoint() {
        assertEquals(canonicalManifestUrl, BuildConfig.UPDATE_MANIFEST_URL)
        assertEquals("tpeditz/Private-vault-android-v2", BuildConfig.OFFICIAL_REPO)
    }

    @Test
    fun testRealParserAcceptsValidSchemaV1Manifest() {
        val manager = createTestOtaManager()
        val parsed = manager.parseAndValidateManifest(sampleValidManifestJson(versionCode = 2))

        assertEquals(1, parsed.schemaVersion)
        assertEquals(officialAppId, parsed.applicationId)
        assertEquals(2, parsed.versionCode)
        assertEquals("2.0.1", parsed.versionName)
        assertEquals(64, parsed.sha256.length)
        assertEquals(expectedCertSha256, parsed.signingCertSha256)
    }

    @Test
    fun testRealParserRejectsForeignApplicationIdAndWrongSchemaAndUntrustedHost() {
        val manager = createTestOtaManager()

        val foreignApp = JSONObject(sampleValidManifestJson()).apply {
            put("applicationId", "com.malicious.impostor")
        }.toString()
        assertThrowsIllegal { manager.parseAndValidateManifest(foreignApp) }

        val badSchema = JSONObject(sampleValidManifestJson()).apply {
            put("schemaVersion", 99)
        }.toString()
        assertThrowsIllegal { manager.parseAndValidateManifest(badSchema) }

        val badSha = JSONObject(sampleValidManifestJson()).apply {
            put("sha256", "not_a_real_hash")
        }.toString()
        assertThrowsIllegal { manager.parseAndValidateManifest(badSha) }

        val untrustedApkHost = sampleValidManifestJson(
            apkUrl = "https://evil.example.com/private-vault.apk"
        )
        assertThrowsIllegal { manager.parseAndValidateManifest(untrustedApkHost) }

        val wrongCert = sampleValidManifestJson(
            certSha256 = "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"
        )
        assertThrowsIllegal { manager.parseAndValidateManifest(wrongCert) }
    }

    @Test
    fun testCheckForUpdatesHandlesRedirectAndDetectsUpdateOrUpToDate() = runBlocking {
        val manifestJson = sampleValidManifestJson(versionCode = 2, versionName = "2.0.1")
        val manager = createTestOtaManager(installedVersionCode = 1L) { chain ->
            val url = chain.request().url.toString()
            when {
                url == canonicalManifestUrl -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://objects.githubusercontent.com/assets/update.json")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
                url == "https://objects.githubusercontent.com/assets/update.json" -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(manifestJson.toResponseBody("application/json".toMediaType()))
                    .build()
                else -> error("Unexpected URL: $url")
            }
        }

        val state = manager.checkForUpdates(canonicalManifestUrl, isUserInitiated = true)
        assertTrue("Expected UpdateAvailable, got $state", state is OtaState.UpdateAvailable)
        assertEquals(2, (state as OtaState.UpdateAvailable).manifest.versionCode)

        val upToDateManager = createTestOtaManager(installedVersionCode = 2L) { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(manifestJson.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val upToDateState = upToDateManager.checkForUpdates(canonicalManifestUrl, isUserInitiated = true)
        assertTrue("Expected UpToDate, got $upToDateState", upToDateState is OtaState.UpToDate)
    }

    @Test
    fun testCheckForUpdatesHandlesUnpublished404AndNetworkErrorsGracefully() = runBlocking {
        val noReleaseManager = createTestOtaManager()
        val state404 = noReleaseManager.checkForUpdates(canonicalManifestUrl, isUserInitiated = true)
        assertTrue(state404 is OtaState.Error)
        assertEquals("No Release Published Yet", (state404 as OtaState.Error).title)

        val offlineManager = createTestOtaManager { _ ->
            throw IOException("Simulated airplane mode")
        }
        val offlineState = offlineManager.checkForUpdates(canonicalManifestUrl, isUserInitiated = true)
        assertTrue(offlineState is OtaState.Error)
        assertEquals("Connection Error", (offlineState as OtaState.Error).title)
    }

    @Test
    fun testDownloadRejectsUntrustedRedirectAndCorruptedApkPayload() = runBlocking {
        val redirectHijackManifest = OtaUpdateManager(context = null, cacheDirProvider = { tempFolder.root })
            .parseAndValidateManifest(sampleValidManifestJson(versionCode = 3))

        val hijackManager = createTestOtaManager { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://attacker.invalid/malware.apk")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        hijackManager.startDownload(redirectHijackManifest)
        val hijackState = hijackManager.otaState.value
        assertTrue(hijackState is OtaState.Error)
        assertTrue((hijackState as OtaState.Error).detail.contains("host could not be verified", ignoreCase = true))

        val fakeApkBytes = "corrupted-apk-bytes".toByteArray()
        val validManifestForFakeBytes = createTestOtaManager().parseAndValidateManifest(
            sampleValidManifestJson(
                versionCode = 3,
                sha256 = sha256Hex(fakeApkBytes),
                sizeBytes = fakeApkBytes.size.toLong()
            )
        )
        val corruptedSigManager = createTestOtaManager { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(fakeApkBytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                .build()
        }
        corruptedSigManager.startDownload(validManifestForFakeBytes)
        val sigErrorState = corruptedSigManager.otaState.value
        assertTrue("Expected APK verification failure, got $sigErrorState", sigErrorState is OtaState.Error)

        val cachedApks = File(tempFolder.root, "ota_updates").listFiles().orEmpty()
        assertFalse(
            "Failed candidate APK must be deleted cleanly",
            cachedApks.any { it.name.endsWith(".apk") && it.exists() }
        )
    }

    @Test
    fun testManifestRejectsOversizedPayloadAndDownloadCancelResetsToIdle() = runBlocking {
        val giantManifest = JSONObject(sampleValidManifestJson(versionCode = 3)).apply {
            put("sizeBytes", 300L * 1024L * 1024L)
        }.toString()
        val manager = createTestOtaManager()
        assertThrowsIllegal { manager.parseAndValidateManifest(giantManifest) }

        manager.cancelActiveDownload()
        assertTrue(manager.otaState.value is OtaState.Idle)
    }

    @Test
    fun automaticChecksPromptOnlyForNewVersionsAndSupportLater() = runBlocking {
        var version = 4
        val manager = createTestOtaManager(installedVersionCode = 3L) { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(sampleValidManifestJson(versionCode = version, versionName = "2.0.$version")
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }
        val first = manager.checkForUpdates(canonicalManifestUrl, isUserInitiated = false)
        assertTrue((first as OtaState.UpdateAvailable).showPromptDialog)
        manager.dismissUpdatePrompt()
        assertFalse((manager.otaState.value as OtaState.UpdateAvailable).showPromptDialog)
        val again = manager.checkForUpdates(canonicalManifestUrl, isUserInitiated = false)
        assertFalse((again as OtaState.UpdateAvailable).showPromptDialog)
        version = 5
        val newRelease = manager.checkForUpdates(canonicalManifestUrl, isUserInitiated = false)
        assertTrue((newRelease as OtaState.UpdateAvailable).showPromptDialog)
    }

    @Test
    fun permissionTransitionKeepsSameVerifiedFileAndManifest() {
        val manager = createTestOtaManager()
        val manifest = manager.parseAndValidateManifest(sampleValidManifestJson(versionCode = 4))
        val file = File(tempFolder.root, "candidate.apk")
        val certificate = expectedCertSha256.replace(":", "").lowercase()
        val needs = pendingInstallState(manifest, file, certificate, permissionGranted = false)
            as OtaState.NeedsUnknownSourcesPermission
        val ready = pendingInstallState(needs.manifest, needs.verifiedApkFile, certificate, permissionGranted = true)
            as OtaState.ReadyToInstall
        assertEquals(needs.verifiedApkFile, ready.verifiedApkFile)
        assertEquals(needs.manifest, ready.manifest)
        assertEquals(certificate, ready.signerCertificateSha256)
        assertTrue(ready.isPendingInstallation)
    }

    @Test
    fun pendingMetadataSurvivesRecreationButInvalidApkIsNeverRestoredAsReady() = runBlocking {
        val directory = File(tempFolder.root, "ota_updates").apply { mkdirs() }
        val manager = createTestOtaManager(installedVersionCode = 1L)
        val bytes = "not an APK".toByteArray()
        val manifest = manager.parseAndValidateManifest(
            sampleValidManifestJson(versionCode = 4, sha256 = sha256Hex(bytes), sizeBytes = bytes.size.toLong())
        )
        val store = PendingOtaDownloadStore(directory)
        store.apkFile(manifest.versionCode).writeBytes(bytes)
        store.save(manifest)
        val recreatedStore = PendingOtaDownloadStore(directory)
        assertEquals(manifest, manager.parseAndValidateManifest(requireNotNull(recreatedStore.readManifestJson())))
        val recreatedManager = createTestOtaManager(installedVersionCode = 1L)
        val state = recreatedManager.recheckInstallPermissionAndVerifyCandidate()
        assertTrue(state is OtaState.Error)
        assertFalse(recreatedStore.apkFile(manifest.versionCode).exists())
        assertEquals(null, recreatedStore.readManifestJson())
    }

    private inline fun assertThrowsIllegal(block: () -> Unit) {
        try {
            block()
            fail("Expected validation exception")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }
}
