package com.example.kalshi

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.util.Locale

/**
 * Cryptographic signer for Kalshi API v2 requests using RSA-PSS SHA-256.
 *
 * Kalshi documentation specifies:
 * - Message format: "<timestamp_ms><HTTP_METHOD><path_without_query>"
 * - Algorithm: SHA256withRSA/PSS (MGF1 with SHA-256, 32-byte salt, trailerField = 1)
 * - Access headers:
 *   KALSHI-ACCESS-KEY: <key_id>
 *   KALSHI-ACCESS-TIMESTAMP: <timestamp_ms>
 *   KALSHI-ACCESS-SIGNATURE: <base64_encoded_signature>
 */
object KalshiSigner {

    fun parsePrivateKey(pemOrBase64: String): PrivateKey? {
        return try {
            val cleanKey = pemOrBase64
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")
                .trim()

            if (cleanKey.isEmpty()) return null

            val decoded = try {
                android.util.Base64.decode(cleanKey, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(cleanKey)
            }
            val keySpec = PKCS8EncodedKeySpec(decoded)
            val keyFactory = KeyFactory.getInstance("RSA")
            keyFactory.generatePrivate(keySpec)
        } catch (_: Exception) {
            null
        }
    }

    fun signMessage(
        timestampMs: Long,
        method: String,
        pathWithoutQuery: String,
        privateKey: PrivateKey
    ): String {
        val cleanPath = pathWithoutQuery.substringBefore("?")
        val message = "$timestampMs${method.uppercase(Locale.US)}$cleanPath"

        val pssSpec = PSSParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            32,
            1
        )

        val signature = Signature.getInstance("SHA256withRSA/PSS").apply {
            setParameter(pssSpec)
            initSign(privateKey)
            update(message.toByteArray(StandardCharsets.UTF_8))
        }

        val signedBytes = signature.sign()
        return try {
            android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(signedBytes)
        }
    }
}
