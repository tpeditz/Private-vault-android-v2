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
        assertEquals("github.com", AutofillHelper.normalizeDomain("http://www.github.com"))
        assertEquals("github.com", AutofillHelper.normalizeDomain("www.github.com/settings"))
        assertEquals("github.com", AutofillHelper.normalizeDomain("github.com"))
        assertEquals(
            "subdomain.example.com",
            AutofillHelper.normalizeDomain("https://subdomain.example.com:8443/auth?step=1")
        )
        assertEquals("", AutofillHelper.normalizeDomain(""))
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
                packageName = ""
            )
        )
        assertTrue(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = AutofillHelper.normalizeDomain("www.github.com"),
                packageName = ""
            )
        )
        assertTrue(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = AutofillHelper.normalizeDomain("https://github.com/session"),
                packageName = ""
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
        val cred = CredentialRecord(
            serviceName = "Spotify",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = ""
        )

        assertTrue(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = "",
                packageName = "com.spotify.music"
            )
        )
        assertFalse(
            AutofillHelper.matchesTarget(
                cred,
                cleanTargetDomain = "",
                packageName = "com.netflix.mediaclient"
            )
        )

        val genericCred = CredentialRecord(
            serviceName = "app",
            username = "fixture-user@example.test",
            password = "TEST_ONLY_PASSWORD",
            websiteUrl = ""
        )
        assertFalse(
            AutofillHelper.matchesTarget(
                genericCred,
                cleanTargetDomain = "",
                packageName = "com.spotify.app"
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

        assertTrue(
            AutofillHelper.matchesTarget(
                cloudCred,
                cleanTargetDomain = "cloud.google.com",
                packageName = ""
            )
        )
        assertTrue(
            AutofillHelper.matchesTarget(
                cloudCred,
                cleanTargetDomain = "google.com",
                packageName = ""
            )
        )

        val candidates = listOf(cloudCred, bankingCred).filter {
            AutofillHelper.matchesTarget(it, cleanTargetDomain = "unknown-site.io", packageName = "com.unknown.app")
        }
        assertTrue("No unrelated credentials should ever be proposed on unrecognized sites", candidates.isEmpty())
    }
}
