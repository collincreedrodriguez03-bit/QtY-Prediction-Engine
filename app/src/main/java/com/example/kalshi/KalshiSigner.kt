package com.example.kalshi

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec

/**
 * Cryptographic signer for Kalshi API v2.
 * Kalshi requires request authentication using RSA-PSS SHA-256 signatures:
 * Parameters: SHA-256, MGF1-SHA256, salt length 32.
 * String to sign: timestamp + method + path_without_query (e.g. "1710000000000GET/trade-api/v2/portfolio/balance")
 */
object KalshiSigner {

    fun parsePrivateKey(pemOrBase64: String): PrivateKey? {
        return try {
            val cleaned = pemOrBase64
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            if (cleaned.isEmpty()) return null

            val bytes = decodeBase64(cleaned) ?: return null
            val keySpec = PKCS8EncodedKeySpec(bytes)
            val kf = KeyFactory.getInstance("RSA")
            kf.generatePrivate(keySpec)
        } catch (_: Throwable) {
            null
        }
    }

    fun signMessage(timestamp: Long, method: String, path: String, privateKey: PrivateKey): String? {
        // Strip any query parameters from path for the signature string
        val pathWithoutQuery = if (path.contains("?")) path.substringBefore("?") else path

        // The signed path MUST include the complete API path: /trade-api/v2/...
        val fullPath = when {
            pathWithoutQuery.startsWith("/trade-api/v2/") -> pathWithoutQuery
            pathWithoutQuery == "/trade-api/v2" -> pathWithoutQuery
            pathWithoutQuery.startsWith("/") -> "/trade-api/v2$pathWithoutQuery"
            else -> "/trade-api/v2/$pathWithoutQuery"
        }
        val message = "$timestamp$method$fullPath"

        return try {
            val pssSpec = PSSParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                32,
                1
            )
            val signature = try {
                Signature.getInstance("RSASSA-PSS")
            } catch (_: Throwable) {
                Signature.getInstance("SHA256withRSA/PSS")
            }
            signature.setParameter(pssSpec)
            signature.initSign(privateKey)
            signature.update(message.toByteArray(Charsets.UTF_8))
            encodeBase64(signature.sign())
        } catch (_: Throwable) {
            // STRICT REQUIREMENT: REMOVE any fallback to SHA256withRSA.
            // If RSA-PSS cannot be performed correctly, fail closed.
            null
        }
    }

    private fun decodeBase64(str: String): ByteArray? {
        return try {
            java.util.Base64.getDecoder().decode(str)
        } catch (_: Throwable) {
            try {
                android.util.Base64.decode(str, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }
}
