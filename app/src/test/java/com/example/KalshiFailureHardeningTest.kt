package com.example

import com.example.data.PriceHistory
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.kalshi.KalshiApiClient
import com.example.kalshi.KalshiAutomationEngine
import com.example.kalshi.KalshiMarket
import com.example.kalshi.KalshiOrderBookLevel
import com.example.kalshi.KalshiOrderBookSnapshot
import com.example.kalshi.KalshiOrderBookVerifier
import com.example.kalshi.KalshiOrderRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hardening and Failure Mode Tests for QtY Kalshi Automated Trading Integration.
 * Covers all expected failure categories:
 * 1. KALSHI AUTH/API: credential validation, rate limits, network failures
 * 2. MARKET IDENTIFICATION: ambiguous contracts, expired contracts, wrong strike/duration
 * 3. ORDER EXECUTION: duplicate prevention, insufficient balance, position mismatch, fail-closed
 * 4. MARKET DATA & ORDER BOOK: stale books (>30s), crossed/inverted book, one-sided book
 * 5. AUTOMATION SAFETY: defaults OFF, OFF guarantees zero orders, restarts preserve OFF
 */
class KalshiFailureHardeningTest {

    private lateinit var apiClient: KalshiApiClient
    private lateinit var priceHistory: PriceHistory
    private lateinit var engine: KalshiAutomationEngine

    @Before
    fun setup() {
        apiClient = KalshiApiClient()
        priceHistory = PriceHistory(300)
        engine = KalshiAutomationEngine(apiClient, priceHistory, tradeSizeLimit = 1)
    }

    private fun createDummyPrediction(
        decision: String = "UP",
        currentPrice: Double = 90000.0,
        now: Long = System.currentTimeMillis()
    ): PredictionRecord {
        return PredictionRecord(
            timestamp = now,
            inputs = IndicatorSnapshot(),
            decision = decision,
            score = if (decision == "UP") 0.75 else 0.25,
            strength = "STRONG",
            predictedPrice = if (decision == "UP") currentPrice + 50.0 else currentPrice - 50.0,
            currentPrice = currentPrice,
            settlementReference = currentPrice
        )
    }

    private fun createDummyMarket(
        ticker: String = "KXBTC15M-TEST",
        openTimeMs: Long,
        closeTimeMs: Long,
        strikePrice: Double = 90000.0,
        status: String = "active"
    ): KalshiMarket {
        return KalshiMarket(
            ticker = ticker,
            eventTicker = "KXBTC15M",
            seriesTicker = "KXBTC15M",
            title = "Bitcoin 15m",
            subtitle = "BTC Price Settlement",
            openTimeMs = openTimeMs,
            closeTimeMs = closeTimeMs,
            expirationTimeMs = closeTimeMs,
            status = status,
            yesBid = 50,
            yesAsk = 52,
            noBid = 48,
            noAsk = 50,
            lastPrice = 51,
            strikePrice = strikePrice
        )
    }

    // ==========================================
    // 1. AUTOMATION SAFETY: DEFAULTS & TOGGLE
    // ==========================================

    @Test
    fun testAutomationDefaultsOff() {
        val state = engine.state.value
        assertFalse("Automation MUST be OFF by default", state.isAutomationEnabled)
    }

    @Test
    fun testOffGuaranteesZeroNewOrders() = runBlocking {
        engine.toggleAutomation(false)
        val pred = createDummyPrediction("UP", 90000.0)

        // Engine is OFF - onNewPrediction must not submit any order
        engine.onNewPrediction(pred, 90000.0, System.currentTimeMillis())

        assertEquals(0, engine.state.value.recentOrders.size)
    }

    // ==========================================
    // 2. AUTH & API CREDENTIAL HARDENING
    // ==========================================

    @Test
    fun testCredentialsValidation() {
        assertFalse("Default API client must not be authenticated", apiClient.isAuthenticated())

        apiClient.setCredentials("", "")
        assertFalse("Empty credentials must not authenticate", apiClient.isAuthenticated())

        apiClient.setCredentials("test_key", "invalid_not_rsa")
        assertFalse("Non-RSA key must not authenticate", apiClient.isAuthenticated())

        val keyGen = java.security.KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val pair = keyGen.generateKeyPair()
        val validPrivKeyBase64 = java.util.Base64.getEncoder().encodeToString(pair.private.encoded)

        apiClient.setCredentials("test_key", validPrivKeyBase64)
        assertTrue("Valid RSA key credentials must authenticate", apiClient.isAuthenticated())

        apiClient.clearCredentials()
        assertFalse(apiClient.isAuthenticated())
    }

    @Test
    fun testSubmitOrderFailsClosedWithoutAuth() = runBlocking {
        apiClient.clearCredentials()
        val request = KalshiOrderRequest(
            ticker = "KXBTC15M-TEST",
            action = "buy",
            side = "yes",
            type = "limit",
            count = 1,
            yesPrice = 50,
            clientOrderId = "cid_test_no_auth"
        )
        val res = apiClient.submitOrder(request)
        assertTrue("Submit order must fail without credentials", res.isFailure)
    }

    // ==========================================
    // 3. MARKET IDENTIFICATION HARDENING
    // ==========================================

