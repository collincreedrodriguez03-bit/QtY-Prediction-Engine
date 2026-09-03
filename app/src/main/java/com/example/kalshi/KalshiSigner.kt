package com.example.kalshi

import android.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec

/**
 * Handles RSA-PSS cryptographic signing for authenticated Kalshi API v2 calls.
 * Headers required:
 * - KALSHI-ACCESS-KEY: User's Key ID
 * - KALSHI-ACCESS-TIMESTAMP: Current time in milliseconds
 * - KALSHI-ACCESS-SIGNATURE: Base64-encoded RSA-PSS SHA-256 signature of `${timestamp}${method}${path}`
 */
object KalshiSigner {

    /**
     * Parses a PEM-encoded PKCS#8 or raw base64 private key.
     */
    fun parsePrivateKey(pemOrBase64: String): PrivateKey? {
        return try {
            val cleanKey = pemOrBase64
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
                .trim()

            if (cleanKey.isEmpty()) return null

            val decoded = try {
                java.util.Base64.getDecoder().decode(cleanKey)
            } catch (_: Throwable) {
                Base64.decode(cleanKey, Base64.DEFAULT)
            }
            val spec = PKCS8EncodedKeySpec(decoded)
            val kf = KeyFactory.getInstance("RSA")
            kf.generatePrivate(spec)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates the RSA-PSS signature for Kalshi API v2:
     * message = "${timestampMs}${method.uppercase()}${pathWithoutQuery}"
     */
    fun signMessage(
        timestampMs: Long,
        method: String,
        path: String,
        privateKey: PrivateKey
    ): String {
        val pathWithoutQuery = if (path.contains("?")) path.substringBefore("?") else path
        val message = "$timestampMs${method.uppercase()}$pathWithoutQuery"

        val signature = Signature.getInstance("SHA256withRSA/PSS").apply {
            val pssSpec = PSSParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                32,
                1
            )
            setParameter(pssSpec)
            initSign(privateKey)
            update(message.toByteArray(Charsets.UTF_8))
        }

        val signedBytes = signature.sign()
        return try {
            java.util.Base64.getEncoder().encodeToString(signedBytes)
        } catch (_: Throwable) {
            Base64.encodeToString(signedBytes, Base64.NO_WRAP)
        }
    }
}
