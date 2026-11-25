package com.tpeditz.privatevault.v2.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorEngineTest {

    @Test
    fun testGeneratePasswordLengthBounds() {
        // Clamp below min
        val shortResult = PasswordGeneratorEngine.generatePassword(
            options = PasswordGeneratorEngine.GeneratorOptions(length = 4)
        )
        assertEquals(PasswordGeneratorEngine.MIN_LENGTH, shortResult.password.length)

        // Clamp above max
        val longResult = PasswordGeneratorEngine.generatePassword(
            options = PasswordGeneratorEngine.GeneratorOptions(length = 100)
        )
        assertEquals(PasswordGeneratorEngine.MAX_LENGTH, longResult.password.length)

        // Custom exact length
        val exactResult = PasswordGeneratorEngine.generatePassword(
            options = PasswordGeneratorEngine.GeneratorOptions(length = 24)
        )
        assertEquals(24, exactResult.password.length)
    }

    @Test
    fun testGuaranteedCharacterClasses() {
        val options = PasswordGeneratorEngine.GeneratorOptions(
            length = 16,
            includeUppercase = true,
            includeLowercase = true,
            includeDigits = true,
            includeSymbols = true,
            excludeAmbiguous = false
        )

        // Generate multiple times to test deterministic class enforcement
        for (i in 0 until 50) {
            val result = PasswordGeneratorEngine.generatePassword(options)
            val pwd = result.password

            assertTrue("Must contain uppercase: $pwd", pwd.any { it.isUpperCase() })
            assertTrue("Must contain lowercase: $pwd", pwd.any { it.isLowerCase() })
            assertTrue("Must contain digit: $pwd", pwd.any { it.isDigit() })
            assertTrue("Must contain symbol: $pwd", pwd.any { "!@#$%^&*()-_=+[]{}|;:,.<>?/~".contains(it) })
        }
    }

    @Test
    fun testExcludeAmbiguousCharacters() {
        val ambiguousChars = setOf('0', 'O', 'o', '1', 'l', 'I', '|')
        val options = PasswordGeneratorEngine.GeneratorOptions(
            length = 32,
            includeUppercase = true,
            includeLowercase = true,
            includeDigits = true,
            includeSymbols = true,
            excludeAmbiguous = true
        )

        for (i in 0 until 50) {
            val result = PasswordGeneratorEngine.generatePassword(options)
            val pwd = result.password
            for (ch in ambiguousChars) {
                assertFalse("Password should not contain ambiguous char '$ch': $pwd", pwd.contains(ch))
            }
        }
    }

    @Test
    fun testPassphraseGeneration() {
        val options = PasswordGeneratorEngine.PassphraseOptions(
            wordCount = 5,
            delimiter = "-",
            capitalizeMode = PasswordGeneratorEngine.CapitalizeMode.TITLE_CASE,
            includeNumber = true
        )

        val result = PasswordGeneratorEngine.generatePassphrase(options)
        val phrase = result.passphrase
        val parts = phrase.split("-")

        assertEquals(5, parts.size)
        // Each word should be title-cased
        for (part in parts) {
            val cleanWord = part.filter { it.isLetter() }
            if (cleanWord.isNotEmpty()) {
                assertTrue("First char must be uppercase: $cleanWord", cleanWord.first().isUpperCase())
            }
        }
        // At least one part should contain a number
        assertTrue("Passphrase must contain at least one digit: $phrase", phrase.any { it.isDigit() })
    }

    @Test
    fun testEntropyCalculationPositive() {
        val pwdResult = PasswordGeneratorEngine.generatePassword(
            PasswordGeneratorEngine.GeneratorOptions(length = 20)
        )
        assertTrue(pwdResult.estimatedEntropyBits > 0)

        val phraseResult = PasswordGeneratorEngine.generatePassphrase(
            PasswordGeneratorEngine.PassphraseOptions(wordCount = 6)
        )
        assertTrue(phraseResult.estimatedEntropyBits > 0)
    }
}
