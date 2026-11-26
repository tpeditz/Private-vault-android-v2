package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.data.AppPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed class OtaCheckPolicyResolution {
    data class FetchApprovedManifest(
        val release: ApprovedReleaseRecord,
        val snapshot: TrustedControlPolicySnapshot
    ) : OtaCheckPolicyResolution()

    data class NoUpdateNeeded(
        val snapshot: TrustedControlPolicySnapshot
    ) : OtaCheckPolicyResolution()

    data class Deferred(
        val title: String,
        val detail: String,
        val channel: ReleaseChannel? = null,
        val versionName: String? = null
    ) : OtaCheckPolicyResolution()
}

interface OtaPolicyGate {
    suspend fun syncPolicySnapshot(forceRefresh: Boolean = true): PolicySyncResult
    suspend fun resolveEligibleCheckTarget(): OtaCheckPolicyResolution
    suspend fun authorizeDownloadOrInstall(
        versionCode: Int,
        forceRefresh: Boolean = true
    ): InstallAuthorizationDecision

    fun currentCachedSnapshot(): TrustedControlPolicySnapshot?
}

/**
 * Production Supabase-backed OTA policy gate implementing strict separation between:
 * 1. Fresh policy confirmation (required before release offer, download, or installer launch)
 * 2. Cached policy display (valid only for non-release offline UI metadata)
 */
class SupabaseOtaPolicyGate(
    private val preferencesRepository: AppPreferencesRepository,
    private val policyClient: SupabasePolicyClient,
    private val cacheStore: PolicyCacheStore,
    private val installedVersionCodeProvider: () -> Long,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val displayCacheMaxAgeMs: Long = TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS
) : OtaPolicyGate {

    override fun currentCachedSnapshot(): TrustedControlPolicySnapshot? {
        val snap = cacheStore.readSnapshot() ?: return null
        return if (cacheStore.isDisplayCacheFresh(snap, clockEpochMs(), displayCacheMaxAgeMs)) {
            snap
        } else {
            null
        }
    }

    override suspend fun syncPolicySnapshot(forceRefresh: Boolean): PolicySyncResult =
        withContext(Dispatchers.IO) {
            val cached = currentCachedSnapshot()
            if (!forceRefresh && cached != null) {
                return@withContext PolicySyncResult.Fresh(cached)
            }

            when (val result = policyClient.fetchTrustedPolicySnapshot()) {
                is SupabasePolicyClient.FetchResult.Success -> {
                    cacheStore.writeSnapshot(result.snapshot)
                    preferencesRepository.setLastPolicySyncEpochMs(result.snapshot.fetchedAtEpochMs)
                    preferencesRepository.setEffectiveOtaCheckIntervalHours(
                        result.snapshot.featureFlags.updateCheckIntervalHours
                    )
                    PolicySyncResult.Fresh(result.snapshot)
                }

                is SupabasePolicyClient.FetchResult.NetworkUnavailable ->
                    PolicySyncResult.NetworkUnavailable(
                        cachedSnapshot = cached,
                        reason = result.reason
                    )

                is SupabasePolicyClient.FetchResult.ServiceUnavailable ->
                    PolicySyncResult.ServiceUnavailable(
                        cachedSnapshot = cached,
                        reason = result.reason
                    )
            }
        }

    override suspend fun resolveEligibleCheckTarget(): OtaCheckPolicyResolution =
        withContext(Dispatchers.IO) {
            val syncOutcome = syncPolicySnapshot(forceRefresh = true)
            val freshSnapshot = (syncOutcome as? PolicySyncResult.Fresh)?.snapshot
            val isNetworkErr = syncOutcome is PolicySyncResult.NetworkUnavailable

            val now = clockEpochMs()
            val cohortBucket = preferencesRepository.getOrCreateRolloutCohortBucket()
            val betaOptIn = preferencesRepository.betaChannelOptIn.first()
            val currentVersionCode = installedVersionCodeProvider().toInt()

            when (val decision = PolicyEvaluator.evaluateEligibleRelease(
                freshSnapshot = freshSnapshot,
                currentVersionCode = currentVersionCode,
                betaChannelOptIn = betaOptIn,
                cohortBucket = cohortBucket,
                nowEpochMs = now,
                isNetworkError = isNetworkErr
            )) {
                is OtaEligibilityDecision.Eligible ->
                    OtaCheckPolicyResolution.FetchApprovedManifest(
                        release = decision.release,
                        snapshot = requireNotNull(freshSnapshot)
                    )

                is OtaEligibilityDecision.NoApprovedRelease ->
                    OtaCheckPolicyResolution.NoUpdateNeeded(requireNotNull(freshSnapshot))

                is OtaEligibilityDecision.Paused ->
                    OtaCheckPolicyResolution.Deferred(
                        title = "Update Unavailable",
                        detail = decision.detail,
                        channel = decision.release.channel,
                        versionName = decision.release.versionName
                    )

                is OtaEligibilityDecision.Withdrawn ->
                    OtaCheckPolicyResolution.Deferred(
                        title = "Update Withdrawn",
                        detail = decision.detail,
                        channel = decision.release.channel,
                        versionName = decision.release.versionName
                    )

                is OtaEligibilityDecision.RolloutDeferred ->
                    OtaCheckPolicyResolution.Deferred(
                        title = "Staged Rollout in Progress",
                        detail = decision.detail,
                        channel = decision.release.channel,
                        versionName = decision.release.versionName
                    )

                is OtaEligibilityDecision.ScheduledDeferred ->
                    OtaCheckPolicyResolution.Deferred(
                        title = "Update Scheduled",
                        detail = decision.detail,
                        channel = decision.release.channel,
                        versionName = decision.release.versionName
                    )

                is OtaEligibilityDecision.MaintenancePaused ->
                    OtaCheckPolicyResolution.Deferred(
                        title = "Online Services in Maintenance",
                        detail = decision.detail
                    )

                is OtaEligibilityDecision.VerificationUnavailable ->
                    OtaCheckPolicyResolution.Deferred(
                        title = decision.title,
                        detail = decision.detail
                    )
            }
        }

    override suspend fun authorizeDownloadOrInstall(
        versionCode: Int,
        forceRefresh: Boolean
    ): InstallAuthorizationDecision = withContext(Dispatchers.IO) {
        // Release-sensitive operations MUST verify fresh live policy unless explicitly non-forced for display recovery.
        val syncOutcome = syncPolicySnapshot(forceRefresh = true)
        val freshSnapshot = (syncOutcome as? PolicySyncResult.Fresh)?.snapshot
        val isNetworkErr = syncOutcome is PolicySyncResult.NetworkUnavailable

        val now = clockEpochMs()
        val cohortBucket = preferencesRepository.getOrCreateRolloutCohortBucket()
        val betaOptIn = preferencesRepository.betaChannelOptIn.first()

        PolicyEvaluator.evaluatePendingInstallAuthorization(
            freshSnapshot = freshSnapshot,
            pendingVersionCode = versionCode,
            betaChannelOptIn = betaOptIn,
            cohortBucket = cohortBucket,
            nowEpochMs = now,
            isNetworkError = isNetworkErr
        )
    }
}
