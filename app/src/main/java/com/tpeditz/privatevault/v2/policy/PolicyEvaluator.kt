package com.tpeditz.privatevault.v2.policy

import java.security.SecureRandom

/**
 * Pure, deterministic evaluator for V2.1.0 release authorization, rollout eligibility,
 * fail-closed install gating, plain-text announcement filtering, and safe feature flags.
 */
object PolicyEvaluator {

    const val COHORT_BUCKET_COUNT = 100

    /**
     * Generates a strictly local random cohort bucket in 0..99.
     * Never derived from hardware/account IDs and never sent to any server.
     */
    fun generateRolloutCohortBucket(random: SecureRandom = SecureRandom()): Int =
        random.nextInt(COHORT_BUCKET_COUNT)

    fun isCohortEligible(cohortBucket: Int, rolloutPercent: Int): Boolean {
        val safeBucket = cohortBucket.coerceIn(0, COHORT_BUCKET_COUNT - 1)
        return when {
            rolloutPercent <= 0 -> false
            rolloutPercent >= 100 -> true
            else -> safeBucket < rolloutPercent
        }
    }

    fun isWithinSchedule(
        startsAtEpochMs: Long?,
        endsAtEpochMs: Long?,
        nowEpochMs: Long
    ): Boolean {
        if (startsAtEpochMs != null && nowEpochMs < startsAtEpochMs) return false
        if (endsAtEpochMs != null && nowEpochMs >= endsAtEpochMs) return false
        return true
    }

    fun effectiveFeatureFlags(
        snapshot: TrustedControlPolicySnapshot?,
        nowEpochMs: Long,
        maxDisplayCacheAgeMs: Long = TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS
    ): AllowlistedFeatureFlags {
        if (snapshot == null || !isDisplaySnapshotFresh(snapshot, nowEpochMs, maxDisplayCacheAgeMs)) {
            return AllowlistedFeatureFlags.SAFE_DEFAULTS
        }
        return snapshot.featureFlags.copy(
            updateCheckIntervalHours = AllowlistedFeatureFlags.clampIntervalHours(
                snapshot.featureFlags.updateCheckIntervalHours
            )
        )
    }

    fun isDisplaySnapshotFresh(
        snapshot: TrustedControlPolicySnapshot?,
        nowEpochMs: Long,
        maxAgeMs: Long = TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS
    ): Boolean {
        if (snapshot == null) return false
        if (snapshot.schemaVersion != TrustedControlPolicySnapshot.CURRENT_SCHEMA_VERSION) return false
        val age = nowEpochMs - snapshot.fetchedAtEpochMs
        if (age < -5L * 60L * 1000L) return false
        return age <= maxAgeMs
    }

    /**
     * Evaluates whether a freshly verified policy snapshot authorizes offering an update.
     * Cached snapshots MUST NOT be passed here for release-sensitive authorization.
     */
    fun evaluateEligibleRelease(
        freshSnapshot: TrustedControlPolicySnapshot?,
        currentVersionCode: Int,
        betaChannelOptIn: Boolean,
        cohortBucket: Int,
        nowEpochMs: Long,
        isNetworkError: Boolean = true
    ): OtaEligibilityDecision {
        if (freshSnapshot == null) {
            return OtaEligibilityDecision.VerificationUnavailable(
                title = "Unable to Verify Update",
                detail = UNABLE_TO_VERIFY_UPDATE_MESSAGE,
                isNetworkError = isNetworkError
            )
        }

        val flags = freshSnapshot.featureFlags
        if (flags.onlineMaintenance) {
            return OtaEligibilityDecision.MaintenancePaused(
                detail = "Online update checks are temporarily paused for maintenance. Your local vault and backups continue working normally."
            )
        }

        val allowedChannels = buildSet {
            add(ReleaseChannel.STABLE)
            if (betaChannelOptIn && flags.betaEnrollmentEnabled) {
                add(ReleaseChannel.BETA)
            }
        }

        val newerInAllowedChannels = freshSnapshot.releases
            .filter { it.versionCode > currentVersionCode && it.channel in allowedChannels }
            .sortedByDescending { it.versionCode }

        if (newerInAllowedChannels.isEmpty()) {
            return OtaEligibilityDecision.NoApprovedRelease(
                reason = "No newer approved release is available for your selected channel."
            )
        }

        var firstDeferred: OtaEligibilityDecision? = null

        for (candidate in newerInAllowedChannels) {
            when (candidate.state) {
                ReleaseState.WITHDRAWN -> {
                    if (firstDeferred == null) {
                        firstDeferred = OtaEligibilityDecision.Withdrawn(
                            release = candidate,
                            detail = "Release v${candidate.versionName} has been withdrawn and will not be offered."
                        )
                    }
                    continue
                }

                ReleaseState.PAUSED -> {
                    if (firstDeferred == null) {
                        firstDeferred = OtaEligibilityDecision.Paused(
                            release = candidate,
                            detail = UNABLE_PAUSED_MESSAGE
                        )
                    }
                    continue
                }

                ReleaseState.DRAFT -> continue

                ReleaseState.PUBLISHED -> Unit
            }

            if (candidate.apkSha256.isNullOrBlank() || candidate.apkSizeBytes == null || candidate.verifiedAtEpochMs == null) {
                continue
            }

            if (!isWithinSchedule(candidate.availableFromEpochMs, candidate.availableUntilEpochMs, nowEpochMs)) {
                if (firstDeferred == null) {
                    firstDeferred = OtaEligibilityDecision.ScheduledDeferred(
                        release = candidate,
                        detail = "Update v${candidate.versionName} is outside its active release window."
                    )
                }
                continue
            }

            if (!isCohortEligible(cohortBucket, candidate.rolloutPercent)) {
                if (firstDeferred == null) {
                    firstDeferred = OtaEligibilityDecision.RolloutDeferred(
                        release = candidate,
                        detail = "Update v${candidate.versionName} is rolling out gradually (${candidate.rolloutPercent}%). Check again later."
                    )
                }
                continue
            }

            return OtaEligibilityDecision.Eligible(candidate)
        }

        return firstDeferred ?: OtaEligibilityDecision.NoApprovedRelease(
            "No newer published release is available."
        )
    }

