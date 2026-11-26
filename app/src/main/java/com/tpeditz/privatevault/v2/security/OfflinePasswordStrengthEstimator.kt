package com.tpeditz.privatevault.v2.security

import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * 100% offline password strength estimator for PRIVATE VAULT V2.
 * Makes zero network calls and provides approximate password health metrics.
 */
object OfflinePasswordStrengthEstimator {

    enum class StrengthBand(val label: String, val scoreFloor: Int) {
        CRITICAL("Critical", 0),
        WEAK("Weak", 25),
        FAIR("Fair", 50),
        STRONG("Strong", 75),
        FORTRESS("Fortress", 90)
    }

    data class StrengthReport(
        val score: Int, // 0..100
        val band: StrengthBand,
        val estimatedEntropyBits: Int,
        val length: Int,
        val hasUppercase: Boolean,
        val hasLowercase: Boolean,
        val hasDigits: Boolean,
        val hasSymbols: Boolean,
        val warnings: List<String>,
        val suggestions: List<String>,
        val isReusedInVault: Boolean = false
    )

    private val COMMON_PASSWORDS = setOf(
        "password", "123456", "12345678", "123456789", "qwerty", "qwertyuiop",
        "asdfgh", "zxcvbn", "letmein", "welcome", "admin", "iloveyou", "monkey",
        "dragon", "baseball", "football", "shadow", "master", "sunshine", "superman",
        "trustno1", "passw0rd", "secret", "private", "vault", "india", "mumbai",
        "delhi", "krishna", "shiva", "ganesh", "google", "android", "samsung",
        "batman", "charlie", "donald", "mustang", "access", "freedom", "whatever",
        "abc123", "111111", "000000", "654321", "987654321", "maverick", "loveyou"
    )

    private val KEYBOARD_ROWS = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    fun evaluate(
        password: String,
        existingVaultPasswords: Collection<String> = emptyList()
    ): StrengthReport {
        if (password.isEmpty()) {
            return StrengthReport(
                score = 0,
                band = StrengthBand.CRITICAL,
                estimatedEntropyBits = 0,
                length = 0,
                hasUppercase = false,
                hasLowercase = false,
                hasDigits = false,
                hasSymbols = false,
                warnings = listOf("Password is empty"),
                suggestions = listOf("Create a password with at least 14 characters"),
                isReusedInVault = false
            )
        }

        val len = password.length
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }

        var poolSize = 0
        if (hasUpper) poolSize += 26
        if (hasLower) poolSize += 26
        if (hasDigit) poolSize += 10
        if (hasSymbol) poolSize += 32

        val entropy = if (poolSize > 0) (len * log2(poolSize.toDouble())).roundToInt() else 0

        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        val lowerCasePwd = password.lowercase()
        val isCommon = COMMON_PASSWORDS.contains(lowerCasePwd)
        if (isCommon) {
            warnings.add("This is a frequently used password")
        }

        val isWalk = detectKeyboardWalk(lowerCasePwd)
        if (isWalk) {
            warnings.add("Contains sequential keyboard patterns")
        }

        val isRepeated = detectRepeatedChars(password)
        if (isRepeated) {
            warnings.add("Contains repetitive characters")
        }

        // Fast O(1) membership check using Set
        val isReused = if (existingVaultPasswords is Set) {
            existingVaultPasswords.contains(password)
        } else {
            existingVaultPasswords.any { it == password }
        }
        if (isReused) {
            warnings.add("Reused in another account in this vault")
        }

        // Calculate score
        var rawScore = 0
        rawScore += (len * 4).coerceAtMost(45)

        var classCount = 0
        if (hasUpper) classCount++
        if (hasLower) classCount++
        if (hasDigit) classCount++
        if (hasSymbol) classCount++

        rawScore += when (classCount) {
            4 -> 30
            3 -> 20
            2 -> 10
            else -> 0
        }

