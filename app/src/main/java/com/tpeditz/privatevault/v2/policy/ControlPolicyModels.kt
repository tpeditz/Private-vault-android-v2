package com.tpeditz.privatevault.v2.policy

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

enum class ReleaseChannel(val wireValue: String, val label: String) {
    STABLE("stable", "Stable"),
    BETA("beta", "Beta");

    companion object {
        fun fromWire(value: String?): ReleaseChannel? = when (value?.trim()?.lowercase(Locale.US)) {
            "stable" -> STABLE
            "beta" -> BETA
            else -> null
        }
    }
}

enum class ReleaseState(val wireValue: String) {
    DRAFT("draft"),
    PUBLISHED("published"),
    PAUSED("paused"),
    WITHDRAWN("withdrawn");

    companion object {
        fun fromWire(value: String?): ReleaseState? = when (value?.trim()?.lowercase(Locale.US)) {
            "draft" -> DRAFT
            "published" -> PUBLISHED
            "paused" -> PAUSED
            "withdrawn" -> WITHDRAWN
            else -> null
        }
    }
}

enum class ReleasePriority(val wireValue: String, val label: String) {
    OPTIONAL("optional", "Optional Update"),
    RECOMMENDED("recommended", "Recommended Update"),
    CRITICAL("critical", "Critical Security Update");

    companion object {
        fun fromWire(value: String?): ReleasePriority = when (value?.trim()?.lowercase(Locale.US)) {
            "recommended" -> RECOMMENDED
            "critical" -> CRITICAL
            "optional" -> OPTIONAL
            else -> OPTIONAL
        }
    }
}

enum class AnnouncementPresentation(val wireValue: String) {
    BANNER("banner"),
    DIALOG("dialog");

    companion object {
        fun fromWire(value: String?): AnnouncementPresentation = when (value?.trim()?.lowercase(Locale.US)) {
            "dialog" -> DIALOG
            else -> BANNER
        }
    }
}

enum class AnnouncementTargetChannel(val wireValue: String) {
    ALL("all"),
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromWire(value: String?): AnnouncementTargetChannel = when (value?.trim()?.lowercase(Locale.US)) {
            "stable" -> STABLE
            "beta" -> BETA
            else -> ALL
        }
    }
}

enum class AnnouncementState(val wireValue: String) {
    DRAFT("draft"),
    PUBLISHED("published"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(value: String?): AnnouncementState? = when (value?.trim()?.lowercase(Locale.US)) {
            "draft" -> DRAFT
            "published" -> PUBLISHED
            "archived" -> ARCHIVED
            else -> null
        }
    }
}

enum class AnnouncementRepeatMode(val wireValue: String) {
    ONCE("once"),
    REPEAT("repeat");

    companion object {
        fun fromWire(value: String?): AnnouncementRepeatMode = when (value?.trim()?.lowercase(Locale.US)) {
            "repeat" -> REPEAT
            else -> ONCE
        }
    }
}

/**
 * Mirrors the exact columns of `public.releases` from the authoritative foundation schema.
 */
data class ApprovedReleaseRecord(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    val channel: ReleaseChannel,
    val priority: ReleasePriority,
    val state: ReleaseState,
    val rolloutPercent: Int,
    val releaseNotes: String,
    val manifestUrl: String,
    val apkSha256: String?,
    val apkSizeBytes: Long?,
    val signingCertSha256: String,
    val verifiedAtEpochMs: Long?,
    val availableFromEpochMs: Long,
    val availableUntilEpochMs: Long? = null,
    val createdAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long? = null
)

/**
 * Mirrors the exact columns of `public.announcements` from the authoritative foundation schema.
 */
data class AnnouncementRecord(
    val id: String,
    val title: String,
    val message: String,
    val presentation: AnnouncementPresentation = AnnouncementPresentation.BANNER,
    val targetChannel: AnnouncementTargetChannel = AnnouncementTargetChannel.ALL,
    val minVersionCode: Int? = null,
    val maxVersionCode: Int? = null,
    val state: AnnouncementState = AnnouncementState.PUBLISHED,
    val repeatMode: AnnouncementRepeatMode = AnnouncementRepeatMode.ONCE,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long? = null,
    val createdAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long? = null
)

/**
 * Exact 4 allowlisted keys in `public.feature_flags`:
 * - beta_enrollment_enabled (default false)
 * - feedback_enabled (default false)
 * - online_maintenance (default false)
 * - update_check_interval_hours (default 12, clamped 6..72)
 */
