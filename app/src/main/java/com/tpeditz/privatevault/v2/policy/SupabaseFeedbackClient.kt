package com.tpeditz.privatevault.v2.policy

import com.tpeditz.privatevault.v2.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Privacy-preserving, explicit-consent client for optional feedback submission.
 * Sends ONLY: category, sanitized message, optional contact handle, app version, and consent = true.
 * Disabled in production until `BuildConfig.FEEDBACK_SUBMISSION_ENABLED` and server `feedback_enabled` are both true.
 */
class SupabaseFeedbackClient(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val buildFeedbackEnabled: Boolean = BuildConfig.FEEDBACK_SUBMISSION_ENABLED,
    private val client: OkHttpClient = defaultHttpClient()
) {

    sealed class SubmitResult {
        data class Accepted(val feedbackId: String?) : SubmitResult()
        data class Rejected(val message: String) : SubmitResult()
        data class NetworkError(val message: String) : SubmitResult()
    }

    suspend fun submitFeedback(
        category: String,
        message: String,
        contact: String?,
        consentChecked: Boolean,
        appVersion: String = BuildConfig.VERSION_NAME
    ): SubmitResult = withContext(Dispatchers.IO) {
        if (!buildFeedbackEnabled) {
            return@withContext SubmitResult.Rejected(
                "Feedback submission is disabled pending server endpoint deployment."
            )
        }

        val validation = PolicyFormatters.validateFeedbackInput(
            category = category,
            message = message,
            contact = contact,
            consentChecked = consentChecked
        )
        if (!validation.isValid) {
            return@withContext SubmitResult.Rejected(
                validation.errorMessage ?: "Please check your feedback entry."
            )
        }

        val baseUrl = supabaseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: return@withContext SubmitResult.Rejected("Unable to connect. Please try again.")
        if (baseUrl.scheme != "https") {
            return@withContext SubmitResult.Rejected("Secure HTTPS connection required.")
        }

        val cleanCategory = category.trim().lowercase(Locale.US)
        val cleanMessage = PolicyFormatters.sanitizePlainText(message, PolicyFormatters.MAX_FEEDBACK_LENGTH)
        val cleanContact = contact?.let { PolicyFormatters.sanitizePlainText(it, PolicyFormatters.MAX_CONTACT_LENGTH) }
            ?.takeIf { it.isNotEmpty() }
        val cleanVersion = PolicyFormatters.sanitizePlainText(appVersion, PolicyFormatters.MAX_APP_VERSION_LENGTH)
            .takeIf { it.isNotEmpty() }

        val jsonBody = JSONObject().apply {
            put("category", cleanCategory)
            put("message", cleanMessage)
            if (cleanContact != null) put("contact", cleanContact)
            if (cleanVersion != null) put("app_version", cleanVersion)
            put("consent", true)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/functions/v1/submit-feedback")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .header("apikey", publishableKey.trim())
            .build()

        return@withContext try {
            client.newCall(request).execute().use { resp ->
                val rawResp = resp.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(rawResp) }.getOrNull()
                when {
                    resp.isSuccessful -> SubmitResult.Accepted(
                        parsed?.optString("feedback_id", "")?.takeIf { it.isNotBlank() }
                    )

                    resp.code == 429 -> SubmitResult.Rejected(
                        "Too many feedback messages sent recently. Please try again later."
                    )

                    resp.code == 403 -> SubmitResult.Rejected(
                        "Feedback submission is currently disabled."
                    )

                    resp.code == 400 -> SubmitResult.Rejected(
                        parsed?.optString("error")?.takeIf { it.isNotBlank() && !it.contains("sql", true) }
                            ?: "Could not accept feedback. Check your entry and try again."
                    )

                    else -> SubmitResult.Rejected("Unable to connect. Please try again.")
                }
            }
        } catch (_: IOException) {
            SubmitResult.NetworkError("Unable to connect. Please try again.")
        } catch (_: Exception) {
            SubmitResult.NetworkError("Unable to connect. Please try again.")
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