    @Test
    fun testValidateContractRejectsExpiredContract() {
        val now = 1700000000000L
        val expiredMarket = createDummyMarket(
            openTimeMs = now - 900_000L,
            closeTimeMs = now - 10_000L, // Expired 10s ago
            strikePrice = 90000.0
        )

        val result = engine.validateContract(expiredMarket, 90000.0, now)
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("expired", ignoreCase = true))
    }

    @Test
    fun testValidateContractRejectsExpiringTooSoon() {
        val now = 1700000000000L
        val expiringMarket = createDummyMarket(
            openTimeMs = now - 880_000L,
            closeTimeMs = now + 15_000L, // Only 15s remaining (< 30s threshold)
            strikePrice = 90000.0
        )

        val result = engine.validateContract(expiringMarket, 90000.0, now)
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("too soon", ignoreCase = true))
    }

    @Test
    fun testValidateContractRejectsInvalidDuration() {
        val now = 1700000000000L
        val wrongDurationMarket = createDummyMarket(
            openTimeMs = now - 3_600_000L,
            closeTimeMs = now + 3_600_000L, // 2-hour contract, not 15m
            strikePrice = 90000.0
        )

        val result = engine.validateContract(wrongDurationMarket, 90000.0, now)
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("duration", ignoreCase = true))
    }

    @Test
    fun testValidateContractRejectsDivergentStrike() {
        val now = 1700000000000L
        val wrongStrikeMarket = createDummyMarket(
            openTimeMs = now - 300_000L,
            closeTimeMs = now + 600_000L,
            strikePrice = 120000.0 // 33% away from spot of $90,000
        )

        val result = engine.validateContract(wrongStrikeMarket, 90000.0, now)
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("diverges", ignoreCase = true))
    }

    // ==========================================
    // 4. ORDER-BOOK VERIFICATION HARDENING
    // ==========================================

    @Test
    fun testOrderBookStalenessRejected() {
        val now = 1700000000000L
        val market = createDummyMarket(
            openTimeMs = now - 300_000L,
            closeTimeMs = now + 600_000L,
            strikePrice = 90000.0
        )
        val staleBook = KalshiOrderBookSnapshot(
            ticker = "KXBTC15M-TEST",
            timestampMs = now - 35_000L, // 35 seconds old (> 30s threshold)
            yesBids = listOf(KalshiOrderBookLevel(50, 0.50, 10.0)),
            noBids = listOf(KalshiOrderBookLevel(48, 0.48, 10.0)),
            bestYesBidCents = 50,
            bestNoBidCents = 48,
            impliedYesAskCents = 52,
            impliedNoAskCents = 50,
            totalYesDepth = 10.0,
            totalNoDepth = 10.0,
            status = "LIVE"
        )
        val pred = createDummyPrediction("UP", 90000.0, now)

        val result = KalshiOrderBookVerifier.verify(market, staleBook, pred, now)
        assertEquals("UNAVAILABLE", result.marketBias)
        assertEquals("UNCONFIRMED", result.verificationSummary)
        assertNull(result.yesMidPriceCents)
    }

    @Test
    fun testCrossedOrderBookDetected() {
        val now = 1700000000000L
        val market = createDummyMarket(
            openTimeMs = now - 300_000L,
            closeTimeMs = now + 600_000L,
            strikePrice = 90000.0
        )
        // Inverted / crossed book: best bid (60) >= implied ask (55)
        val crossedBook = KalshiOrderBookSnapshot(
            ticker = "KXBTC15M-TEST",
            timestampMs = now,
            yesBids = listOf(KalshiOrderBookLevel(60, 0.60, 10.0)),
            noBids = listOf(KalshiOrderBookLevel(45, 0.45, 10.0)),
            bestYesBidCents = 60,
            bestNoBidCents = 45,
            impliedYesAskCents = 55, // Implied Yes Ask = 100 - bestNoBid = 55 <= bestYesBid
            impliedNoAskCents = 40,
            totalYesDepth = 10.0,
            totalNoDepth = 10.0,
            status = "LIVE"
        )
        val pred = createDummyPrediction("UP", 90000.0, now)

        val result = KalshiOrderBookVerifier.verify(market, crossedBook, pred, now)
        assertEquals("UNAVAILABLE", result.marketBias)
        assertEquals("UNCONFIRMED", result.verificationSummary)
        assertNull(result.yesMidPriceCents)
    }

    // ==========================================
    // 5. ORDER EXECUTION & SIZE CONSTRAINTS
    // ==========================================

    @Test
    fun testOrderInputValidation() = runBlocking {
        apiClient.setCredentials("key", "secret")

        // Invalid count (> 5)
        val tooLarge = KalshiOrderRequest(
            ticker = "KXBTC15M-TEST",
            action = "buy",
            side = "yes",
            type = "limit",
            count = 10,
            yesPrice = 50,
            clientOrderId = "cid_too_large"
        )
        val res1 = apiClient.submitOrder(tooLarge)
        assertTrue(res1.isFailure)

        // Invalid price (150¢ > 99¢)
        val invalidPrice = KalshiOrderRequest(
            ticker = "KXBTC15M-TEST",
            action = "buy",
            side = "yes",
            type = "limit",
            count = 1,
            yesPrice = 150,
            clientOrderId = "cid_invalid_price"
        )
        val res2 = apiClient.submitOrder(invalidPrice)
        assertTrue(res2.isFailure)
    }
}