        if (len >= 16) rawScore += 15
        else if (len >= 12) rawScore += 8

        if (isWalk) {
            rawScore -= 25
            rawScore = rawScore.coerceAtMost(35)
        }
        if (isRepeated) rawScore -= 15

        // Check for passphrases (distinct non-repeating words separated by spaces/hyphens)
        val words = password.split(Regex("[\\s\\-_]+")).filter { it.isNotBlank() }
        val normalizedWords = words.map { it.lowercase() }
        val distinctWordCount = normalizedWords.distinct().size
        val hasRepeatingWords = words.size >= 2 && distinctWordCount <= words.size / 2
        if (hasRepeatingWords) {
            rawScore -= 25
            warnings.add("Repeated words weaken passphrases significantly")
        }
        if (words.size >= 4 && distinctWordCount >= 4 && len >= 16 && !isCommon && !isWalk) {
            rawScore = rawScore.coerceAtLeast(80)
        }

        // Caps
        if (isCommon) {
            rawScore = rawScore.coerceAtMost(15)
        }
        if (len < 6) {
            rawScore = rawScore.coerceAtMost(20)
        }
        if (isReused) {
            rawScore = rawScore.coerceAtMost(45)
        }

        val finalScore = rawScore.coerceIn(0, 100)

        val band = when {
            finalScore >= StrengthBand.FORTRESS.scoreFloor -> StrengthBand.FORTRESS
            finalScore >= StrengthBand.STRONG.scoreFloor -> StrengthBand.STRONG
            finalScore >= StrengthBand.FAIR.scoreFloor -> StrengthBand.FAIR
            finalScore >= StrengthBand.WEAK.scoreFloor -> StrengthBand.WEAK
            else -> StrengthBand.CRITICAL
        }

        if (len < 12) suggestions.add("Make your password at least 14 characters long")
        if (classCount < 3) suggestions.add("Mix uppercase, lowercase, numbers, and symbols")
        if (isReused) suggestions.add("Generate a unique password for this service")

        return StrengthReport(
            score = finalScore,
            band = band,
            estimatedEntropyBits = entropy,
            length = len,
            hasUppercase = hasUpper,
            hasLowercase = hasLower,
            hasDigits = hasDigit,
            hasSymbols = hasSymbol,
            warnings = warnings,
            suggestions = suggestions,
            isReusedInVault = isReused
        )
    }

    private fun detectKeyboardWalk(lower: String): Boolean {
        if (lower.length < 4) return false
        for (row in KEYBOARD_ROWS) {
            for (i in 0..row.length - 4) {
                val seq = row.substring(i, i + 4)
                val rev = seq.reversed()
                if (lower.contains(seq) || lower.contains(rev)) return true
            }
        }
        return false
    }

    private fun detectRepeatedChars(pwd: String): Boolean {
        if (pwd.length < 3) return false
        var count = 1
        for (i in 1 until pwd.length) {
            if (pwd[i] == pwd[i - 1]) {
                count++
                if (count >= 3) return true
            } else {
                count = 1
            }
        }
        return false
    }

    /**
     * Efficient single-pass whole-vault reuse frequency map (O(N)).
     */
    fun computeVaultReuseFrequencyMap(passwords: Collection<String>): Map<String, Int> {
        val freq = HashMap<String, Int>(passwords.size)
        for (pwd in passwords) {
            if (pwd.isNotEmpty()) {
                freq[pwd] = (freq[pwd] ?: 0) + 1
            }
        }
        return freq
    }

    fun buildVaultReuseFrequencyMap(passwords: Collection<String>): Map<String, Int> =
        computeVaultReuseFrequencyMap(passwords)

    fun estimateStrength(
        password: String,
        vaultReuseCount: Int = 0
    ): StrengthReport {
        val dummyList = if (vaultReuseCount > 1) {
            List(vaultReuseCount) { password }
        } else emptyList()
        return evaluate(password, dummyList)
    }
}
