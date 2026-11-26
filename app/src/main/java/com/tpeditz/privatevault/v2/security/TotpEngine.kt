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

    sealed class ParseOtpAuthResult {
        data class Success(val parsed: ParsedOtpAuthUri) : ParseOtpAuthResult()
        data class Invalid(val message: String) : ParseOtpAuthResult()
    }

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
     * Parses standard `otpauth://totp/...` URI while preserving `+` and Unicode in labels and
     * rejecting explicitly invalid optional query parameters instead of silently falling back.
     */
    fun parseOtpAuthUri(uriString: String): ParsedOtpAuthUri? {
        return (parseOtpAuthUriWithDetails(uriString) as? ParseOtpAuthResult.Success)?.parsed
    }

    fun parseOtpAuthUriWithDetails(uriString: String): ParseOtpAuthResult {
        return try {
            val uri = URI(uriString.trim())
            if (!uri.scheme.equals("otpauth", ignoreCase = true) ||
                !uri.host.equals("totp", ignoreCase = true)
            ) {
                return ParseOtpAuthResult.Invalid("QR code is not a valid TOTP authenticator URI.")
            }

            val rawPath = uri.rawPath?.removePrefix("/") ?: ""
            val decodedPath = decodePercentEncodedUtf8(rawPath, plusAsSpace = false)
            val queryParams = parseQueryParams(uri.rawQuery.orEmpty())

            val issuerFromPath: String
            val accountName: String
            if (decodedPath.contains(":")) {
                val parts = decodedPath.split(":", limit = 2)
                issuerFromPath = parts[0].trim()
                accountName = parts[1].trim()
            } else {
                if (queryParams["issuer"].isNullOrBlank()) {
                    issuerFromPath = decodedPath.trim()
                    accountName = ""
                } else {
                    issuerFromPath = ""
                    accountName = decodedPath.trim()
                }
            }

            val rawSecret = queryParams["secret"]?.replace(" ", "")?.replace("-", "")?.trim()
                ?: return ParseOtpAuthResult.Invalid("Authenticator URI is missing required secret key.")
            val decodedSecret = runCatching { decodeBase32(rawSecret) }.getOrNull()
            if (decodedSecret == null || decodedSecret.isEmpty()) {
                return ParseOtpAuthResult.Invalid("Authenticator secret key is not valid Base32.")
            }

            val issuerParam = queryParams["issuer"]?.trim()
            val issuer = when {
                !issuerParam.isNullOrBlank() -> issuerParam
                issuerFromPath.isNotBlank() -> issuerFromPath
                else -> "Service"
            }

            val digits = if (queryParams.containsKey("digits")) {
                val parsedDigits = queryParams["digits"]?.trim()?.toIntOrNull()
                    ?: return ParseOtpAuthResult.Invalid("Authenticator digits setting is invalid. Supported values are 6 to 8 digits.")
                if (parsedDigits !in MIN_TOTP_DIGITS..MAX_TOTP_DIGITS) {
                    return ParseOtpAuthResult.Invalid("Authenticator digits must be between $MIN_TOTP_DIGITS and $MAX_TOTP_DIGITS.")
                }
                parsedDigits
            } else {
                6
            }

            val period = if (queryParams.containsKey("period")) {
                val parsedPeriod = queryParams["period"]?.trim()?.toIntOrNull()
                    ?: return ParseOtpAuthResult.Invalid("Authenticator time period is invalid. Supported values are 5 to 300 seconds.")
                if (parsedPeriod !in MIN_TOTP_PERIOD_SECONDS..MAX_TOTP_PERIOD_SECONDS) {
                    return ParseOtpAuthResult.Invalid("Authenticator time period must be between $MIN_TOTP_PERIOD_SECONDS and $MAX_TOTP_PERIOD_SECONDS seconds.")
                }
                parsedPeriod
            } else {
                30
            }

            val algorithm = if (queryParams.containsKey("algorithm")) {
                val rawAlg = queryParams["algorithm"]?.trim()?.uppercase(java.util.Locale.US).orEmpty()
                when (rawAlg) {
                    "SHA1" -> TotpAlgorithm.SHA1
                    "SHA256" -> TotpAlgorithm.SHA256
                    "SHA512" -> TotpAlgorithm.SHA512
                    else -> return ParseOtpAuthResult.Invalid("Unsupported authenticator hash algorithm. Use SHA1, SHA256, or SHA512.")
                }
            } else {
                TotpAlgorithm.SHA1
            }

            ParseOtpAuthResult.Success(
                ParsedOtpAuthUri(
                    serviceName = issuer,
                    accountName = accountName,
                    secret = rawSecret,
                    digits = digits,
                    periodSeconds = period,
                    algorithm = algorithm
                )
            )
        } catch (_: Exception) {
            ParseOtpAuthResult.Invalid("QR code is not a valid TOTP URI.")
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isBlank()) return map
        val pairs = query.split("&")
        for (pair in pairs) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = decodePercentEncodedUtf8(pair.substring(0, idx), plusAsSpace = true)
                val value = decodePercentEncodedUtf8(pair.substring(idx + 1), plusAsSpace = true)
                map[key] = value
            }
        }
        return map
    }

    private fun decodePercentEncodedUtf8(raw: String, plusAsSpace: Boolean): String {
        val out = java.io.ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == '%' -> {
                    require(i + 2 < raw.length) { "Malformed percent encoding." }
                    val hi = Character.digit(raw[i + 1], 16)
                    val lo = Character.digit(raw[i + 2], 16)
                    require(hi >= 0 && lo >= 0) { "Invalid hex digits in percent encoding." }
                    out.write((hi shl 4) or lo)
                    i += 3
                }
                ch == '+' && plusAsSpace -> {
                    out.write(' '.code)
                    i++
                }
                else -> {
                    val bytes = ch.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                    out.write(bytes)
                    i++
                }
            }
        }
        return String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
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