    /**
     * Re-validates a pending download or installer handoff strictly against a FRESH policy snapshot.
     * Never accepts cached policy after a failed network refresh.
     */
    fun evaluatePendingInstallAuthorization(
        freshSnapshot: TrustedControlPolicySnapshot?,
        pendingVersionCode: Int,
        betaChannelOptIn: Boolean,
        cohortBucket: Int,
        nowEpochMs: Long,
        isNetworkError: Boolean = true
    ): InstallAuthorizationDecision {
        if (freshSnapshot == null) {
            return InstallAuthorizationDecision.VerificationUnavailable(
                title = "Unable to Verify Update",
                detail = UNABLE_TO_VERIFY_UPDATE_MESSAGE,
                isNetworkError = isNetworkError
            )
        }

        val flags = freshSnapshot.featureFlags
        if (flags.onlineMaintenance) {
            return InstallAuthorizationDecision.Deferred(
                title = "Update Temporarily Paused",
                detail = UNABLE_PAUSED_MESSAGE
            )
        }

        val matching = freshSnapshot.releases.firstOrNull { it.versionCode == pendingVersionCode }
            ?: return InstallAuthorizationDecision.Deferred(
                title = "Update No Longer Active",
                detail = "This version is not currently active in the release policy."
            )

        if (matching.channel == ReleaseChannel.BETA &&
            !(betaChannelOptIn && flags.betaEnrollmentEnabled)
        ) {
            return InstallAuthorizationDecision.Deferred(
                title = "Beta Channel Disabled",
                detail = "Beta updates are disabled by policy or your update settings.",
                blockedRelease = matching
            )
        }

        return when (matching.state) {
            ReleaseState.WITHDRAWN -> InstallAuthorizationDecision.Withdrawn(
                release = matching,
                detail = "Version ${matching.versionName} has been withdrawn and installation is blocked."
            )

            ReleaseState.PAUSED -> InstallAuthorizationDecision.Paused(
                release = matching,
                detail = UNABLE_PAUSED_MESSAGE
            )

            ReleaseState.DRAFT -> InstallAuthorizationDecision.Deferred(
                title = "Release Unavailable",
                detail = UNABLE_PAUSED_MESSAGE,
                blockedRelease = matching
            )

            ReleaseState.PUBLISHED -> {
                if (matching.apkSha256.isNullOrBlank() || matching.apkSizeBytes == null || matching.verifiedAtEpochMs == null) {
                    return InstallAuthorizationDecision.Deferred(
                        title = "Verification Required",
                        detail = UNABLE_TO_VERIFY_UPDATE_MESSAGE,
                        blockedRelease = matching
                    )
                }
                if (!isWithinSchedule(matching.availableFromEpochMs, matching.availableUntilEpochMs, nowEpochMs)) {
                    return InstallAuthorizationDecision.Deferred(
                        title = "Outside Release Window",
                        detail = "Version ${matching.versionName} is outside its scheduled window.",
                        blockedRelease = matching
                    )
                }
                if (!isCohortEligible(cohortBucket, matching.rolloutPercent)) {
                    return InstallAuthorizationDecision.Deferred(
                        title = "Rollout Adjusted",
                        detail = "Version ${matching.versionName} rollout is currently ${matching.rolloutPercent}%.",
                        blockedRelease = matching
                    )
                }
                InstallAuthorizationDecision.Authorized(matching)
            }
        }
    }

    fun selectEffectiveAnnouncements(
        snapshot: TrustedControlPolicySnapshot?,
        currentVersionCode: Int,
        betaChannelOptIn: Boolean,
        dismissedOnceIds: Set<String>,
        sessionDismissedRepeatIds: Set<String>,
        nowEpochMs: Long,
        maxDisplayCacheAgeMs: Long = TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS
    ): List<AnnouncementRecord> {
        if (snapshot == null || !isDisplaySnapshotFresh(snapshot, nowEpochMs, maxDisplayCacheAgeMs)) {
            return emptyList()
        }
        val flags = effectiveFeatureFlags(snapshot, nowEpochMs, maxDisplayCacheAgeMs)
        val allowBetaTargeting = betaChannelOptIn && flags.betaEnrollmentEnabled

        return snapshot.announcements.filter { ann ->
            if (ann.state != AnnouncementState.PUBLISHED) return@filter false
            if (!isWithinSchedule(ann.startsAtEpochMs, ann.endsAtEpochMs, nowEpochMs)) return@filter false
            when (ann.targetChannel) {
                AnnouncementTargetChannel.ALL -> Unit
                AnnouncementTargetChannel.STABLE -> if (allowBetaTargeting) return@filter false
                AnnouncementTargetChannel.BETA -> if (!allowBetaTargeting) return@filter false
            }
            if (ann.minVersionCode != null && currentVersionCode < ann.minVersionCode) return@filter false
            if (ann.maxVersionCode != null && currentVersionCode > ann.maxVersionCode) return@filter false
            when (ann.repeatMode) {
                AnnouncementRepeatMode.ONCE -> ann.id !in dismissedOnceIds
                AnnouncementRepeatMode.REPEAT -> ann.id !in sessionDismissedRepeatIds
            }
        }
    }
}
