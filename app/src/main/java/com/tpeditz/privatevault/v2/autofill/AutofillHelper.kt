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
 * - Web targets require an explicit `websiteUrl` matching the exact host or parent domain.
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
            host.removePrefix("www.")
        } catch (_: Exception) {
            trimmed.lowercase().removePrefix("www.")
        }
    }

    private fun isHostOrSubdomainMatch(targetHost: String, storedHost: String): Boolean {
        if (targetHost.isBlank() || storedHost.isBlank()) return false
        if (targetHost == storedHost) return true
        if (storedHost.contains('.') && targetHost.endsWith(".$storedHost")) return true
        if (targetHost.contains('.') && storedHost.endsWith(".$targetHost")) return true
        return false
    }

    fun matchesTarget(
        cred: CredentialRecord,
        cleanTargetDomain: String,
        packageName: String,
        targetScheme: String? = null
    ): Boolean {
        val rawWeb = cred.websiteUrl.trim().lowercase()
        val cleanPkg = packageName.trim().lowercase()

        if (cleanTargetDomain.isNotBlank()) {
            val cleanWeb = normalizeDomain(cred.websiteUrl)
            if (cleanWeb.isBlank()) return false
            // Do not auto-fill explicitly HTTPS credentials on explicit plaintext HTTP pages
            if (targetScheme.equals("http", ignoreCase = true) && rawWeb.startsWith("https://")) {
                return false
            }
            return isHostOrSubdomainMatch(cleanTargetDomain, cleanWeb)
        }

        if (cleanPkg.isBlank()) return false
        return rawWeb == "android://$cleanPkg" || rawWeb == cleanPkg
    }
}
