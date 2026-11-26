package com.tpeditz.privatevault.v2.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePasswordStrengthEstimatorTest {

    @Test
    fun testEmptyPasswordIsCritical() {
        val report = OfflinePasswordStrengthEstimator.estimateStrength("")
        assertEquals(0, report.score)
        assertEquals(OfflinePasswordStrengthEstimator.StrengthBand.CRITICAL, report.band)
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test
    fun testCommonDictionaryPasswordIsWeakOrCritical() {
        val report = OfflinePasswordStrengthEstimator.estimateStrength("password")
        assertTrue("Common password score must be low (<= 25)", report.score <= 25)
        assertTrue("Warning should mention common password", report.warnings.any { it.contains("frequently used", ignoreCase = true) })
    }

    @Test
    fun testKeyboardWalkDetected() {
        val report = OfflinePasswordStrengthEstimator.estimateStrength("qwerty123456")
        assertTrue(report.score < 40)
        assertTrue(report.warnings.any { it.contains("keyboard", ignoreCase = true) || it.contains("sequence", ignoreCase = true) })
    }

    @Test
    fun testHighEntropyPasswordIsFortressOrStrong() {
        val strongPwd = "K9#mQ!8zP@2xL$7vR*4w"
        val report = OfflinePasswordStrengthEstimator.estimateStrength(strongPwd)
        assertTrue("High entropy password score must be >= 75", report.score >= 75)
        assertTrue(
            report.band == OfflinePasswordStrengthEstimator.StrengthBand.STRONG ||
                    report.band == OfflinePasswordStrengthEstimator.StrengthBand.FORTRESS
        )
        assertTrue(report.hasUppercase)
        assertTrue(report.hasLowercase)
        assertTrue(report.hasDigits)
        assertTrue(report.hasSymbols)
    }

    @Test
    fun testVaultReuseFrequencyPenalty() {
        val reusedPassword = "SharedSecret987#"
        val uniquePassword = "UniqueSecret123!"

        val vaultSample = listOf(
            reusedPassword,
            reusedPassword,
            reusedPassword,
            uniquePassword
        )

        val reuseMap = OfflinePasswordStrengthEstimator.buildVaultReuseFrequencyMap(vaultSample)
        assertEquals(3, reuseMap[reusedPassword])
        assertEquals(1, reuseMap[uniquePassword])

        val reportReused = OfflinePasswordStrengthEstimator.estimateStrength(
            password = reusedPassword,
            vaultReuseCount = reuseMap[reusedPassword] ?: 0
        )
        val reportUnique = OfflinePasswordStrengthEstimator.estimateStrength(
            password = uniquePassword,
            vaultReuseCount = reuseMap[uniquePassword] ?: 0
        )

        assertTrue(reportReused.isReusedInVault)
        assertFalse(reportUnique.isReusedInVault)
        assertTrue(reportReused.warnings.any { it.contains("reused", ignoreCase = true) })
    }

    @Test
    fun testRepeatedWordPassphraseIsPenalizedWhileDistinctPassphraseScoresStrong() {
        val repeated = OfflinePasswordStrengthEstimator.estimateStrength("word word word word")
        assertTrue("Repeated word passphrase must not be rated Strong (>= 80), got ${repeated.score}", repeated.score < 80)
        assertTrue(repeated.warnings.any { it.contains("Repeated", ignoreCase = true) })

        val distinct = OfflinePasswordStrengthEstimator.estimateStrength("correct-horse-battery-staple")
        assertTrue("Distinct multi-word passphrase should be Strong (>= 80), got ${distinct.score}", distinct.score >= 80)
    }
}
