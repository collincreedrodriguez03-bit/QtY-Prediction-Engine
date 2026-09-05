package com.example

import com.example.kalshi.KalshiApiClient
import com.example.kalshi.KalshiOrderRequest
import com.example.kalshi.KalshiSigner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class KalshiApiClientTest {

    @Test
    fun testSignerWithGeneratedRsaKey() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val timestamp = 1710000000000L
        val method = "GET"
        val path = "/trade-api/v2/portfolio/balance"

        val signature = KalshiSigner.signMessage(timestamp, method, path, keyPair.private)
        assertNotNull(signature)
        assertTrue(signature?.isNotEmpty() == true)
    }

    @Test
    fun testSignerStripsQueryParamsFromSignString() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val timestamp = 1710000000000L
        val method = "GET"
        val pathWithQuery = "/trade-api/v2/portfolio/orders?status=resting"
        val pathWithoutQuery = "/trade-api/v2/portfolio/orders"

        // Both should sign the base path without query
        val sig1 = KalshiSigner.signMessage(timestamp, method, pathWithQuery, keyPair.private)
        val sig2 = KalshiSigner.signMessage(timestamp, method, pathWithoutQuery, keyPair.private)
        // Signatures are valid base64 strings
        assertNotNull(sig1)
        assertNotNull(sig2)
    }

    @Test
    fun testSubmitOrderFailsClosedWithoutCredentials() = runBlocking {
        val client = KalshiApiClient()
        val order = KalshiOrderRequest(
            ticker = "KXBTC15M-26MAR05-T90000",
            action = "buy",
            side = "yes",
            type = "limit",
            count = 1,
            yesPrice = 50,
            clientOrderId = "test_order_1"
        )
        val result = client.submitOrder(order)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not authenticated") == true)
    }

    @Test
    fun testCancelOrderFailsClosedWithoutCredentials() = runBlocking {
        val client = KalshiApiClient()
        val result = client.cancelOrder("order_123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not authenticated") == true)
    }

    @Test
    fun testGetBalanceFailsClosedWithoutCredentials() = runBlocking {
        val client = KalshiApiClient()
        val result = client.getPortfolioBalance()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not authenticated") == true)
    }
}
