package com.tpeditz.privatevault.v2.autofill

import android.content.Context
import android.os.Build
import android.view.autofill.AutofillManager
import com.tpeditz.privatevault.v2.data.CredentialRecord
import java.net.URI

/**
 * API 24-safe Autofill helper enforcing strict explicit site/app association rules.
 *
 * Security Model:
 * - Never matches credentials using ambiguous `serviceName` labels or package-token splitting
 *   (e.g., `com.fake.github` never automatically receives `https://github.com` credentials).
 * - Web targets require an explicit `websiteUrl` matching the exact verified origin.
 * - HTTPS-configured credentials are not auto-suggested on explicit `http://` pages.
 * - Native Android app targets require explicit `android://<packageName>` or `<packageName>` in `websiteUrl`.
 */
object AutofillHelper {

    fun isAutofillServiceEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val autofillManager = context.getSystemService(AutofillManager::class.java)
        return autofillManager?.hasEnabledAutofillServices() == true
    }

    fun normalizeDomain(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("android://", ignoreCase = true)) return ""
        return try {
            val fullUrl = if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                "https://$trimmed"
            } else {
                trimmed
            }
            val host = URI(fullUrl).host?.lowercase() ?: ""
            host
        } catch (_: Exception) {
            ""
        }
    }

    fun matchesTarget(
        cred: CredentialRecord,
        cleanTargetDomain: String,
        packageName: String,
        targetScheme: String? = null,
        webOriginVerified: Boolean = false
    ): Boolean {
        val rawWeb = cred.websiteUrl.trim().lowercase()
        val cleanPkg = packageName.trim().lowercase()

        if (cleanTargetDomain.isNotBlank()) {
            // A domain reported by a form is not proof of the website's identity.
            // Require explicit verification before automatic website matching.
            if (!webOriginVerified || !targetScheme.equals("https", ignoreCase = true)) return false
            return normalizeDomain(cred.websiteUrl) == cleanTargetDomain &&
                !rawWeb.startsWith("http://")
        }

        if (cleanPkg.isBlank()) return false
        return rawWeb == "android://$cleanPkg" || rawWeb == cleanPkg
    }
}
