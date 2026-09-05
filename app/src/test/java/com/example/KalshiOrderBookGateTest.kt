package com.example

import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.kalshi.KalshiAutomationEngine
import com.example.kalshi.KalshiBalance
import com.example.kalshi.KalshiMarket
import com.example.kalshi.KalshiOrderBookLevel
import com.example.kalshi.KalshiOrderBookSnapshot
import com.example.kalshi.KalshiOrderResponse
import com.example.kalshi.KalshiRiskEngine
import com.example.testutil.MockKalshiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * P0 Mandate 3: Order-Book Verification Hard Gate Test.
 *
 * Verifies that the order-book verifier operates as a strict hard gate:
 * - UNCONFIRMED -> NO ORDER
 * - DIVERGENCE -> NO ORDER
 * - UNAVAILABLE -> NO ORDER
 * - STALE BOOK -> NO ORDER
 * - CROSSED BOOK -> NO ORDER
 * - NEUTRAL -> NO ORDER
 * - APPROVED AGREEMENT -> ORDER SUBMISSION PERMITTED
 */
class KalshiOrderBookGateTest {

    private lateinit var priceHistory: PriceHistory
    private lateinit var riskEngine: KalshiRiskEngine
    private val btcPrice = 90_000.0

    @Before
    fun setUp() {
        priceHistory = PriceHistory(maxCapacity = 50)
        riskEngine = KalshiRiskEngine(startingCapitalDollars = 50.0, maxContractsHardCap = 5)
    }

    private fun createMarket(
        now: Long,
        ticker: String = "KXBTC15M-TEST",
        yesBid: Int = 50,
        yesAsk: Int = 52,
        noBid: Int = 48,
        noAsk: Int = 50,
        lastPrice: Int = 51
    ): KalshiMarket {
        return KalshiMarket(
            ticker = ticker,
            eventTicker = "KXBTC15M",
            seriesTicker = "KXBTC15M",
            title = "BTC 15m",
            subtitle = "Settlement",
            openTimeMs = now - 300_000L,
            closeTimeMs = now + 600_000L,
            expirationTimeMs = now + 600_000L,
            status = "active",
            yesBid = yesBid,
            yesAsk = yesAsk,
            noBid = noBid,
            noAsk = noAsk,
            lastPrice = lastPrice,
            strikePrice = btcPrice
        )
    }

    @Test
    fun testUnconfirmedOrderBookBlocksOrder() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val activeMarket = createMarket(now)
        engine.setStateForTesting(
            activeContract = activeMarket,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = null // No order book -> UNCONFIRMED
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        // Verifier returns UNCONFIRMED -> strictly NO ORDER submitted
        assertEquals("Order must NOT be submitted when verification is UNCONFIRMED", 0, mockClient.submittedOrders.size)
    }

    @Test
    fun testDivergenceBlocksOrder() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val activeMarket = createMarket(now, yesBid = 20, yesAsk = 25, noBid = 75, noAsk = 80, lastPrice = 22)
        // Book clearly shows DOWN bias
        val orderBook = KalshiOrderBookSnapshot(
            ticker = activeMarket.ticker,
            timestampMs = now,
            yesBids = listOf(KalshiOrderBookLevel(20, 0.20, 100.0)),
            noBids = listOf(KalshiOrderBookLevel(75, 0.75, 500.0)),
            bestYesBidCents = 20,
            bestNoBidCents = 75,
            impliedYesAskCents = 25,
            impliedNoAskCents = 80
        )
        engine.setStateForTesting(
            activeContract = activeMarket,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = orderBook
        )

        // Model predicts UP -> conflict/divergence with Kalshi market bias (DOWN)
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Order must NOT be submitted when order book shows DIVERGENCE", 0, mockClient.submittedOrders.size)
    }

    @Test
    fun testStaleOrderBookBlocksOrder() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val activeMarket = createMarket(now, yesBid = 70, yesAsk = 75, noBid = 25, noAsk = 30, lastPrice = 72)
        // Order book timestamp is 45 seconds old (> 30s threshold -> STALE)
        val staleBook = KalshiOrderBookSnapshot(
            ticker = activeMarket.ticker,
            timestampMs = now - 45_000L,
            yesBids = listOf(KalshiOrderBookLevel(70, 0.70, 100.0)),
            noBids = listOf(KalshiOrderBookLevel(25, 0.25, 50.0)),
            bestYesBidCents = 70,
            bestNoBidCents = 25,
            impliedYesAskCents = 75,
            impliedNoAskCents = 30
        )
        engine.setStateForTesting(
            activeContract = activeMarket,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = staleBook
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Order must NOT be submitted when order book is STALE", 0, mockClient.submittedOrders.size)
    }

    @Test
    fun testCrossedOrderBookBlocksOrder() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val activeMarket = createMarket(now, yesBid = 60, yesAsk = 55, noBid = 40, noAsk = 45, lastPrice = 58)
        val crossedBook = KalshiOrderBookSnapshot(
            ticker = activeMarket.ticker,
            timestampMs = now,
            yesBids = listOf(KalshiOrderBookLevel(60, 0.60, 100.0)),
            noBids = listOf(KalshiOrderBookLevel(40, 0.40, 100.0)),
            bestYesBidCents = 60,
            bestNoBidCents = 40,
            impliedYesAskCents = 55, // Crossed: 60 >= 55
            impliedNoAskCents = 40
        )
        engine.setStateForTesting(
            activeContract = activeMarket,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = crossedBook
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Order must NOT be submitted when order book is CROSSED", 0, mockClient.submittedOrders.size)
    }

    @Test
    fun testVerifiedAgreementPermitsOrderSubmission() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val activeMarket = createMarket(now, yesBid = 70, yesAsk = 75, noBid = 25, noAsk = 30, lastPrice = 72)
        // Authentic fresh order book confirming UP market bias
        val orderBook = KalshiOrderBookSnapshot(
            ticker = activeMarket.ticker,
            timestampMs = now,
            yesBids = listOf(KalshiOrderBookLevel(70, 0.70, 500.0)),
            noBids = listOf(KalshiOrderBookLevel(25, 0.25, 100.0)),
            bestYesBidCents = 70,
            bestNoBidCents = 25,
            impliedYesAskCents = 75,
            impliedNoAskCents = 30
        )
        engine.setStateForTesting(
            activeContract = activeMarket,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = orderBook
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            projectedPrice90s = btcPrice + 150.0,
            projectedDecision90s = "UP",
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        // Agreement verified -> order submitted successfully
        assertEquals("Order MUST be submitted when verification passes with AGREEMENT", 1, mockClient.submittedOrders.size)
        assertEquals("yes", mockClient.submittedOrders[0].side)
    }
}
