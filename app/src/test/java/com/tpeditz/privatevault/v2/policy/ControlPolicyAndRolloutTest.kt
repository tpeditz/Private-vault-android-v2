package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ControlPolicyAndRolloutTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val validCert = PolicyFormatters.normalizeSha256(BuildConfig.RELEASE_CERT_SHA256)
    private val validApkSha = "a".repeat(64)

    private fun releaseRecord(
        versionCode: Int = 10,
        versionName: String = "2.2.0",
        channel: ReleaseChannel = ReleaseChannel.STABLE,
        state: ReleaseState = ReleaseState.PUBLISHED,
        priority: ReleasePriority = ReleasePriority.OPTIONAL,
        rolloutPercent: Int = 100,
        availableFromEpochMs: Long = 1_759_999_000_000L,
        availableUntilEpochMs: Long? = null,
        apkSha256: String? = validApkSha,
        apkSizeBytes: Long? = 15_000_000L,
        verifiedAtEpochMs: Long? = 1_759_999_000_000L
    ) = ApprovedReleaseRecord(
        id = "rel-$versionCode-${channel.wireValue}",
        versionCode = versionCode,
        versionName = versionName,
        channel = channel,
        state = state,
        priority = priority,
        rolloutPercent = rolloutPercent,
        releaseNotes = "Security hardening release",
        manifestUrl = "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v$versionName/update.json",
        apkSha256 = apkSha256,
        apkSizeBytes = apkSizeBytes,
        signingCertSha256 = validCert,
        verifiedAtEpochMs = verifiedAtEpochMs,
        availableFromEpochMs = availableFromEpochMs,
        availableUntilEpochMs = availableUntilEpochMs
    )

    private fun snapshotOf(
        fetchedAtEpochMs: Long = 1_760_000_000_000L,
        releases: List<ApprovedReleaseRecord> = emptyList(),
        announcements: List<AnnouncementRecord> = emptyList(),
        flags: AllowlistedFeatureFlags = AllowlistedFeatureFlags.SAFE_DEFAULTS,
        revision: Long = 1L
    ) = TrustedControlPolicySnapshot(
        fetchedAtEpochMs = fetchedAtEpochMs,
        sourceOrigin = "https://lszjacrrqthhdapzvjok.supabase.co",
        policyState = ClientPolicyStateRecord(
            singleton = true,
            revision = revision,
            updatedAtEpochMs = fetchedAtEpochMs
        ),
        releases = releases,
        announcements = announcements,
        featureFlags = flags
    )

    @Test
    fun parseIso8601UtcParsesZuluAndOffsetsWithoutJavaTime() {
        val zulu = PolicyFormatters.parseIso8601UtcToEpochMs("2026-11-26T12:00:00Z")
        val withMicros = PolicyFormatters.parseIso8601UtcToEpochMs("2026-11-26T12:00:00.123456+00:00")
        val withPositiveOffset = PolicyFormatters.parseIso8601UtcToEpochMs("2026-11-26T17:30:00+05:30")

        assertNotNull(zulu)
        assertNotNull(withMicros)
        assertEquals(zulu!! + 123L, withMicros!!)
        assertEquals(zulu, withPositiveOffset)
        assertNull(PolicyFormatters.parseIso8601UtcToEpochMs("not-a-timestamp"))
        assertNull(PolicyFormatters.parseIso8601UtcToEpochMs(""))
    }

    @Test
    fun parseSnapshotRejectsUnverifiedPublishedTamperedCertAndDrafts() {
        val releases = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "valid-rel")
                put("version_code", 7)
                put("version_name", "2.1.1")
                put("channel", "stable")
                put("state", "published")
                put("priority", "recommended")
                put("rollout_percent", 50)
                put("manifest_url", "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.1/update.json")
                put("apk_sha256", validApkSha)
                put("apk_size_bytes", 15_000_000L)
                put("signing_cert_sha256", validCert)
                put("verified_at", "2026-11-26T12:00:00Z")
                put("available_from", "2026-11-26T12:00:00Z")
            })
            put(JSONObject().apply {
                put("id", "unverified-pub")
                put("version_code", 8)
                put("version_name", "2.1.2")
                put("channel", "stable")
                put("state", "published")
                put("priority", "critical")
                put("rollout_percent", 100)
                put("manifest_url", "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.2/update.json")
                put("signing_cert_sha256", validCert)
                put("available_from", "2026-11-26T12:00:00Z")
            })
            put(JSONObject().apply {
                put("id", "bad-cert")
                put("version_code", 9)
                put("version_name", "2.1.3")
                put("channel", "stable")
                put("state", "published")
                put("rollout_percent", 100)
                put("manifest_url", "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.3/update.json")
                put("apk_sha256", validApkSha)
                put("apk_size_bytes", 15_000_000L)
                put("verified_at", "2026-11-26T12:00:00Z")
                put("available_from", "2026-11-26T12:00:00Z")
                put("signing_cert_sha256", "0".repeat(64))
            })
            put(JSONObject().apply {
                put("id", "draft-leak")
                put("version_code", 10)
                put("version_name", "2.1.4")
                put("channel", "beta")
                put("state", "draft")
                put("rollout_percent", 100)
                put("manifest_url", "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.4/update.json")
                put("signing_cert_sha256", validCert)
                put("available_from", "2026-11-26T12:00:00Z")
            })
        }

        val parsed = SupabasePolicyClient.parseSnapshot(
            sourceOrigin = "https://lszjacrrqthhdapzvjok.supabase.co",
            fetchedAtEpochMs = 1_760_000_000_000L,
            policyStateArray = JSONArray().put(JSONObject().put("singleton", true).put("revision", 2)),
            releasesArray = releases,
            announcementsArray = JSONArray(),
            flagsArray = JSONArray()
        )

        assertEquals(1, parsed.releases.size)
        assertEquals("valid-rel", parsed.releases.first().id)
        assertEquals(ReleasePriority.RECOMMENDED, parsed.releases.first().priority)
    }

    @Test
    fun rolloutBucketAndPercentageEligibilityAreDeterministicAndBounded() {
        repeat(50) {
            val bucket = PolicyEvaluator.generateRolloutCohortBucket()
            assertTrue(bucket in 0 until 100)
        }

        assertFalse(PolicyEvaluator.isCohortEligible(cohortBucket = 0, rolloutPercent = 0))
        assertTrue(PolicyEvaluator.isCohortEligible(cohortBucket = 99, rolloutPercent = 100))
        assertTrue(PolicyEvaluator.isCohortEligible(cohortBucket = 24, rolloutPercent = 25))
        assertFalse(PolicyEvaluator.isCohortEligible(cohortBucket = 25, rolloutPercent = 25))
    }

    @Test
    fun channelOptInAndPolicyFlagControlBetaVisibility() {
        val betaRel = releaseRecord(versionCode = 12, versionName = "2.2.0", channel = ReleaseChannel.BETA, rolloutPercent = 100)
        val betaAllowedSnap = snapshotOf(
            releases = listOf(betaRel),
            flags = AllowlistedFeatureFlags(betaEnrollmentEnabled = true)
        )
        val betaDisallowedSnap = snapshotOf(
            releases = listOf(betaRel),
            flags = AllowlistedFeatureFlags(betaEnrollmentEnabled = false)
        )
        val now = 1_760_000_000_000L

        assertTrue(
            PolicyEvaluator.evaluateEligibleRelease(betaAllowedSnap, 6, betaChannelOptIn = false, 10, now)
                is OtaEligibilityDecision.NoApprovedRelease
        )
        assertTrue(
            PolicyEvaluator.evaluateEligibleRelease(betaAllowedSnap, 6, betaChannelOptIn = true, 10, now)
                is OtaEligibilityDecision.Eligible
        )
        assertTrue(
            PolicyEvaluator.evaluateEligibleRelease(betaDisallowedSnap, 6, betaChannelOptIn = true, 10, now)
                is OtaEligibilityDecision.NoApprovedRelease
        )
    }

    @Test
    fun schedulePauseWithdrawnAndUnavailableFreshPolicyFailClosed() {
        val now = 1_760_000_000_000L

        val unavailable = PolicyEvaluator.evaluateEligibleRelease(null, 6, false, 0, now)
        assertTrue(unavailable is OtaEligibilityDecision.VerificationUnavailable)
        assertEquals(UNABLE_TO_VERIFY_UPDATE_MESSAGE, (unavailable as OtaEligibilityDecision.VerificationUnavailable).detail)

        val scheduledFuture = snapshotOf(releases = listOf(releaseRecord(availableFromEpochMs = now + 3_600_000L)))
        assertTrue(PolicyEvaluator.evaluateEligibleRelease(scheduledFuture, 6, false, 0, now) is OtaEligibilityDecision.ScheduledDeferred)

        val paused = snapshotOf(releases = listOf(releaseRecord(versionCode = 8, state = ReleaseState.PAUSED)))
        val pauseAuth = PolicyEvaluator.evaluatePendingInstallAuthorization(paused, 8, false, 0, now)
        assertTrue(pauseAuth is InstallAuthorizationDecision.Paused)
        assertEquals(UNABLE_PAUSED_MESSAGE, (pauseAuth as InstallAuthorizationDecision.Paused).detail)

        val withdrawn = snapshotOf(releases = listOf(releaseRecord(versionCode = 8, state = ReleaseState.WITHDRAWN)))
        assertTrue(PolicyEvaluator.evaluatePendingInstallAuthorization(withdrawn, 8, false, 0, now) is InstallAuthorizationDecision.Withdrawn)
    }

    @Test
    fun policyCacheStoreRoundTripsExactSchemaAndChecksDisplayFreshness() {
        val cacheFile = File(tempFolder.root, "ota_updates/control-policy-cache.json")
        val store = PolicyCacheStore(cacheFile)
        val snap = snapshotOf(
            fetchedAtEpochMs = 1_760_000_000_000L,
            releases = listOf(releaseRecord(versionCode = 7, versionName = "2.1.1", priority = ReleasePriority.CRITICAL)),
            flags = AllowlistedFeatureFlags(betaEnrollmentEnabled = true, feedbackEnabled = true, onlineMaintenance = true, updateCheckIntervalHours = 24),
            revision = 9L
        )

        store.writeSnapshot(snap)
        val loaded = store.readSnapshot()
        assertNotNull(loaded)
        assertEquals(9L, loaded!!.policyState.revision)
        assertEquals(24, loaded.featureFlags.updateCheckIntervalHours)
        assertTrue(loaded.featureFlags.betaEnrollmentEnabled)
        assertTrue(loaded.featureFlags.feedbackEnabled)
        assertTrue(loaded.featureFlags.onlineMaintenance)
        assertEquals(ReleasePriority.CRITICAL, loaded.releases.first().priority)

        assertTrue(store.isDisplayCacheFresh(loaded, snap.fetchedAtEpochMs + 1000L))
        assertFalse(store.isDisplayCacheFresh(loaded, snap.fetchedAtEpochMs + TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS + 1L))
    }
}
