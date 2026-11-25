package com.tpeditz.privatevault.v2.security

import java.security.SecureRandom
import kotlin.math.ln

/**
 * Cryptographically secure Password and Passphrase Generator for PRIVATE VAULT V2.
 */
object PasswordGeneratorEngine {

    private const val UPPERCASE_ALL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE_ALL = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS_ALL = "0123456789"
    private const val SYMBOLS_ALL = "!@#$%^&*()-_=+[]{}|;:,.<>?/~"

    private val AMBIGUOUS_CHARS = setOf('0', 'O', 'o', '1', 'l', 'I', '|')

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    const val DEFAULT_LENGTH = 20

    private val secureRandom = SecureRandom()

    val passphraseWordlist: List<String> get() = PassphraseWordlist.WORDS

    enum class CapitalizeMode {
        TITLE_CASE,
        ALL_LOWERCASE,
        ALL_UPPERCASE
    }

    data class PassphraseOptions(
        val wordCount: Int = 4,
        val delimiter: String = "-",
        val capitalizeMode: CapitalizeMode = CapitalizeMode.TITLE_CASE,
        val includeNumber: Boolean = true
    )

    data class PassphraseResult(
        val passphrase: String,
        val estimatedEntropyBits: Double
    )

    data class GeneratorConfig(
        val length: Int = DEFAULT_LENGTH,
        val includeUppercase: Boolean = true,
        val includeLowercase: Boolean = true,
        val includeDigits: Boolean = true,
        val includeSymbols: Boolean = true,
        val excludeAmbiguous: Boolean = true
    )

    data class GeneratorOptions(
        val length: Int = DEFAULT_LENGTH,
        val includeUppercase: Boolean = true,
        val includeLowercase: Boolean = true,
        val includeDigits: Boolean = true,
        val includeSymbols: Boolean = true,
        val excludeAmbiguous: Boolean = true
    )

    sealed interface GenerationResult {
        data class Success(
            val password: String,
            val poolSize: Int,
            val estimatedEntropyBits: Double,
            val configExplanation: String
        ) : GenerationResult

        data class InvalidConfig(val reason: String) : GenerationResult
    }

    fun generate(config: GeneratorConfig = GeneratorConfig()): GenerationResult {
        if (config.length !in MIN_LENGTH..MAX_LENGTH) {
            return GenerationResult.InvalidConfig(
                "Password length must be between $MIN_LENGTH and $MAX_LENGTH characters."
            )
        }

        val activePools = mutableListOf<String>()
        val categoryNames = mutableListOf<String>()

        if (config.includeUppercase) {
            val pool = filterPool(UPPERCASE_ALL, config.excludeAmbiguous)
            activePools.add(pool)
            categoryNames.add("Uppercase")
        }
        if (config.includeLowercase) {
            val pool = filterPool(LOWERCASE_ALL, config.excludeAmbiguous)
            activePools.add(pool)
            categoryNames.add("Lowercase")
        }
        if (config.includeDigits) {
            val pool = filterPool(DIGITS_ALL, config.excludeAmbiguous)
            activePools.add(pool)
            categoryNames.add("Digits")
        }
        if (config.includeSymbols) {
            val pool = filterPool(SYMBOLS_ALL, config.excludeAmbiguous)
            activePools.add(pool)
            categoryNames.add("Symbols")
        }

        if (activePools.isEmpty()) {
            return GenerationResult.InvalidConfig(
                "Enable at least one character category (Uppercase, Lowercase, Digits, or Symbols)."
            )
        }

        if (config.length < activePools.size) {
            return GenerationResult.InvalidConfig(
                "Length (${config.length}) is shorter than the number of required categories (${activePools.size})."
            )
        }

        val combinedPool = activePools.joinToString("")
        val chars = CharArray(config.length)

        // 1. Pick one unbiased character from each enabled class
        for (i in activePools.indices) {
            val pool = activePools[i]
            chars[i] = pool[nextUnbiasedInt(pool.length)]
        }

        // 2. Fill the remaining positions uniformly from combined pool
        for (i in activePools.size until config.length) {
            chars[i] = combinedPool[nextUnbiasedInt(combinedPool.length)]
        }

        // 3. Cryptographically secure Fisher-Yates shuffle
        for (i in chars.lastIndex downTo 1) {
            val j = nextUnbiasedInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }

        val password = String(chars)
        val requiredBits = activePools.sumOf { ln(it.length.toDouble()) / ln(2.0) }
        val remainingCount = (config.length - activePools.size).coerceAtLeast(0)
        val remainingBits = remainingCount * (ln(combinedPool.length.toDouble()) / ln(2.0))
        val entropy = requiredBits + remainingBits

        val explanation = "${config.length} chars (${categoryNames.joinToString(", ")})" +
            if (config.excludeAmbiguous) " without ambiguous characters" else ""

        return GenerationResult.Success(
            password = password,
            poolSize = combinedPool.length,
            estimatedEntropyBits = entropy,
            configExplanation = explanation
        )
    }

