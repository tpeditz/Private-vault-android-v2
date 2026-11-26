package com.tpeditz.privatevault.v2.policy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Atomic JSON cache for the last verified Supabase Control Center policy snapshot.
 * Used strictly for displaying previously retrieved non-release-critical metadata (notices/flags)
 * when offline; release authorization ALWAYS requires a fresh network sync.
 */
class PolicyCacheStore(
    private val cacheFile: File
) {

    fun readSnapshot(): TrustedControlPolicySnapshot? {
        if (!cacheFile.isFile) return null
        return try {
            val raw = cacheFile.readText(Charsets.UTF_8)
            fromJson(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun writeSnapshot(snapshot: TrustedControlPolicySnapshot) {
        val parent = cacheFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent ?: cacheFile.absoluteFile.parentFile, "${cacheFile.name}.tmp")
        val payload = toJson(snapshot)
        tmpFile.writeText(payload, Charsets.UTF_8)
        if (!tmpFile.renameTo(cacheFile)) {
            cacheFile.writeText(payload, Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    fun clear() {
        runCatching { cacheFile.delete() }
    }

    fun isDisplayCacheFresh(
        snapshot: TrustedControlPolicySnapshot?,
        nowEpochMs: Long,
        maxAgeMs: Long = TrustedControlPolicySnapshot.DEFAULT_DISPLAY_CACHE_TTL_MS
    ): Boolean {
        if (snapshot == null) return false
        if (snapshot.schemaVersion != TrustedControlPolicySnapshot.CURRENT_SCHEMA_VERSION) return false
        val ageMs = nowEpochMs - snapshot.fetchedAtEpochMs
        if (ageMs < -MAX_CLOCK_SKEW_MS) return false
        return ageMs <= maxAgeMs
    }

    companion object {
        private const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L

        fun toJson(snapshot: TrustedControlPolicySnapshot): String {
            val root = JSONObject().apply {
                put("schemaVersion", snapshot.schemaVersion)
                put("fetchedAtEpochMs", snapshot.fetchedAtEpochMs)
                put("sourceOrigin", snapshot.sourceOrigin)
                put("policyState", JSONObject().apply {
                    put("singleton", snapshot.policyState.singleton)
                    put("revision", snapshot.policyState.revision)
                    snapshot.policyState.updatedAtEpochMs?.let { put("updatedAtEpochMs", it) }
                })
                put("featureFlags", JSONObject().apply {
                    put("betaEnrollmentEnabled", snapshot.featureFlags.betaEnrollmentEnabled)
                    put("feedbackEnabled", snapshot.featureFlags.feedbackEnabled)
                    put("onlineMaintenance", snapshot.featureFlags.onlineMaintenance)
                    put("updateCheckIntervalHours", snapshot.featureFlags.updateCheckIntervalHours)
                })
                put("releases", JSONArray().apply {
                    snapshot.releases.forEach { rel ->
                        put(JSONObject().apply {
                            put("id", rel.id)
                            put("versionCode", rel.versionCode)
                            put("versionName", rel.versionName)
                            put("channel", rel.channel.wireValue)
                            put("priority", rel.priority.wireValue)
                            put("state", rel.state.wireValue)
                            put("rolloutPercent", rel.rolloutPercent)
                            put("releaseNotes", rel.releaseNotes)
                            put("manifestUrl", rel.manifestUrl)
                            rel.apkSha256?.let { put("apkSha256", it) }
                            rel.apkSizeBytes?.let { put("apkSizeBytes", it) }
                            put("signingCertSha256", rel.signingCertSha256)
                            rel.verifiedAtEpochMs?.let { put("verifiedAtEpochMs", it) }
                            put("availableFromEpochMs", rel.availableFromEpochMs)
                            rel.availableUntilEpochMs?.let { put("availableUntilEpochMs", it) }
                            rel.createdAtEpochMs?.let { put("createdAtEpochMs", it) }
                            rel.updatedAtEpochMs?.let { put("updatedAtEpochMs", it) }
                        })
                    }
                })
                put("announcements", JSONArray().apply {
                    snapshot.announcements.forEach { ann ->
                        put(JSONObject().apply {
                            put("id", ann.id)
                            put("title", ann.title)
                            put("message", ann.message)
                            put("presentation", ann.presentation.wireValue)
                            put("targetChannel", ann.targetChannel.wireValue)
                            ann.minVersionCode?.let { put("minVersionCode", it) }
                            ann.maxVersionCode?.let { put("maxVersionCode", it) }
                            put("state", ann.state.wireValue)
                            put("repeatMode", ann.repeatMode.wireValue)
                            put("startsAtEpochMs", ann.startsAtEpochMs)
                            ann.endsAtEpochMs?.let { put("endsAtEpochMs", it) }
                            ann.createdAtEpochMs?.let { put("createdAtEpochMs", it) }
                            ann.updatedAtEpochMs?.let { put("updatedAtEpochMs", it) }
                        })
                    }
                })
            }
            return root.toString()
        }

        fun fromJson(raw: String): TrustedControlPolicySnapshot? {
            val root = JSONObject(raw)
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion != TrustedControlPolicySnapshot.CURRENT_SCHEMA_VERSION) return null
            val fetchedAtEpochMs = root.optLong("fetchedAtEpochMs", -1L)
            if (fetchedAtEpochMs <= 0L) return null
            val sourceOrigin = root.optString("sourceOrigin", "")

            val psObj = root.optJSONObject("policyState") ?: JSONObject()
            val policyState = ClientPolicyStateRecord(
                singleton = psObj.optBoolean("singleton", true),
                revision = psObj.optLong("revision", 1L).coerceAtLeast(1L),
                updatedAtEpochMs = psObj.optLong("updatedAtEpochMs", -1L).takeIf { it > 0L }
            )

            val ffObj = root.optJSONObject("featureFlags") ?: JSONObject()
            val featureFlags = AllowlistedFeatureFlags(
                betaEnrollmentEnabled = ffObj.optBoolean(
                    "betaEnrollmentEnabled",
                    AllowlistedFeatureFlags.DEFAULT_BETA_ENROLLMENT_ENABLED
                ),
                feedbackEnabled = ffObj.optBoolean(
                    "feedbackEnabled",
                    AllowlistedFeatureFlags.DEFAULT_FEEDBACK_ENABLED
                ),
                onlineMaintenance = ffObj.optBoolean(
                    "onlineMaintenance",
                    AllowlistedFeatureFlags.DEFAULT_ONLINE_MAINTENANCE
                ),
                updateCheckIntervalHours = AllowlistedFeatureFlags.clampIntervalHours(
                    ffObj.optInt(
                        "updateCheckIntervalHours",
                        AllowlistedFeatureFlags.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
                    )
                )
            )

            val relArr = root.optJSONArray("releases") ?: JSONArray()
            val releases = buildList {
                for (i in 0 until relArr.length()) {
                    val o = relArr.optJSONObject(i) ?: continue
                    val channel = ReleaseChannel.fromWire(o.optString("channel")) ?: continue
                    val state = ReleaseState.fromWire(o.optString("state")) ?: continue
                    val vCode = o.optInt("versionCode", -1)
                    val vName = o.optString("versionName", "")
                    val manifest = o.optString("manifestUrl", "")
                    val cert = o.optString("signingCertSha256", "")
                    val availFrom = o.optLong("availableFromEpochMs", -1L)
                    if (vCode <= 0 || vName.isBlank() || manifest.isBlank() || cert.isBlank() || availFrom <= 0L) continue
                    add(
                        ApprovedReleaseRecord(
                            id = o.optString("id"),
                            versionCode = vCode,
                            versionName = vName,
                            channel = channel,
                            priority = ReleasePriority.fromWire(o.optString("priority")),
                            state = state,
                            rolloutPercent = o.optInt("rolloutPercent", 0).coerceIn(0, 100),
                            releaseNotes = o.optString("releaseNotes", ""),
                            manifestUrl = manifest,
                            apkSha256 = o.optString("apkSha256", "").takeIf { it.isNotBlank() },
                            apkSizeBytes = o.optLong("apkSizeBytes", -1L).takeIf { it > 0L },
                            signingCertSha256 = cert,
                            verifiedAtEpochMs = o.optLong("verifiedAtEpochMs", -1L).takeIf { it > 0L },
                            availableFromEpochMs = availFrom,
                            availableUntilEpochMs = o.optLong("availableUntilEpochMs", -1L).takeIf { it > 0L },
                            createdAtEpochMs = o.optLong("createdAtEpochMs", -1L).takeIf { it > 0L },
                            updatedAtEpochMs = o.optLong("updatedAtEpochMs", -1L).takeIf { it > 0L }
                        )
                    )
                }
            }

            val annArr = root.optJSONArray("announcements") ?: JSONArray()
            val announcements = buildList {
                for (i in 0 until annArr.length()) {
                    val o = annArr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val title = o.optString("title")
                    val message = o.optString("message")
                    val state = AnnouncementState.fromWire(o.optString("state")) ?: continue
                    val startsAt = o.optLong("startsAtEpochMs", -1L)
                    if (id.isBlank() || title.isBlank() || message.isBlank() || startsAt <= 0L) continue
                    add(
                        AnnouncementRecord(
                            id = id,
                            title = title,
                            message = message,
                            presentation = AnnouncementPresentation.fromWire(o.optString("presentation")),
                            targetChannel = AnnouncementTargetChannel.fromWire(o.optString("targetChannel")),
                            minVersionCode = o.optInt("minVersionCode", -1).takeIf { it > 0 },
                            maxVersionCode = o.optInt("maxVersionCode", -1).takeIf { it > 0 },
                            state = state,
                            repeatMode = AnnouncementRepeatMode.fromWire(o.optString("repeatMode")),
                            startsAtEpochMs = startsAt,
                            endsAtEpochMs = o.optLong("endsAtEpochMs", -1L).takeIf { it > 0L },
                            createdAtEpochMs = o.optLong("createdAtEpochMs", -1L).takeIf { it > 0L },
                            updatedAtEpochMs = o.optLong("updatedAtEpochMs", -1L).takeIf { it > 0L }
                        )
                    )
                }
            }

            return TrustedControlPolicySnapshot(
                schemaVersion = schemaVersion,
                fetchedAtEpochMs = fetchedAtEpochMs,
                sourceOrigin = sourceOrigin,
                policyState = policyState,
                releases = releases,
                announcements = announcements,
                featureFlags = featureFlags
            )
        }
    }
}