data class AllowlistedFeatureFlags(
    val betaEnrollmentEnabled: Boolean = DEFAULT_BETA_ENROLLMENT_ENABLED,
    val feedbackEnabled: Boolean = DEFAULT_FEEDBACK_ENABLED,
    val onlineMaintenance: Boolean = DEFAULT_ONLINE_MAINTENANCE,
    val updateCheckIntervalHours: Int = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
) {
    companion object {
        const val KEY_BETA_ENROLLMENT_ENABLED = "beta_enrollment_enabled"
        const val KEY_FEEDBACK_ENABLED = "feedback_enabled"
        const val KEY_ONLINE_MAINTENANCE = "online_maintenance"
        const val KEY_UPDATE_CHECK_INTERVAL_HOURS = "update_check_interval_hours"

        const val DEFAULT_BETA_ENROLLMENT_ENABLED = false
        const val DEFAULT_FEEDBACK_ENABLED = false
        const val DEFAULT_ONLINE_MAINTENANCE = false
        const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
        const val MIN_CHECK_INTERVAL_HOURS = 6
        const val MAX_CHECK_INTERVAL_HOURS = 72

        val SAFE_DEFAULTS = AllowlistedFeatureFlags()

        fun clampIntervalHours(hours: Int): Int =
            min(MAX_CHECK_INTERVAL_HOURS, max(MIN_CHECK_INTERVAL_HOURS, hours))
    }
}

/**
 * Mirrors the exact columns of `public.client_policy_state`:
 * - singleton (boolean PK)
 * - revision (bigint)
 * - updated_at (timestamptz)
 */
data class ClientPolicyStateRecord(
    val singleton: Boolean = true,
    val revision: Long = 1L,
    val updatedAtEpochMs: Long? = null
)

data class TrustedControlPolicySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val fetchedAtEpochMs: Long,
    val sourceOrigin: String,
    val policyState: ClientPolicyStateRecord,
    val releases: List<ApprovedReleaseRecord>,
    val announcements: List<AnnouncementRecord>,
    val featureFlags: AllowlistedFeatureFlags
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val DEFAULT_DISPLAY_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

/**
 * Explicitly separates fresh live Supabase policy confirmation from offline cached display state.
 */
sealed class PolicySyncResult {
    data class Fresh(val snapshot: TrustedControlPolicySnapshot) : PolicySyncResult()
    data class NetworkUnavailable(
        val cachedSnapshot: TrustedControlPolicySnapshot?,
        val reason: String
    ) : PolicySyncResult()
    data class ServiceUnavailable(
        val cachedSnapshot: TrustedControlPolicySnapshot?,
        val reason: String
    ) : PolicySyncResult()

    val anySnapshotForDisplay: TrustedControlPolicySnapshot?
        get() = when (this) {
            is Fresh -> snapshot
            is NetworkUnavailable -> cachedSnapshot
            is ServiceUnavailable -> cachedSnapshot
        }
}

sealed class OtaEligibilityDecision {
    data class Eligible(
        val release: ApprovedReleaseRecord
    ) : OtaEligibilityDecision()

    data class NoApprovedRelease(
        val reason: String
    ) : OtaEligibilityDecision()

    data class Paused(
        val release: ApprovedReleaseRecord,
        val detail: String = UNABLE_PAUSED_MESSAGE
    ) : OtaEligibilityDecision()

    data class Withdrawn(
        val release: ApprovedReleaseRecord,
        val detail: String
    ) : OtaEligibilityDecision()

    data class RolloutDeferred(
        val release: ApprovedReleaseRecord,
        val detail: String
    ) : OtaEligibilityDecision()

    data class ScheduledDeferred(
        val release: ApprovedReleaseRecord,
        val detail: String
    ) : OtaEligibilityDecision()

    data class MaintenancePaused(
        val detail: String
    ) : OtaEligibilityDecision()

    data class VerificationUnavailable(
        val title: String = "Unable to Verify Update",
        val detail: String = UNABLE_TO_VERIFY_UPDATE_MESSAGE,
        val isNetworkError: Boolean = true
    ) : OtaEligibilityDecision()
}

sealed class InstallAuthorizationDecision {
    data class Authorized(
        val release: ApprovedReleaseRecord
    ) : InstallAuthorizationDecision()

    data class Paused(
        val release: ApprovedReleaseRecord,
        val detail: String = UNABLE_PAUSED_MESSAGE
    ) : InstallAuthorizationDecision()

    data class Withdrawn(
        val release: ApprovedReleaseRecord,
        val detail: String
    ) : InstallAuthorizationDecision()

    data class Deferred(
        val title: String,
        val detail: String,
        val blockedRelease: ApprovedReleaseRecord? = null
    ) : InstallAuthorizationDecision()

