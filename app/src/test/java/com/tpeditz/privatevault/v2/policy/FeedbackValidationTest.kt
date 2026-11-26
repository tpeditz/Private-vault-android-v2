package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackValidationTest {

    @Test
    fun feedbackBuildFlagRemainsDisabledPendingEdgeDeployment() {
        assertFalse(BuildConfig.FEEDBACK_SUBMISSION_ENABLED)
        val state = PolicyFeedbackState(
            isSupportedByBuild = BuildConfig.FEEDBACK_SUBMISSION_ENABLED,
            isEnabledByServer = true,
            isMaintenanceActive = false
        )
        assertFalse(state.isSubmissionAllowed)
        assertEquals(
            "Feedback submission is disabled pending server endpoint deployment.",
            state.disabledReason
        )
    }

    @Test
    fun validationEnforcesConsentCategoryAndBoundedLength() {
        assertFalse(PolicyFormatters.validateFeedbackInput("bug", "Crash in export", null, consentChecked = false).isValid)
        assertFalse(PolicyFormatters.validateFeedbackInput("telemetry", "Bad category", null, consentChecked = true).isValid)
        assertFalse(PolicyFormatters.validateFeedbackInput("bug", "   ", null, consentChecked = true).isValid)
        assertFalse(
            PolicyFormatters.validateFeedbackInput(
                "suggestion",
                "a".repeat(PolicyFormatters.MAX_FEEDBACK_LENGTH + 1),
                null,
                consentChecked = true
            ).isValid
        )
        assertFalse(
            PolicyFormatters.validateFeedbackInput(
                "other",
                "Looks great",
                "x".repeat(PolicyFormatters.MAX_CONTACT_LENGTH + 1),
                consentChecked = true
            ).isValid
        )

        val valid = PolicyFormatters.validateFeedbackInput(
            category = "suggestion",
            message = "Please add keyboard shortcut notes.",
            contact = "@user",
            consentChecked = true
        )
        assertTrue(valid.isValid)
    }

    @Test
    fun feedbackClientSendsZeroTelemetryAndHandlesRateLimitsCleanly() = runBlocking {
        var capturedPayload: JSONObject? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val buf = Buffer()
                chain.request().body!!.writeTo(buf)
                capturedPayload = JSONObject(buf.readUtf8())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("{\"accepted\":true,\"feedback_id\":\"fb-123\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val sender = SupabaseFeedbackClient(
            supabaseUrl = "https://lszjacrrqthhdapzvjok.supabase.co",
            publishableKey = "sb_publishable_test",
            buildFeedbackEnabled = true,
            client = client
        )

        val ok = sender.submitFeedback(
            category = "suggestion",
            message = "Clean UX suggestion",
            contact = null,
            consentChecked = true,
            appVersion = "2.1.0"
        )
        assertTrue(ok is SupabaseFeedbackClient.SubmitResult.Accepted)
        assertEquals("fb-123", (ok as SupabaseFeedbackClient.SubmitResult.Accepted).feedbackId)
        assertEquals(setOf("category", "message", "app_version", "consent"), capturedPayload!!.keys().asSequence().toSet())
        assertTrue(capturedPayload!!.getBoolean("consent"))

        // Verify 429 rate-limit response produces clean plain-English error
        val rateLimited = SupabaseFeedbackClient(
            supabaseUrl = "https://lszjacrrqthhdapzvjok.supabase.co",
            publishableKey = "sb_publishable_test",
            buildFeedbackEnabled = true,
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("{\"error\":\"Rate limit exceeded\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        ).submitFeedback("bug", "Message", null, true)
        assertTrue(rateLimited is SupabaseFeedbackClient.SubmitResult.Rejected)
        assertTrue((rateLimited as SupabaseFeedbackClient.SubmitResult.Rejected).message.contains("Too many", ignoreCase = true))
    }
}
