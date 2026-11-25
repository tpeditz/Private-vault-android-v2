package com.tpeditz.privatevault.v2.autofill

import android.content.Context
import android.os.Build
import android.view.autofill.AutofillManager
import com.tpeditz.privatevault.v2.data.CredentialRecord
import java.net.URI

/**
 * API 24-safe helper for checking Android Autofill availability and performing strict
 * domain/package matching without exposing API 26-only service companion symbols.
 */
object AutofillHelper {

    private val PACKAGE_NOISE_TOKENS = setOf(
        "com", "org", "net", "io", "co", "app", "android", "mobile",
        "client", "login", "auth", "lite", "pro", "free", "beta", "dev", "official", "v2"
    )

    fun isAutofillServiceEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val autofillManager = context.getSystemService(AutofillManager::class.java)
        return autofillManager?.hasEnabledAutofillServices() == true
    }

    fun normalizeDomain(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val host = URI(fullUrl).host?.lowercase() ?: ""
            host.removePrefix("www.")
        } catch (_: Exception) {
            url.trim().lowercase().removePrefix("www.")
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
        packageName: String
    ): Boolean {
        val rawWeb = cred.websiteUrl.trim().lowercase()
        val cleanWeb = normalizeDomain(cred.websiteUrl)
        val cleanService = cred.serviceName.trim().lowercase()
        val cleanPkg = packageName.trim().lowercase()

        if (cleanTargetDomain.isNotBlank()) {
            if (cleanWeb.isNotBlank()) {
                return isHostOrSubdomainMatch(cleanTargetDomain, cleanWeb)
            }
            if (cleanService.contains('.')) {
                return isHostOrSubdomainMatch(cleanTargetDomain, normalizeDomain(cleanService))
            }
            if (cleanService.length >= 3) {
                val hostLabels = cleanTargetDomain.split('.').dropLast(1)
                return hostLabels.any { it == cleanService }
            }
            return false
        }

        if (cleanPkg.isBlank()) return false
        if (rawWeb == cleanPkg || rawWeb == "android://$cleanPkg" || cleanWeb == cleanPkg) {
            return true
        }
        val pkgTokens = cleanPkg.split('.').filter { it.length >= 3 && it !in PACKAGE_NOISE_TOKENS }
        if (pkgTokens.isEmpty()) return false

        if (cleanWeb.isNotBlank()) {
            val primaryWebLabel = cleanWeb.split('.').dropLast(1).firstOrNull { it.length >= 3 }
            if (primaryWebLabel != null && pkgTokens.contains(primaryWebLabel)) {
                return true
            }
            return false
        }

        return cleanService.length >= 3 && pkgTokens.contains(cleanService)
    }
}
