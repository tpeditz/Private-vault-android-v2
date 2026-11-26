package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.BuildConfig
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Verifies 1:1 contract compatibility between Android `SupabasePolicyClient`
 * and the authoritative foundation SQL tables:
 * `client_policy_state`, `releases`, `announcements`, and `feature_flags`.
 */
class SupabaseSchemaContractTest {

    private val expectedCertHex = PolicyFormatters.normalizeSha256(BuildConfig.RELEASE_CERT_SHA256)

    private fun jsonOk(chain: Interceptor.Chain, json: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(json.toResponseBody("application/json".toMediaType()))
        .build()

    @Test
    fun clientQueriesUseExactFoundationSchemaColumnsWithoutDeprecatedFields() {
        val requestedUrls = mutableListOf<String>()
        val validApkSha = "b".repeat(64)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestedUrls.add(url)
                val path = chain.request().url.encodedPath
                when {
                    path.endsWith("/client_policy_state") -> jsonOk(
                        chain,
                        JSONArray().put(
                            JSONObject()
                                .put("singleton", true)
                                .put("revision", 7L)
                                .put("updated_at", "2026-11-26T12:00:00Z")
                        ).toString()
                    )

                    path.endsWith("/releases") -> jsonOk(
                        chain,
                        JSONArray().put(
                            JSONObject()
                                .put("id", "rel-uuid-1")
                                .put("version_name", "2.1.1")
                                .put("version_code", 7)
                                .put("channel", "stable")
                                .put("priority", "recommended")
                                .put("state", "published")
                                .put("rollout_percent", 50)
                                .put("release_notes", "Security fixes")
                                .put("manifest_url", "https://github.com/tpeditz/Private-vault-android-v2/releases/download/v2.1.1/update.json")
                                .put("apk_sha256", validApkSha)
                                .put("apk_size_bytes", 15_000_000L)
                                .put("signing_cert_sha256", expectedCertHex)
                                .put("verified_at", "2026-11-26T12:00:00Z")
                                .put("available_from", "2026-11-26T12:00:00Z")
                                .put("available_until", JSONObject.NULL)
                                .put("created_at", "2026-11-26T11:50:00Z")
                                .put("updated_at", "2026-11-26T12:00:00Z")
                        ).toString()
                    )

                    path.endsWith("/announcements") -> jsonOk(
                        chain,
                        JSONArray().put(
                            JSONObject()
                                .put("id", "ann-uuid-1")
                                .put("title", "Maintenance Window")
                                .put("message", "Scheduled maintenance tonight.")
                                .put("presentation", "dialog")
                                .put("target_channel", "all")
                                .put("min_version_code", 6)
                                .put("max_version_code", 10)
                                .put("state", "published")
                                .put("repeat_mode", "repeat")
                                .put("starts_at", "2026-11-26T12:00:00Z")
                                .put("ends_at", JSONObject.NULL)
                                .put("created_at", "2026-11-26T11:55:00Z")
                                .put("updated_at", "2026-11-26T12:00:00Z")
                        ).toString()
                    )

                    path.endsWith("/feature_flags") -> jsonOk(
                        chain,
                        JSONArray()
                            .put(JSONObject().put("flag_key", "beta_enrollment_enabled").put("boolean_value", true).put("integer_value", JSONObject.NULL).put("is_published", true).put("updated_at", "2026-11-26T12:00:00Z"))
                            .put(JSONObject().put("flag_key", "feedback_enabled").put("boolean_value", false).put("integer_value", JSONObject.NULL).put("is_published", true).put("updated_at", "2026-11-26T12:00:00Z"))
                            .put(JSONObject().put("flag_key", "online_maintenance").put("boolean_value", true).put("integer_value", JSONObject.NULL).put("is_published", true).put("updated_at", "2026-11-26T12:00:00Z"))
                            .put(JSONObject().put("flag_key", "update_check_interval_hours").put("boolean_value", JSONObject.NULL).put("integer_value", 24).put("is_published", true).put("updated_at", "2026-11-26T12:00:00Z"))
                            .toString()
                    )

                    else -> throw AssertionError("Unexpected REST endpoint: $url")
                }
            }
            .build()

        val policyClient = SupabasePolicyClient(
            supabaseUrl = "https://lszjacrrqthhdapzvjok.supabase.co",
            publishableKey = "sb_publishable_test",
            clockEpochMs = { 1_760_000_000_000L },
            client = client
        )

        val res = policyClient.fetchTrustedPolicySnapshot()
        assertTrue(res is SupabasePolicyClient.FetchResult.Success)
        val snap = (res as SupabasePolicyClient.FetchResult.Success).snapshot

        // Verify query URLs use ONLY exact foundation schema column names
        assertEquals(4, requestedUrls.size)
        val joined = requestedUrls.joinToString("\n")
        assertTrue(joined.contains("singleton,revision,updated_at"))
        assertTrue(joined.contains("apk_sha256,apk_size_bytes,signing_cert_sha256,verified_at,available_from,available_until"))
        assertTrue(joined.contains("id,title,message,presentation,target_channel,min_version_code,max_version_code,state,repeat_mode,starts_at,ends_at"))
        assertFalse(joined.contains("latest_stable_version_code"))
        assertFalse(joined.contains("active_flag_version"))
        assertFalse(joined.contains("display_mode"))
        assertFalse(joined.contains("min_supported_version_code"))
        assertFalse(joined.contains("ota_checks_enabled"))
        assertFalse(joined.contains("ota_check_interval_hours"))

        // Verify decoded policyState, release, announcement, and flags
        assertEquals(7L, snap.policyState.revision)
        assertEquals(1, snap.releases.size)
        val rel = snap.releases.first()
        assertEquals(ReleasePriority.RECOMMENDED, rel.priority)
        assertEquals(validApkSha, rel.apkSha256)
        assertEquals(15_000_000L, rel.apkSizeBytes)
        assertNotNull(rel.verifiedAtEpochMs)

        assertEquals(1, snap.announcements.size)
        val ann = snap.announcements.first()
        assertEquals(AnnouncementPresentation.DIALOG, ann.presentation)
        assertEquals(AnnouncementTargetChannel.ALL, ann.targetChannel)
        assertEquals(AnnouncementRepeatMode.REPEAT, ann.repeatMode)
        assertEquals("Scheduled maintenance tonight.", ann.message)

        assertTrue(snap.featureFlags.betaEnrollmentEnabled)
        assertFalse(snap.featureFlags.feedbackEnabled)
        assertTrue(snap.featureFlags.onlineMaintenance)
        assertEquals(24, snap.featureFlags.updateCheckIntervalHours)
    }

    @Test
    fun networkFailureAndHttpErrorDistinguishNetworkUnavailableFromServiceUnavailable() {
        val netDown = SupabasePolicyClient(
            supabaseUrl = "https://lszjacrrqthhdapzvjok.supabase.co",
            publishableKey = "sb_publishable_test",
            client = OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build()
        )
        assertTrue(netDown.fetchTrustedPolicySnapshot() is SupabasePolicyClient.FetchResult.NetworkUnavailable)

        val server503 = SupabasePolicyClient(
            supabaseUrl = "https://lszjacrrqthhdapzvjok.supabase.co",
            publishableKey = "sb_publishable_test",
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        )
        assertTrue(server503.fetchTrustedPolicySnapshot() is SupabasePolicyClient.FetchResult.ServiceUnavailable)
    }
}