    fun generatePassphrase(
        wordCount: Int = 4,
        separator: String = "-",
        capitalize: Boolean = true,
        includeNumber: Boolean = true
    ): String {
        val count = wordCount.coerceIn(4, 8)
        val selectedWords = mutableListOf<String>()

        for (i in 0 until count) {
            var word = PassphraseWordlist.WORDS[nextUnbiasedInt(PassphraseWordlist.WORDS.size)]
            if (capitalize) {
                word = word.replaceFirstChar { it.uppercase() }
            }
            selectedWords.add(word)
        }

        if (includeNumber) {
            val randomNum = nextUnbiasedInt(100)
            val insertIndex = nextUnbiasedInt(selectedWords.size)
            selectedWords[insertIndex] = "${selectedWords[insertIndex]}$randomNum"
        }

        return selectedWords.joinToString(separator)
    }

    fun generatePassword(options: GeneratorConfig): GenerationResult.Success {
        val clampedLen = options.length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val res = generate(options.copy(length = clampedLen))
        return when (res) {
            is GenerationResult.Success -> res
            is GenerationResult.InvalidConfig -> {
                generate(GeneratorConfig(length = clampedLen)) as GenerationResult.Success
            }
        }
    }

    fun generatePassword(options: GeneratorOptions): GenerationResult.Success =
        generatePassword(GeneratorConfig(
            length = options.length,
            includeUppercase = options.includeUppercase,
            includeLowercase = options.includeLowercase,
            includeDigits = options.includeDigits,
            includeSymbols = options.includeSymbols,
            excludeAmbiguous = options.excludeAmbiguous
        ))

    fun generatePassphrase(options: PassphraseOptions): PassphraseResult {
        val count = options.wordCount.coerceIn(4, 8)
        val selectedWords = mutableListOf<String>()

        for (i in 0 until count) {
            var word = PassphraseWordlist.WORDS[nextUnbiasedInt(PassphraseWordlist.WORDS.size)]
            word = when (options.capitalizeMode) {
                CapitalizeMode.TITLE_CASE -> word.replaceFirstChar { it.uppercase() }
                CapitalizeMode.ALL_UPPERCASE -> word.uppercase()
                CapitalizeMode.ALL_LOWERCASE -> word.lowercase()
            }
            selectedWords.add(word)
        }

        if (options.includeNumber) {
            val randomNum = nextUnbiasedInt(10)
            val insertIndex = nextUnbiasedInt(selectedWords.size)
            selectedWords[insertIndex] = "${selectedWords[insertIndex]}$randomNum"
        }

        val phrase = selectedWords.joinToString(options.delimiter)
        val entropy = count * (ln(PassphraseWordlist.WORDS.size.toDouble()) / ln(2.0))
        return PassphraseResult(phrase, entropy)
    }

    private fun filterPool(rawPool: String, excludeAmbiguous: Boolean): String {
        return if (excludeAmbiguous) {
            rawPool.filter { it !in AMBIGUOUS_CHARS }
        } else {
            rawPool
        }
    }

    private fun nextUnbiasedInt(bound: Int): Int {
        require(bound > 0)
        val mask = (1 shl (32 - Integer.numberOfLeadingZeros(bound - 1))) - 1
        var value: Int
        do {
            value = secureRandom.nextInt() and mask
        } while (value >= bound)
        return value
    }
}
