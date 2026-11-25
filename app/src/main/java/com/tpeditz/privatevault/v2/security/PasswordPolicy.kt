package com.tpeditz.privatevault.v2.security

/**
 * Shared validation policy for NEW vault, master, backup, and cloud recovery passwords.
 *
 * Compatibility Guarantee:
 * - Applies strictly to NEW password creation, explicit password changes, and NEW backup exports.
 * - Never blocks unlocking existing vaults or restoring/decrypting older backups that used
 *   shorter legacy passwords.
 */
object PasswordPolicy {

    const val MIN_NEW_PASSWORD_LENGTH = 12
    const val RECOMMENDED_PASSPHRASE_LENGTH = 14
    const val MAX_PASSWORD_LENGTH = 512

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val recommendation: String? = null,
        val strengthReport: OfflinePasswordStrengthEstimator.StrengthReport? = null
    )

    fun validateNewPassword(
        passwordChars: CharArray,
        fieldLabel: String = "Password"
    ): ValidationResult {
        if (passwordChars.isEmpty() || passwordChars.all { it.isWhitespace() }) {
            return ValidationResult(
                isValid = false,
                errorMessage = "$fieldLabel cannot be empty."
            )
        }
        if (passwordChars.size < MIN_NEW_PASSWORD_LENGTH) {
            return ValidationResult(
                isValid = false,
                errorMessage = "Use at least $MIN_NEW_PASSWORD_LENGTH characters for your new ${fieldLabel.lowercase()}. Passphrases with spaces or multiple words are great."
            )
        }
        if (passwordChars.size > MAX_PASSWORD_LENGTH) {
            return ValidationResult(
                isValid = false,
                errorMessage = "$fieldLabel must be $MAX_PASSWORD_LENGTH characters or fewer."
            )
        }
        val pwdString = String(passwordChars)
        val report = OfflinePasswordStrengthEstimator.evaluate(pwdString)
        val hint = if (passwordChars.size < RECOMMENDED_PASSPHRASE_LENGTH) {
            "Tip: $RECOMMENDED_PASSPHRASE_LENGTH+ characters or a memorable multi-word passphrase gives even stronger protection."
        } else {
            null
        }
        return ValidationResult(
            isValid = true,
            recommendation = hint,
            strengthReport = report
        )
    }

    fun validateNewPassword(
        password: String,
        confirmPassword: String,
        fieldLabel: String = "Password"
    ): ValidationResult {
        val chars = password.toCharArray()
        return try {
            val base = validateNewPassword(chars, fieldLabel)
            if (!base.isValid) {
                base
            } else if (password != confirmPassword) {
                base.copy(isValid = false, errorMessage = "Passwords do not match.")
            } else {
                base
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    fun isShortLegacyPassword(passwordChars: CharArray?): Boolean {
        return passwordChars != null &&
            passwordChars.isNotEmpty() &&
            passwordChars.size < MIN_NEW_PASSWORD_LENGTH
    }
}
