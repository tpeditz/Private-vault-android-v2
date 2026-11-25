package com.tpeditz.privatevault.v2.autofill

import com.tpeditz.privatevault.v2.data.CredentialRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatcherTest {

    @Test
    fun testDomainNormalization() {
        assertEquals("github.com", AutofillHelper.normalizeDomain("https://github.com/login"))
        assertEquals("www.github.com", AutofillHelper.normalizeDomain("http://www.github.com"))
        assertEquals("www.github.com", AutofillHelper.normalizeDomain("www.github.com/settings"))
        assertEquals("github.com", AutofillHelper.normalizeDomain("github.com"))
        assertEquals(
            "subdomain.example.com",
            AutofillHelper.normalizeDomain("https://subdomain.example.com:8443/auth?step=1")
        )
        assertEquals("", AutofillHelper.normalizeDomain(""))
    }

    @Test
    fun unverifiedWebOriginRequiresManualSelection() {
        val cred = CredentialRecord(serviceName = "Example", websiteUrl = "https://example.test",
            username = "fixture", password = "TEST_ONLY_PASSWORD")
        assertFalse(AutofillHelper.matchesTarget(cred, "example.test", "com.example.browser", "https"))
    }

    @Test
    fun testCredentialMatchingByExactDomain() {
        val cred = CredentialRecord(
            serviceName = "GitHub",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = "https://github.com"
        )

        assertTrue(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = AutofillHelper.normalizeDomain("github.com"),
                packageName = "", targetScheme = "https", webOriginVerified = true
            )
        )
        assertFalse(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = AutofillHelper.normalizeDomain("www.github.com"),
                packageName = "", targetScheme = "https", webOriginVerified = true
            )
        )
        assertTrue(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = AutofillHelper.normalizeDomain("https://github.com/session"),
                packageName = "", targetScheme = "https", webOriginVerified = true
            )
        )
        assertFalse(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = AutofillHelper.normalizeDomain("gitlab.com"),
                packageName = ""
            )
        )
    }

    @Test
    fun testRejectLookalikeAndSubstringSpoofing() {
        val googleCred = CredentialRecord(
            serviceName = "Google",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = "https://google.com"
        )

        assertFalse(
            "Lookalike domain evil-google.com must never match google.com",
            AutofillHelper.matchesTarget(
                googleCred,
                cleanTargetDomain = "evil-google.com",
                packageName = ""
            )
        )
        assertFalse(
            "Unrecognized domain must not match",
            AutofillHelper.matchesTarget(
                googleCred,
                cleanTargetDomain = "example.org",
                packageName = ""
            )
        )
    }

    @Test
    fun testCredentialMatchingByPackageNameStrict() {
        val explicitAndroidCred = CredentialRecord(
            serviceName = "Spotify",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = "android://com.spotify.music"
        )

        assertTrue(
            AutofillHelper.matchesTarget(
                explicitAndroidCred,
                cleanTargetDomain = "",
                packageName = "com.spotify.music"
            )
        )
        assertFalse(
            AutofillHelper.matchesTarget(
                explicitAndroidCred,
                cleanTargetDomain = "",
                packageName = "com.netflix.mediaclient"
            )
        )

        val noUrlCred = CredentialRecord(
            serviceName = "Spotify",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = ""
        )
        assertFalse(
            "Credentials without an explicit android:// association must never guess-match apps by serviceName",
            AutofillHelper.matchesTarget(
                noUrlCred,
                cleanTargetDomain = "",
                packageName = "com.spotify.music"
            )
        )

        val webOnlyCred = CredentialRecord(
            serviceName = "GitHub",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = "https://github.com"
        )
        assertFalse(
            "An impostor native app must not receive a web credential via package-token guessing",
            AutofillHelper.matchesTarget(
                webOnlyCred,
                cleanTargetDomain = "",
                packageName = "com.fake.github"
            )
        )
        assertFalse(
            "HTTPS-configured web credential must not auto-fill over explicit http:// scheme",
            AutofillHelper.matchesTarget(
                webOnlyCred,
                cleanTargetDomain = "github.com",
                packageName = "",
                targetScheme = "http"
            )
        )
        assertTrue(
            AutofillHelper.matchesTarget(
                webOnlyCred,
                cleanTargetDomain = "github.com",
                packageName = "",
                targetScheme = "https", webOriginVerified = true
            )
        )
    }

    @Test
    fun testCredentialMatchingBySubdomainAndNoFallbackToUnrelated() {
        val cloudCred = CredentialRecord(
            serviceName = "Google Cloud",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = "https://console.cloud.google.com"
        )
        val bankingCred = CredentialRecord(
            serviceName = "Bank",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = "https://bank.example.com"
        )

        assertFalse(
            AutofillHelper.matchesTarget(
                cloudCred,
                cleanTargetDomain = "cloud.google.com",
                packageName = "", targetScheme = "https", webOriginVerified = true
            )
        )
        assertFalse(
            AutofillHelper.matchesTarget(
                cloudCred,
                cleanTargetDomain = "google.com",
                packageName = "", targetScheme = "https", webOriginVerified = true
            )
        )

        val candidates = listOf(cloudCred, bankingCred).filter {
            AutofillHelper.matchesTarget(it, cleanTargetDomain = "unknown-site.io", packageName = "com.unknown.app")
        }
        assertTrue("No unrelated credentials should ever be proposed on unrecognized sites", candidates.isEmpty())
    }
}
