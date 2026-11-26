package com.tpeditz.privatevault.v2.security

import com.tpeditz.privatevault.v2.data.TotpAlgorithm
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Standard RFC 6238 Time-Based One-Time Password (TOTP) and RFC 4226 HOTP engine for PRIVATE VAULT V2.
 * Fully local with zero network calls and zero telemetry.
 */
object TotpEngine {

    data class ParsedOtpAuthUri(
        val serviceName: String,
        val accountName: String,
        val secret: String,
        val digits: Int = 6,
        val periodSeconds: Int = 30,
        val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    )

    const val MIN_TOTP_DIGITS = 6
    const val MAX_TOTP_DIGITS = 8
    const val MIN_TOTP_PERIOD_SECONDS = 5
    const val MAX_TOTP_PERIOD_SECONDS = 300

    /**
     * Generates a TOTP code for the given secret at `epochSeconds`.
     */
    fun generateTotpCode(
        secretBase32: String,
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        periodSeconds: Int = 30,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    ): String {
        require(digits in MIN_TOTP_DIGITS..MAX_TOTP_DIGITS) {
            "TOTP code length must be between $MIN_TOTP_DIGITS and $MAX_TOTP_DIGITS digits."
        }
        require(periodSeconds in MIN_TOTP_PERIOD_SECONDS..MAX_TOTP_PERIOD_SECONDS) {
            "TOTP period must be between $MIN_TOTP_PERIOD_SECONDS and $MAX_TOTP_PERIOD_SECONDS seconds."
        }
        val cleanSecret = secretBase32.replace(" ", "").replace("-", "").uppercase(java.util.Locale.US)
        val keyBytes = decodeBase32(cleanSecret)
        require(keyBytes.isNotEmpty()) { "TOTP secret cannot be empty." }
        val timeStep = epochSeconds / periodSeconds.toLong()
        return generateHotp(keyBytes, timeStep, digits, algorithm)
    }

    /**
     * Computes remaining seconds in current TOTP window.
     */
    fun getRemainingSeconds(
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        periodSeconds: Int = 30
    ): Int {
        val safePeriod = periodSeconds.coerceIn(MIN_TOTP_PERIOD_SECONDS, MAX_TOTP_PERIOD_SECONDS)
        val rem = safePeriod - (epochSeconds % safePeriod).toInt()
        return if (rem <= 0) safePeriod else rem
    }

    fun generateHotp(
        keyBytes: ByteArray,
        counter: Long,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    ): String {
        require(keyBytes.isNotEmpty()) { "HOTP secret key cannot be empty." }
        require(digits in MIN_TOTP_DIGITS..MAX_TOTP_DIGITS) {
            "HOTP digits must be between $MIN_TOTP_DIGITS and $MAX_TOTP_DIGITS."
        }
        val macAlgorithm = when (algorithm) {
            TotpAlgorithm.SHA1 -> "HmacSHA1"
            TotpAlgorithm.SHA256 -> "HmacSHA256"
            TotpAlgorithm.SHA512 -> "HmacSHA512"
        }

        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance(macAlgorithm)
        mac.init(SecretKeySpec(keyBytes, macAlgorithm))
        val hash = mac.doFinal(counterBytes)

        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulus = 10.0.pow(digits.toDouble()).toInt()
        val otp = binary % modulus
        return "%0${digits}d".format(java.util.Locale.US, otp)
    }

    /**
     * Parses standard `otpauth://totp/...` URI.
     */
    fun parseOtpAuthUri(uriString: String): ParsedOtpAuthUri? {
        return try {
            val uri = URI(uriString)
            if (!uri.scheme.equals("otpauth", ignoreCase = true)) return null
            if (!uri.host.equals("totp", ignoreCase = true)) return null

            val rawPath = uri.path.removePrefix("/")
            val decodedPath = URLDecoder.decode(rawPath, "UTF-8")

            var issuerFromPath = ""
            var accountName = ""
            val queryParams = parseQueryParams(uri.rawQuery.orEmpty())
            if (decodedPath.contains(":")) {
                val parts = decodedPath.split(":", limit = 2)
                issuerFromPath = parts[0].trim()
                accountName = parts[1].trim()
            } else {
                if (queryParams["issuer"].isNullOrBlank()) {
                    issuerFromPath = decodedPath.trim()
                    accountName = ""
                } else {
                    accountName = decodedPath.trim()
                }
            }
            val secret = queryParams["secret"]?.replace(" ", "")?.replace("-", "")?.trim() ?: return null
            val decodedSecret = runCatching { decodeBase32(secret) }.getOrNull()
            if (decodedSecret == null || decodedSecret.isEmpty()) return null
            val issuerParam = queryParams["issuer"]
            val issuer = (issuerParam ?: issuerFromPath).ifBlank { "Service" }

            val digits = queryParams["digits"]?.toIntOrNull() ?: 6
            val period = queryParams["period"]?.toIntOrNull() ?: 30
            if (digits !in MIN_TOTP_DIGITS..MAX_TOTP_DIGITS) return null
            if (period !in MIN_TOTP_PERIOD_SECONDS..MAX_TOTP_PERIOD_SECONDS) return null

            val algorithmStr = queryParams["algorithm"]?.uppercase(java.util.Locale.US) ?: "SHA1"
            val algorithm = when (algorithmStr) {
                "SHA1" -> TotpAlgorithm.SHA1
                "SHA256" -> TotpAlgorithm.SHA256
                "SHA512" -> TotpAlgorithm.SHA512
                else -> return null
            }

            ParsedOtpAuthUri(
                serviceName = issuer,
                accountName = accountName,
                secret = secret,
                digits = digits,
                periodSeconds = period,
                algorithm = algorithm
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isBlank()) return map
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    /**
     * RFC 4648 Base32 decoding with padding support.
     */
    fun decodeBase32(base32: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = base32.trim().replace("=", "").uppercase()
        var buffer = 0
        var bitsLeft = 0
        val out = mutableListOf<Byte>()

        for (c in clean) {
            val charVal = alphabet.indexOf(c)
            require(charVal >= 0) { "Illegal Base32 character: $c" }
            buffer = (buffer shl 5) or charVal
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
