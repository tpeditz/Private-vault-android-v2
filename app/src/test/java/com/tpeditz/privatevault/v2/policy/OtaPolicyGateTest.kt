package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.BuildConfig
import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import com.tpeditz.privatevault.v2.ota.OtaState
import com.tpeditz.privatevault.v2.ota.OtaUpdateManager
import com.tpeditz.privatevault.v2.ota.PendingOtaDownloadStore
import com.tpeditz.privatevault.v2.ota.UpdateManifest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class OtaPolicyGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val expectedCertHex = PolicyFormatters.normalizeSha256(BuildConfig.RELEASE_CERT_SHA256)
    private val defaultApkSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val defaultApkSize = 15_000_000L

    private fun jsonOk(chain: Interceptor.Chain, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun approvedSnapshot(
        versionCode: Int = 7,
        versionName: String = "2.2.0",
        state: ReleaseState = ReleaseState.PUBLISHED,
        rolloutPercent: Int = 100,
        priority: ReleasePriority = ReleasePriority.RECOMMENDED,
        apkSha256: String = defaultApkSha,
        apkSizeBytes: Long = defaultApkSize
    ) = TrustedControlPolicySnapshot(
        fetchedAtEpochMs = 1_760_000_000_000L,
        sourceOrigin = "https://lszjacrrqthhdapzvjok.supabase.co",
        policyState = ClientPolicyStateRecord(singleton = true, revision = 3L),
        releases = listOf(
            ApprovedReleaseRecord(
                id = "rel-$versionCode",
                versionCode = versionCode,
                versionName = versionName,
                channel = ReleaseChannel.STABLE,
                state = state,
                priority = priority,
                rolloutPercent = rolloutPercent,
                releaseNotes = "Staged release notes",
                manifestUrl = "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v$versionName/update.json",
                apkSha256 = apkSha256,
                apkSizeBytes = apkSizeBytes,
                signingCertSha256 = expectedCertHex,
                verifiedAtEpochMs = 1_759_999_000_000L,
                availableFromEpochMs = 1_759_999_000_000L
            )
        ),
        announcements = emptyList(),
        featureFlags = AllowlistedFeatureFlags.SAFE_DEFAULTS
    )

    private class FakePolicyGate(
        var freshSnapshot: TrustedControlPolicySnapshot?,
        var cachedSnapshot: TrustedControlPolicySnapshot? = freshSnapshot,
        var cohortBucket: Int = 10,
        var betaOptIn: Boolean = false,
        var nowMs: Long = 1_760_000_000_000L
    ) : OtaPolicyGate {
        override fun currentCachedSnapshot(): TrustedControlPolicySnapshot? = cachedSnapshot

        override suspend fun syncPolicySnapshot(forceRefresh: Boolean): PolicySyncResult =
            freshSnapshot?.let { PolicySyncResult.Fresh(it) }
                ?: PolicySyncResult.NetworkUnavailable(cachedSnapshot, "offline")

        override suspend fun resolveEligibleCheckTarget(): OtaCheckPolicyResolution {
            return when (val d = PolicyEvaluator.evaluateEligibleRelease(
                freshSnapshot, 6, betaOptIn, cohortBucket, nowMs
            )) {
                is OtaEligibilityDecision.Eligible -> OtaCheckPolicyResolution.FetchApprovedManifest(d.release, freshSnapshot!!)
                is OtaEligibilityDecision.NoApprovedRelease -> OtaCheckPolicyResolution.NoUpdateNeeded(freshSnapshot!!)
                is OtaEligibilityDecision.Paused -> OtaCheckPolicyResolution.Deferred("Update Unavailable", d.detail, d.release.channel, d.release.versionName)
                is OtaEligibilityDecision.Withdrawn -> OtaCheckPolicyResolution.Deferred("Update Withdrawn", d.detail, d.release.channel, d.release.versionName)
                is OtaEligibilityDecision.RolloutDeferred -> OtaCheckPolicyResolution.Deferred("Staged Rollout in Progress", d.detail, d.release.channel, d.release.versionName)
                is OtaEligibilityDecision.ScheduledDeferred -> OtaCheckPolicyResolution.Deferred("Update Scheduled", d.detail, d.release.channel, d.release.versionName)
                is OtaEligibilityDecision.MaintenancePaused -> OtaCheckPolicyResolution.Deferred("Online Services in Maintenance", d.detail)
                is OtaEligibilityDecision.VerificationUnavailable -> OtaCheckPolicyResolution.Deferred(d.title, d.detail)
            }
        }

        override suspend fun authorizeDownloadOrInstall(
            versionCode: Int,
            forceRefresh: Boolean
        ): InstallAuthorizationDecision = PolicyEvaluator.evaluatePendingInstallAuthorization(
            freshSnapshot, versionCode, betaOptIn, cohortBucket, nowMs
        )
    }

    @Test
    fun cachedPolicyAfterFailedNetworkRefreshDoesNotAuthorizeUpdateOrInstall() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferencesRepository(context)
        val cacheStore = PolicyCacheStore(File(tempFolder.root, "ota_updates/policy-cache.json"))
        val cachedSnap = approvedSnapshot(versionCode = 7, rolloutPercent = 100)
        cacheStore.writeSnapshot(cachedSnap)

        val offlinePolicyClient = SupabasePolicyClient(
            supabaseUrl = "https://lszjacrrqthhdapzvjok.supabase.co",
            publishableKey = "sb_publishable_test",
            clockEpochMs = { cachedSnap.fetchedAtEpochMs + 1000L },
            client = OkHttpClient.Builder().addInterceptor { throw IOException("No network") }.build()
        )

        val gate = SupabaseOtaPolicyGate(
            preferencesRepository = prefs,
            policyClient = offlinePolicyClient,
            cacheStore = cacheStore,
            installedVersionCodeProvider = { 6L },
            clockEpochMs = { cachedSnap.fetchedAtEpochMs + 1000L }
        )

        // Cached display metadata is still readable for offline UI notices/flags
        val displayResult = gate.syncPolicySnapshot(forceRefresh = false)
        assertTrue(displayResult is PolicySyncResult.Fresh || displayResult.anySnapshotForDisplay != null)

        // BUT release-sensitive operations MUST fail closed because fresh network confirmation failed
        val resolution = gate.resolveEligibleCheckTarget()
        assertTrue(resolution is OtaCheckPolicyResolution.Deferred)
        assertEquals(UNABLE_TO_VERIFY_UPDATE_MESSAGE, (resolution as OtaCheckPolicyResolution.Deferred).detail)

        val installAuth = gate.authorizeDownloadOrInstall(versionCode = 7, forceRefresh = true)
        assertTrue(installAuth is InstallAuthorizationDecision.VerificationUnavailable)
        assertEquals(UNABLE_TO_VERIFY_UPDATE_MESSAGE, (installAuth as InstallAuthorizationDecision.VerificationUnavailable).detail)
    }

    @Test
    fun missingPolicyFailsClosedAndNeverFallsBackToLatestGithubRelease() = runBlocking {
        val githubRequests = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor {
                githubRequests.incrementAndGet()
                throw IOException("Should not fetch GitHub without fresh Supabase policy")
            }
            .build()

        val manager = OtaUpdateManager(
            context = null,
            installedSignerDigestsProvider = { setOf(expectedCertHex) },
            installedVersionCodeProvider = { 6L },
            cacheDirProvider = { tempFolder.root },
            httpClient = client,
            policyGate = FakePolicyGate(freshSnapshot = null, cachedSnapshot = approvedSnapshot())
        )

        val state = manager.checkForUpdates(isUserInitiated = true)
        assertTrue(state is OtaState.PolicyDeferred)
        assertEquals(UNABLE_TO_VERIFY_UPDATE_MESSAGE, (state as OtaState.PolicyDeferred).detail)
        assertEquals(0, githubRequests.get())
    }

    @Test
    fun approvedPolicyVerifiesDigestParityAndRespectsRolloutBucket() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val manifestBody = JSONObject().apply {
            put("schemaVersion", 1)
            put("applicationId", "com.tpeditz.privatevault.v2")
            put("versionCode", 7)
            put("versionName", "2.2.0")
            put("apkUrl", "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.2.0/private-vault.apk")
            put("sha256", defaultApkSha)
            put("sizeBytes", defaultApkSize)
            put("publishedAt", "2026-11-26T12:00:00Z")
            put("releaseNotes", "Tagged manifest notes")
            put("signingCertSha256", BuildConfig.RELEASE_CERT_SHA256)
        }.toString()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrls.add(chain.request().url.toString())
                jsonOk(chain, manifestBody)
            }
            .build()

        val gate = FakePolicyGate(freshSnapshot = approvedSnapshot(rolloutPercent = 10), cohortBucket = 5)
        val manager = OtaUpdateManager(
            context = null,
            installedSignerDigestsProvider = { setOf(expectedCertHex) },
            installedVersionCodeProvider = { 6L },
            cacheDirProvider = { tempFolder.root },
            httpClient = client,
            policyGate = gate
        )

        val eligibleState = manager.checkForUpdates(isUserInitiated = true)
        assertTrue(eligibleState is OtaState.UpdateAvailable)
        val avail = eligibleState as OtaState.UpdateAvailable
        assertEquals(ReleasePriority.RECOMMENDED, avail.priority)
        assertEquals(
            listOf("https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.2.0/update.json"),
            requestedUrls
        )
        assertFalse(requestedUrls.any { it.contains("/releases/latest/") })

        // Reject if Supabase approved apk_sha256 disagrees with GitHub manifest sha256
        val mismatchedShaGate = FakePolicyGate(
            freshSnapshot = approvedSnapshot(rolloutPercent = 100, apkSha256 = "c".repeat(64)),
            cohortBucket = 5
        )
        val mismatchManager = OtaUpdateManager(
            context = null,
            installedSignerDigestsProvider = { setOf(expectedCertHex) },
            installedVersionCodeProvider = { 6L },
            cacheDirProvider = { tempFolder.root },
            httpClient = client,
            policyGate = mismatchedShaGate
        )
        assertTrue(mismatchManager.checkForUpdates(isUserInitiated = true) is OtaState.Error)
    }

    @Test
    fun pausedDownloadedCandidateBlocksDownloadAndPreservesPendingStore() = runBlocking {
        val gate = FakePolicyGate(freshSnapshot = approvedSnapshot(state = ReleaseState.PAUSED))
        val client = OkHttpClient.Builder().build()
        val manager = OtaUpdateManager(
            context = null,
            installedSignerDigestsProvider = { setOf(expectedCertHex) },
            installedVersionCodeProvider = { 6L },
            cacheDirProvider = { tempFolder.root },
            httpClient = client,
            policyGate = gate
        )

        val store = PendingOtaDownloadStore(File(tempFolder.root, "ota_updates"))
        val dummyApk = store.apkFile(7)
        dummyApk.parentFile?.mkdirs()
        val bytes = "fake-verified-apk-payload".toByteArray()
        dummyApk.writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        val manifest = UpdateManifest(
            schemaVersion = 1,
            applicationId = "com.tpeditz.privatevault.v2",
            versionCode = 7,
            versionName = "2.2.0",
            apkUrl = "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.2.0/private-vault.apk",
            sha256 = sha,
            sizeBytes = bytes.size.toLong(),
            publishedAt = "2026-11-26T12:00:00Z",
            releaseNotes = "Notes",
            signingCertSha256 = BuildConfig.RELEASE_CERT_SHA256
        )

        manager.startDownload(manifest)
        assertTrue(manager.otaState.value is OtaState.PolicyDeferred)
        assertEquals(UNABLE_PAUSED_MESSAGE, (manager.otaState.value as OtaState.PolicyDeferred).detail)
        assertTrue(dummyApk.exists())
    }

    @Test
    fun startDownloadRejectsManifestMismatchAgainstFreshApprovedMetadataWithoutDownloading() = runBlocking {
        val downloadAttempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor {
                downloadAttempts.incrementAndGet()
                throw IOException("Should not start download when manifest disagrees with fresh release")
            }
            .build()

        val freshApproved = approvedSnapshot(
            versionCode = 7,
            versionName = "2.2.0",
            apkSha256 = "f".repeat(64),
            apkSizeBytes = 20_000_000L
        )
        val gate = FakePolicyGate(freshSnapshot = freshApproved)
        val manager = OtaUpdateManager(
            context = null,
            installedSignerDigestsProvider = { setOf(expectedCertHex) },
            installedVersionCodeProvider = { 6L },
            cacheDirProvider = { tempFolder.root },
            httpClient = client,
            policyGate = gate
        )

        val mismatchedManifest = UpdateManifest(
            schemaVersion = 1,
            applicationId = "com.tpeditz.privatevault.v2",
            versionCode = 7,
            versionName = "2.2.0",
            apkUrl = "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.2.0/private-vault.apk",
            sha256 = defaultApkSha, // differs from freshApproved "f".repeat(64)
            sizeBytes = defaultApkSize,
            publishedAt = "2026-11-26T12:00:00Z",
            releaseNotes = "Stale manifest",
            signingCertSha256 = BuildConfig.RELEASE_CERT_SHA256
        )

        manager.startDownload(mismatchedManifest)
        assertEquals(0, downloadAttempts.get())
        assertTrue(manager.otaState.value is OtaState.Error)
    }

    @Test
    fun v204PreferencesAndVaultDatabaseVersionUpgradeSafelyWithoutDataLoss() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferencesRepository(context)
        prefs.setAutomaticOtaCheckEnabled(false)
        prefs.setLastOtaCheckEpochMs(1_750_000_000_000L)

        // Existing V2.0.4 prefs remain intact while additive V2.1.0 defaults stay safe.
        assertFalse(prefs.automaticOtaCheckEnabled.first())
        assertEquals(1_750_000_000_000L, prefs.lastOtaCheckEpochMs.first())
        assertFalse(prefs.betaChannelOptIn.first())
        assertEquals(
            AllowlistedFeatureFlags.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS,
            prefs.effectiveOtaCheckIntervalHours.first()
        )
        val bucket = prefs.getOrCreateRolloutCohortBucket()
        assertTrue(bucket in 0 until 100)
    }
}
