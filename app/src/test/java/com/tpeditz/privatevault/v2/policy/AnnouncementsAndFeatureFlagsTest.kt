package com.tpeditz.privatevault.v2.policy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementsAndFeatureFlagsTest {

    @Test
    fun allowlistedFlagsMatchExactFoundationKeysBoundsAndSafeDefaults() {
        val array = JSONArray().apply {
            put(JSONObject().put("flag_key", "update_check_interval_hours").put("integer_value", 2).put("is_published", true))
            put(JSONObject().put("flag_key", "online_maintenance").put("boolean_value", true).put("is_published", true))
            put(JSONObject().put("flag_key", "feedback_enabled").put("boolean_value", true).put("is_published", true))
            put(JSONObject().put("flag_key", "unknown_telemetry_flag").put("boolean_value", true).put("is_published", true))
            put(JSONObject().put("flag_key", "beta_enrollment_enabled").put("boolean_value", true).put("is_published", false))
        }

        val flags = SupabasePolicyClient.parseAllowlistedFlags(array)
        assertEquals(AllowlistedFeatureFlags.MIN_CHECK_INTERVAL_HOURS, flags.updateCheckIntervalHours)
        assertTrue(flags.onlineMaintenance)
        assertTrue(flags.feedbackEnabled)
        // Unpublished flag preserves compiled safe default (false for beta enrollment)
        assertFalse(flags.betaEnrollmentEnabled)

        val upperBound = SupabasePolicyClient.parseAllowlistedFlags(
            JSONArray().put(JSONObject().put("flag_key", "update_check_interval_hours").put("integer_value", 999).put("is_published", true))
        )
        assertEquals(AllowlistedFeatureFlags.MAX_CHECK_INTERVAL_HOURS, upperBound.updateCheckIntervalHours)
    }

    @Test
    fun plainTextSanitizerStripsControlCharactersSafely() {
        val raw = "  Hello\u0000\u0007 World!\nNew line ok.\tTab ok.  "
        val clean = PolicyFormatters.sanitizePlainText(raw, 200)
        assertEquals("Hello World!\nNew line ok.\tTab ok.", clean)
    }

    @Test
    fun announcementTargetingHonorsPresentationScheduleChannelAndOnceVsRepeat() {
        val now = 1_760_000_000_000L
        val aBanner = AnnouncementRecord(
            id = "ann-banner-once",
            title = "Security Notice",
            message = "Update to 2.1.0 is live.",
            presentation = AnnouncementPresentation.BANNER,
            targetChannel = AnnouncementTargetChannel.STABLE,
            minVersionCode = 5,
            maxVersionCode = 8,
            state = AnnouncementState.PUBLISHED,
            repeatMode = AnnouncementRepeatMode.ONCE,
            startsAtEpochMs = now - 10_000L,
            endsAtEpochMs = now + 60_000L
        )
        val aDialog = AnnouncementRecord(
            id = "ann-dialog-repeat",
            title = "Beta Advisory",
            message = "Beta test active.",
            presentation = AnnouncementPresentation.DIALOG,
            targetChannel = AnnouncementTargetChannel.BETA,
            state = AnnouncementState.PUBLISHED,
            repeatMode = AnnouncementRepeatMode.REPEAT,
            startsAtEpochMs = now - 5_000L
        )
        val aDraft = AnnouncementRecord(
            id = "ann-draft",
            title = "Unpublished",
            message = "Should never appear",
            presentation = AnnouncementPresentation.BANNER,
            targetChannel = AnnouncementTargetChannel.ALL,
            state = AnnouncementState.DRAFT,
            repeatMode = AnnouncementRepeatMode.ONCE,
            startsAtEpochMs = now - 5_000L
        )

        val snapshot = TrustedControlPolicySnapshot(
            fetchedAtEpochMs = now,
            sourceOrigin = "https://lszjacrrqthhdapzvjok.supabase.co",
            policyState = ClientPolicyStateRecord(),
            releases = emptyList(),
            announcements = listOf(aBanner, aDialog, aDraft),
            featureFlags = AllowlistedFeatureFlags(betaEnrollmentEnabled = true)
        )

        val stableActive = PolicyEvaluator.selectEffectiveAnnouncements(
            snapshot = snapshot,
            currentVersionCode = 6,
            betaChannelOptIn = false,
            dismissedOnceIds = emptySet(),
            sessionDismissedRepeatIds = emptySet(),
            nowEpochMs = now
        )
        assertEquals(listOf("ann-banner-once"), stableActive.map { it.id })
        assertEquals(AnnouncementPresentation.BANNER, stableActive.first().presentation)

        val betaActive = PolicyEvaluator.selectEffectiveAnnouncements(
            snapshot = snapshot,
            currentVersionCode = 6,
            betaChannelOptIn = true,
            dismissedOnceIds = setOf("ann-banner-once"),
            sessionDismissedRepeatIds = emptySet(),
            nowEpochMs = now
        )
        assertEquals(listOf("ann-dialog-repeat"), betaActive.map { it.id })
        assertEquals(AnnouncementPresentation.DIALOG, betaActive.first().presentation)

        val sessionDismissedBeta = PolicyEvaluator.selectEffectiveAnnouncements(
            snapshot = snapshot,
            currentVersionCode = 6,
            betaChannelOptIn = true,
            dismissedOnceIds = setOf("ann-banner-once"),
            sessionDismissedRepeatIds = setOf("ann-dialog-repeat"),
            nowEpochMs = now
        )
        assertTrue(sessionDismissedBeta.isEmpty())
    }

    @Test
    fun staleDisplaySnapshotRevertsFeatureFlagsToSafeDefaultsWithoutBlockingOfflineUsage() {
        val now = 1_760_000_000_000L
        val stale = TrustedControlPolicySnapshot(
            fetchedAtEpochMs = now - TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS - 1_000L,
            sourceOrigin = "https://lszjacrrqthhdapzvjok.supabase.co",
            policyState = ClientPolicyStateRecord(),
            releases = emptyList(),
            announcements = emptyList(),
            featureFlags = AllowlistedFeatureFlags(
                betaEnrollmentEnabled = true,
                feedbackEnabled = true,
                onlineMaintenance = true,
                updateCheckIntervalHours = 72
            )
        )

        val effective = PolicyEvaluator.effectiveFeatureFlags(stale, now)
        assertFalse(effective.onlineMaintenance)
        assertFalse(effective.betaEnrollmentEnabled)
        assertFalse(effective.feedbackEnabled)
        assertEquals(AllowlistedFeatureFlags.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS, effective.updateCheckIntervalHours)
    }
}
