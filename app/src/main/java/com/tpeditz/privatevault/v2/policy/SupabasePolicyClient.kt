package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Anonymous, zero-telemetry HTTPS client querying the exact tables and columns
 * defined by the authoritative Supabase foundation schema:
 * - `client_policy_state` (`singleton`, `revision`, `updated_at`)
 * - `releases` (`id`, `version_name`, `version_code`, `channel`, `priority`, `state`, `rollout_percent`, `release_notes`, `manifest_url`, `apk_sha256`, `apk_size_bytes`, `signing_cert_sha256`, `verified_at`, `available_from`, `available_until`, `created_at`, `updated_at`)
 * - `announcements` (`id`, `title`, `message`, `presentation`, `target_channel`, `min_version_code`, `max_version_code`, `state`, `repeat_mode`, `starts_at`, `ends_at`, `created_at`, `updated_at`)
 * - `feature_flags` (`flag_key`, `boolean_value`, `integer_value`, `is_published`, `updated_at`)
 */
class SupabasePolicyClient(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val officialRepo: String = BuildConfig.OFFICIAL_REPO,
    private val pinnedSigningCertSha256: String = BuildConfig.RELEASE_CERT_SHA256,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val client: OkHttpClient = defaultHttpClient()
) {

    sealed class FetchResult {
        data class Success(val snapshot: TrustedControlPolicySnapshot) : FetchResult()
        data class NetworkUnavailable(val reason: String, val cause: Throwable? = null) : FetchResult()
        data class ServiceUnavailable(val reason: String, val cause: Throwable? = null) : FetchResult()
    }

    private class HttpErrorException(val code: Int, message: String) : Exception(message)

    fun fetchTrustedPolicySnapshot(): FetchResult {
        val baseUrl = supabaseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: return FetchResult.ServiceUnavailable("Invalid policy service address.")
        if (baseUrl.scheme != "https") {
            return FetchResult.ServiceUnavailable("Policy service must use HTTPS.")
        }
        val key = publishableKey.trim()
        if (key.isBlank()) {
            return FetchResult.ServiceUnavailable("Missing public Supabase client key.")
        }

        return try {
            val policyStateJson = executePublicGet(
                url = "$baseUrl/rest/v1/client_policy_state?select=singleton,revision,updated_at&singleton=eq.true",
                apiKey = key
            )
            val releasesJson = executePublicGet(
                url = "$baseUrl/rest/v1/releases?select=id,version_name,version_code,channel,priority,state,rollout_percent,release_notes,manifest_url,apk_sha256,apk_size_bytes,signing_cert_sha256,verified_at,available_from,available_until,created_at,updated_at&order=version_code.desc",
                apiKey = key
            )
            val announcementsJson = executePublicGet(
                url = "$baseUrl/rest/v1/announcements?select=id,title,message,presentation,target_channel,min_version_code,max_version_code,state,repeat_mode,starts_at,ends_at,created_at,updated_at&state=eq.published&order=starts_at.desc",
                apiKey = key
            )
            val flagsJson = executePublicGet(
                url = "$baseUrl/rest/v1/feature_flags?select=flag_key,boolean_value,integer_value,is_published,updated_at&is_published=eq.true",
                apiKey = key
            )

            val now = clockEpochMs()
            val snapshot = parseSnapshot(
                sourceOrigin = "${baseUrl.scheme}://${baseUrl.host}",
                fetchedAtEpochMs = now,
                policyStateArray = JSONArray(policyStateJson),
                releasesArray = JSONArray(releasesJson),
                announcementsArray = JSONArray(announcementsJson),
                flagsArray = JSONArray(flagsJson),
                officialRepo = officialRepo,
                expectedSigningSha256 = PolicyFormatters.normalizeSha256(pinnedSigningCertSha256)
            )
            FetchResult.Success(snapshot)
        } catch (e: HttpErrorException) {
            FetchResult.ServiceUnavailable("Unable to connect. Please try again.", e)
        } catch (e: IOException) {
            FetchResult.NetworkUnavailable("Unable to connect. Please try again.", e)
        } catch (e: Exception) {
            FetchResult.ServiceUnavailable("Unable to connect. Please try again.", e)
        }
    }

    private fun executePublicGet(url: String, apiKey: String): String {
        val parsed = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Malformed Supabase REST URL")
        if (parsed.scheme != "https") {
            throw IllegalArgumentException("Cleartext policy request rejected")
        }

        val request = Request.Builder()
            .url(parsed)
            .get()
            .header("Accept", "application/json")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", "PrivateVault-Android/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpErrorException(response.code, "Policy service HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty policy response")
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("Policy response exceeded maximum allowed size")
            }
            val bytes = body.source().use { source ->
                source.request(MAX_RESPONSE_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    throw IllegalStateException("Policy payload exceeded $MAX_RESPONSE_BYTES bytes")
                }
                source.readByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
        private const val MAX_APK_BYTES = 262_144_000L
        private val HEX_SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val SEMVER_REGEX = Regex("^[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}$")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun parseSnapshot(
            sourceOrigin: String,
            fetchedAtEpochMs: Long,
            policyStateArray: JSONArray,
            releasesArray: JSONArray,
            announcementsArray: JSONArray,
            flagsArray: JSONArray,
            officialRepo: String = BuildConfig.OFFICIAL_REPO,
            expectedSigningSha256: String = PolicyFormatters.normalizeSha256(BuildConfig.RELEASE_CERT_SHA256)
        ): TrustedControlPolicySnapshot {
            val policyStateObj = if (policyStateArray.length() > 0) policyStateArray.optJSONObject(0) else null
            val policyState = if (policyStateObj != null) {
                ClientPolicyStateRecord(
                    singleton = policyStateObj.optBoolean("singleton", true),
                    revision = policyStateObj.optLong("revision", 1L).coerceAtLeast(1L),
                    updatedAtEpochMs = PolicyFormatters.parseIso8601UtcToEpochMs(
                        optStringOrNull(policyStateObj, "updated_at")
                    )
                )
            } else {
                ClientPolicyStateRecord()
            }

            val expectedManifestPrefix = "https://github.com/$officialRepo/releases/download/"
            val releases = buildList {
                for (i in 0 until releasesArray.length()) {
                    val obj = releasesArray.optJSONObject(i) ?: continue
                    val release = parseValidatedRelease(obj, expectedManifestPrefix, expectedSigningSha256)
                    if (release != null) add(release)
                }
            }

            val announcements = buildList {
                for (i in 0 until announcementsArray.length()) {
                    val obj = announcementsArray.optJSONObject(i) ?: continue
                    val ann = parseAnnouncement(obj)
                    if (ann != null) add(ann)
                }
            }

            val featureFlags = parseAllowlistedFlags(flagsArray)

            return TrustedControlPolicySnapshot(
                schemaVersion = TrustedControlPolicySnapshot.CURRENT_SCHEMA_VERSION,
                fetchedAtEpochMs = fetchedAtEpochMs,
                sourceOrigin = sourceOrigin,
                policyState = policyState,
                releases = releases,
                announcements = announcements,
                featureFlags = featureFlags
            )
        }

        private fun parseValidatedRelease(
            obj: JSONObject,
            expectedManifestPrefix: String,
            expectedSigningSha256: String
        ): ApprovedReleaseRecord? {
            val id = optStringOrNull(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
            val versionCode = obj.optInt("version_code", -1)
            val versionName = PolicyFormatters.sanitizePlainText(optStringOrNull(obj, "version_name"), 64)
            if (versionCode <= 0 || !SEMVER_REGEX.matches(versionName)) return null

            val channel = ReleaseChannel.fromWire(optStringOrNull(obj, "channel")) ?: return null
            val state = ReleaseState.fromWire(optStringOrNull(obj, "state")) ?: return null
            if (state == ReleaseState.DRAFT) return null

            val rolloutPercent = obj.optInt("rollout_percent", -1)
            if (rolloutPercent !in 0..100) return null

            val manifestUrl = optStringOrNull(obj, "manifest_url").orEmpty().trim()
            val expectedManifestUrl = "${expectedManifestPrefix}v${versionName}/update.json"
            if (manifestUrl != expectedManifestUrl) return null

            val cert = PolicyFormatters.normalizeSha256(optStringOrNull(obj, "signing_cert_sha256"))
            if (cert != expectedSigningSha256) return null

            val apkSha = optStringOrNull(obj, "apk_sha256")?.trim()?.lowercase()
            if (apkSha != null && !HEX_SHA256_REGEX.matches(apkSha)) return null

            val apkSize = optPositiveLong(obj, "apk_size_bytes")
            if (apkSize != null && apkSize > MAX_APK_BYTES) return null

            val verifiedAt = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "verified_at"))

            // Foundation DB constraint: published releases MUST carry verified SHA-256, byte size, and verified_at.
            if (state == ReleaseState.PUBLISHED && (apkSha == null || apkSize == null || verifiedAt == null)) {
                return null
            }

            val availableFrom = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "available_from"))
                ?: return null
            val availableUntil = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "available_until"))
            if (availableUntil != null && availableUntil <= availableFrom) return null

            return ApprovedReleaseRecord(
                id = id,
                versionCode = versionCode,
                versionName = versionName,
                channel = channel,
                state = state,
                priority = ReleasePriority.fromWire(optStringOrNull(obj, "priority")),
                rolloutPercent = rolloutPercent,
                releaseNotes = PolicyFormatters.sanitizePlainText(optStringOrNull(obj, "release_notes"), 4000),
                manifestUrl = manifestUrl,
                apkSha256 = apkSha,
                apkSizeBytes = apkSize,
                signingCertSha256 = cert,
                verifiedAtEpochMs = verifiedAt,
                availableFromEpochMs = availableFrom,
                availableUntilEpochMs = availableUntil,
                createdAtEpochMs = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "created_at")),
                updatedAtEpochMs = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "updated_at"))
            )
        }

        private fun parseAnnouncement(obj: JSONObject): AnnouncementRecord? {
            val id = optStringOrNull(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
            val title = PolicyFormatters.sanitizePlainText(optStringOrNull(obj, "title"), 120)
            val message = PolicyFormatters.sanitizePlainText(optStringOrNull(obj, "message"), 2000)
            if (title.isBlank() || message.isBlank()) return null

            val state = AnnouncementState.fromWire(optStringOrNull(obj, "state")) ?: return null
            val startsAt = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "starts_at"))
                ?: return null
            val endsAt = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "ends_at"))
            if (endsAt != null && endsAt <= startsAt) return null

            return AnnouncementRecord(
                id = id,
                title = title,
                message = message,
                presentation = AnnouncementPresentation.fromWire(optStringOrNull(obj, "presentation")),
                targetChannel = AnnouncementTargetChannel.fromWire(optStringOrNull(obj, "target_channel")),
                minVersionCode = optPositiveInt(obj, "min_version_code"),
                maxVersionCode = optPositiveInt(obj, "max_version_code"),
                state = state,
                repeatMode = AnnouncementRepeatMode.fromWire(optStringOrNull(obj, "repeat_mode")),
                startsAtEpochMs = startsAt,
                endsAtEpochMs = endsAt,
                createdAtEpochMs = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "created_at")),
                updatedAtEpochMs = PolicyFormatters.parseIso8601UtcToEpochMs(optStringOrNull(obj, "updated_at"))
            )
        }

        fun parseAllowlistedFlags(flagsArray: JSONArray): AllowlistedFeatureFlags {
            var betaEnrollmentEnabled = AllowlistedFeatureFlags.DEFAULT_BETA_ENROLLMENT_ENABLED
            var feedbackEnabled = AllowlistedFeatureFlags.DEFAULT_FEEDBACK_ENABLED
            var onlineMaintenance = AllowlistedFeatureFlags.DEFAULT_ONLINE_MAINTENANCE
            var checkIntervalHours = AllowlistedFeatureFlags.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS

            for (i in 0 until flagsArray.length()) {
                val obj = flagsArray.optJSONObject(i) ?: continue
                if (!obj.optBoolean("is_published", true)) continue
                when (optStringOrNull(obj, "flag_key")) {
                    AllowlistedFeatureFlags.KEY_BETA_ENROLLMENT_ENABLED -> {
                        if (!obj.isNull("boolean_value")) {
                            betaEnrollmentEnabled = obj.optBoolean("boolean_value", betaEnrollmentEnabled)
                        }
                    }

                    AllowlistedFeatureFlags.KEY_FEEDBACK_ENABLED -> {
                        if (!obj.isNull("boolean_value")) {
                            feedbackEnabled = obj.optBoolean("boolean_value", feedbackEnabled)
                        }
                    }

                    AllowlistedFeatureFlags.KEY_ONLINE_MAINTENANCE -> {
                        if (!obj.isNull("boolean_value")) {
                            onlineMaintenance = obj.optBoolean("boolean_value", onlineMaintenance)
                        }
                    }

                    AllowlistedFeatureFlags.KEY_UPDATE_CHECK_INTERVAL_HOURS -> {
                        if (!obj.isNull("integer_value")) {
                            val rawHours = obj.optInt(
                                "integer_value",
                                AllowlistedFeatureFlags.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
                            )
                            checkIntervalHours = AllowlistedFeatureFlags.clampIntervalHours(rawHours)
                        }
                    }
                    // Unknown keys are strictly ignored for security and forward isolation.
                }
            }

            return AllowlistedFeatureFlags(
                betaEnrollmentEnabled = betaEnrollmentEnabled,
                feedbackEnabled = feedbackEnabled,
                onlineMaintenance = onlineMaintenance,
                updateCheckIntervalHours = checkIntervalHours
            )
        }

        private fun optStringOrNull(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optString(key, "")
            return if (v == "null") null else v
        }

        private fun optPositiveInt(obj: JSONObject, key: String): Int? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optInt(key, -1)
            return if (v > 0) v else null
        }

        private fun optPositiveLong(obj: JSONObject, key: String): Long? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optLong(key, -1L)
            return if (v > 0L) v else null
        }
    }
}