    data class VerificationUnavailable(
        val title: String = "Unable to Verify Update",
        val detail: String = UNABLE_TO_VERIFY_UPDATE_MESSAGE,
        val isNetworkError: Boolean = true
    ) : InstallAuthorizationDecision()
}

const val UNABLE_TO_VERIFY_UPDATE_MESSAGE = "Unable to verify the update right now. Please try again later."
const val UNABLE_PAUSED_MESSAGE = "This update is temporarily unavailable."

data class PolicyFeedbackState(
    val isSupportedByBuild: Boolean,
    val isEnabledByServer: Boolean,
    val isMaintenanceActive: Boolean
) {
    val isSubmissionAllowed: Boolean
        get() = isSupportedByBuild && isEnabledByServer && !isMaintenanceActive

    val disabledReason: String?
        get() = when {
            !isSupportedByBuild ->
                "Feedback submission is disabled pending server endpoint deployment."
            !isEnabledByServer ->
                "Feedback submission is currently disabled by administrator policy."
            isMaintenanceActive ->
                "Feedback submission is paused during online maintenance."
            else -> null
        }
}

data class FeedbackDraftValidation(
    val isValid: Boolean,
    val errorMessage: String? = null
)

object PolicyFormatters {

    val ALLOWED_FEEDBACK_CATEGORIES = setOf("bug", "suggestion", "other")
    const val MIN_FEEDBACK_LENGTH = 1
    const val MAX_FEEDBACK_LENGTH = 3000
    const val MAX_CONTACT_LENGTH = 254
    const val MAX_APP_VERSION_LENGTH = 30

    fun normalizeSha256(raw: String?): String =
        raw.orEmpty()
            .trim()
            .replace(":", "")
            .replace(" ", "")
            .lowercase(Locale.US)

    /**
     * Sanitizes plain-text control-plane strings so control characters never corrupt layout,
     * while preserving printable text cleanly (Compose Text renders literal text without HTML execution).
     */
    fun sanitizePlainText(raw: String?, maxLength: Int = 2000): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(min(raw.length, maxLength))
        for (ch in raw.trim()) {
            if (sb.length >= maxLength) break
            when {
                ch == '\n' || ch == '\t' -> sb.append(ch)
                ch < ' ' || ch == '\u007F' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * API-24 safe UTC ISO-8601 timestamp parser (does not depend on java.time.Instant).
     */
    fun parseIso8601UtcToEpochMs(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val match = ISO_REGEX.matchEntire(text) ?: return null
        val datePart = match.groupValues[1]
        val timePart = match.groupValues[2]
        val fracPart = match.groupValues[3]
        val tzPart = match.groupValues[4]

        val millis = if (fracPart.isEmpty()) {
            "000"
        } else {
            (fracPart + "000").substring(0, 3)
        }

        val normalizedTz = when {
            tzPart == "Z" || tzPart == "z" -> "+0000"
            else -> tzPart.replace(":", "")
        }

        val canonical = "${datePart}T${timePart}.${millis}${normalizedTz}"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(canonical)?.time
        } catch (_: Exception) {
            null
        }
    }

    fun validateFeedbackInput(
        category: String,
        message: String,
        contact: String?,
        consentChecked: Boolean
    ): FeedbackDraftValidation {
        if (!consentChecked) {
            return FeedbackDraftValidation(
                isValid = false,
                errorMessage = "Please confirm explicit consent before preparing feedback."
            )
        }
        if (category.trim().lowercase(Locale.US) !in ALLOWED_FEEDBACK_CATEGORIES) {
            return FeedbackDraftValidation(
                isValid = false,
                errorMessage = "Choose a valid category: Bug, Suggestion, or Other."
            )
        }
        val cleanMsg = sanitizePlainText(message, MAX_FEEDBACK_LENGTH + 1)
        if (cleanMsg.length < MIN_FEEDBACK_LENGTH) {
            return FeedbackDraftValidation(
                isValid = false,
                errorMessage = "Enter a message between 1 and $MAX_FEEDBACK_LENGTH characters."
            )
        }
        if (cleanMsg.length > MAX_FEEDBACK_LENGTH) {
            return FeedbackDraftValidation(
                isValid = false,
                errorMessage = "Feedback message cannot exceed $MAX_FEEDBACK_LENGTH characters."
            )
        }
        val cleanContact = contact?.let { sanitizePlainText(it, MAX_CONTACT_LENGTH + 1) }.orEmpty()
        if (cleanContact.length > MAX_CONTACT_LENGTH) {
            return FeedbackDraftValidation(
                isValid = false,
                errorMessage = "Optional contact text cannot exceed $MAX_CONTACT_LENGTH characters."
            )
        }
        return FeedbackDraftValidation(isValid = true)
    }

    private val ISO_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})$"""
    )
}
