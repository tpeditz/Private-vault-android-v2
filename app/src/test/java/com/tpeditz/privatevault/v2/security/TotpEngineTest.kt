package com.tpeditz.privatevault.v2.security

import com.tpeditz.privatevault.v2.data.TotpAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TotpEngineTest {

    // RFC 6238 standard ASCII secret: "12345678901234567890"
    // In Base32: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val rfcBase32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun testRfc4226HotpVectors() {
        val keyBytes = "12345678901234567890".toByteArray(StandardCharsets.US_ASCII)

        // RFC 4226 Section 5.4 test vectors (6 digits, SHA1)
        val expectedCodes = listOf(
            "755224", // Counter 0
            "287082", // Counter 1
            "359152", // Counter 2
            "969429", // Counter 3
            "338314", // Counter 4
            "254676", // Counter 5
            "287922", // Counter 6
            "162583", // Counter 7
            "399871", // Counter 8
            "520489"  // Counter 9
        )

        for (counter in expectedCodes.indices) {
            val code = TotpEngine.generateHotp(
                keyBytes = keyBytes,
                counter = counter.toLong(),
                digits = 6,
                algorithm = TotpAlgorithm.SHA1
            )
            assertEquals("HOTP vector mismatch for counter $counter", expectedCodes[counter], code)
        }
    }

    @Test
    fun testRfc6238TotpVectorsSha1() {
        // RFC 6238 Appendix B test vectors (8 digits, SHA1, 30s period)
        val testCases = listOf(
            Pair(59L, "94287082"),
            Pair(1111111109L, "07081804"),
            Pair(1111111111L, "14050471"),
            Pair(1234567890L, "89005924"),
            Pair(2000000000L, "69279037")
        )

        for ((epochSeconds, expectedCode) in testCases) {
            val code = TotpEngine.generateTotpCode(
                secretBase32 = rfcBase32Secret,
                epochSeconds = epochSeconds,
                periodSeconds = 30,
                digits = 8,
                algorithm = TotpAlgorithm.SHA1
            )
            assertEquals("TOTP vector mismatch at epoch $epochSeconds", expectedCode, code)
        }
    }

    @Test
    fun testBase32DecodingRobustness() {
        // Test lower-case, internal spaces, hyphens, and padding
        val cleanSecret = "JBSWY3DPEHPK3PXP" // "Hello!\nde" in Base32
        val spacedSecret = "jbsw y3dp-ehpk 3pxp==="

        val code1 = TotpEngine.generateTotpCode(cleanSecret, epochSeconds = 1000L)
        val code2 = TotpEngine.generateTotpCode(spacedSecret, epochSeconds = 1000L)

        assertEquals("Formatted secret must produce identical code", code1, code2)
    }

    @Test
    fun testRemainingSeconds() {
        // At second 10 in a 30s period, 20 seconds remain
        val rem1 = TotpEngine.getRemainingSeconds(epochSeconds = 10L, periodSeconds = 30)
        assertEquals(20, rem1)

        // At second 30 (boundary), remaining is 30s
        val rem2 = TotpEngine.getRemainingSeconds(epochSeconds = 30L, periodSeconds = 30)
        assertEquals(30, rem2)

        // At second 59, 1 second remains
        val rem3 = TotpEngine.getRemainingSeconds(epochSeconds = 59L, periodSeconds = 30)
        assertEquals(1, rem3)
    }

    @Test
    fun testParseOtpAuthUri() {
        val uri = "otpauth://totp/GitHub:octocat@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6&period=30&algorithm=SHA1"
        val parsed = TotpEngine.parseOtpAuthUri(uri)
        assertNotNull(parsed)
        val p = checkNotNull(parsed)

        assertEquals("GitHub", p.serviceName)
        assertEquals("octocat@example.com", p.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", p.secret)
        assertEquals(6, p.digits)
        assertEquals(30, p.periodSeconds)
        assertEquals(TotpAlgorithm.SHA1, p.algorithm)
    }

    @Test
    fun testParseOtpAuthUriDefaults() {
        val simpleUri = "otpauth://totp/MyBank?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ"
        val parsed = TotpEngine.parseOtpAuthUri(simpleUri)
        assertNotNull(parsed)
        val p = checkNotNull(parsed)

        assertEquals("MyBank", p.serviceName)
        assertEquals("", p.accountName)
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", p.secret)
        assertEquals(6, p.digits)
        assertEquals(30, p.periodSeconds)
        assertEquals(TotpAlgorithm.SHA1, p.algorithm)
    }
}
