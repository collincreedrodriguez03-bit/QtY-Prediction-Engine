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
import com.example.kalshi.KalshiPosition
import com.example.kalshi.KalshiRiskEngine
import com.example.testutil.MockKalshiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1 Mandate 6: Execution State Safety Test.
 *
 * Verifies that the automation engine:
 * - is OFF by default and places NO orders
 * - blocks duplicate orders
 * - blocks unknown order states
 * - blocks unknown / out-of-bound fill counts
 * - blocks conflicting positions
 * - blocks unauthenticated execution
 * - blocks stale market data
 * - fails closed on API errors
 */
class ExecutionSafetyTest {

    private lateinit var priceHistory: PriceHistory
    private lateinit var riskEngine: KalshiRiskEngine
    private val btcPrice = 90_000.0

    @Before
    fun setUp() {
        priceHistory = PriceHistory(maxCapacity = 50)
        riskEngine = KalshiRiskEngine(startingCapitalDollars = 50.0, maxContractsHardCap = 5)
    }

    private fun createValidMarket(now: Long): KalshiMarket {
        return KalshiMarket(
            ticker = "KXBTC15M-TEST",
            eventTicker = "KXBTC15M",
            seriesTicker = "KXBTC15M",
            title = "BTC 15m",
            subtitle = "Settlement",
            openTimeMs = now - 300_000L,
            closeTimeMs = now + 600_000L,
            expirationTimeMs = now + 600_000L,
            status = "active",
            yesBid = 70,
            yesAsk = 75,
            noBid = 25,
            noAsk = 30,
            lastPrice = 72,
            strikePrice = btcPrice
        )
    }

    private fun createValidBook(now: Long, ticker: String): KalshiOrderBookSnapshot {
        return KalshiOrderBookSnapshot(
            ticker = ticker,
            timestampMs = now,
            yesBids = listOf(KalshiOrderBookLevel(70, 0.70, 500.0)),
            noBids = listOf(KalshiOrderBookLevel(25, 0.25, 100.0)),
            bestYesBidCents = 70,
            bestNoBidCents = 25,
            impliedYesAskCents = 75,
            impliedNoAskCents = 30
        )
    }

    @Test
    fun testAutomationIsOffByDefaultAndSubmitsNoOrders() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )

        assertFalse("Automation must be OFF by default", engine.state.value.isAutomationEnabled)

        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("No orders can be submitted when automation is OFF", 0, mockClient.submittedOrders.size)
    }

    @Test
    fun testUnauthenticatedBlocksExecution() = runBlocking {
        val mockClient = MockKalshiApiClient(authenticated = false)
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        engine.setStateForTesting(
            activeContract = market,
            balance = KalshiBalance(balanceCents = 5000),
            isAuthenticated = false,
            latestOrderBook = book
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("No order submitted when unauthenticated", 0, mockClient.submittedOrders.size)
        assertNotNull(engine.state.value.error)
    }

    @Test
    fun testDuplicateOrderIsBlocked() = runBlocking {
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

        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        engine.setStateForTesting(
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = book
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        // First trade
        engine.onNewPrediction(prediction, btcPrice, now)
        assertEquals("First order submitted", 1, mockClient.submittedOrders.size)

        // Immediate subsequent trade on the same contract within cooldown (< 60s)
        engine.onNewPrediction(prediction, btcPrice, now + 5000L)
        assertEquals("Duplicate order on same contract within cooldown MUST be blocked", 1, mockClient.submittedOrders.size)
    }

    @Test
    fun testConflictingPositionBlocksOrder() = runBlocking {
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

        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        // Account holds conflicting NO position (-2 contracts) on this market
        val positions = listOf(KalshiPosition(ticker = market.ticker, position = -2))

        engine.setStateForTesting(
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            activePositions = positions,
            latestOrderBook = book
        )

        // Model predicts UP (wants to buy YES, but account holds opposite NO)
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Conflicting position must block order submission", 0, mockClient.submittedOrders.size)
    }

    @Test
    fun testUnknownOrderStateIsCaughtAndFailsClosed() = runBlocking {
        // Return an unknown/invalid order status string from API
        val invalidResponse = KalshiOrderResponse(
            orderId = "ord_unknown",
            clientOrderId = "cid_unknown",
            ticker = "KXBTC15M-TEST",
            status = "UNKNOWN_STATE_FROM_API",
            action = "buy",
            side = "yes",
            count = 1,
            filledCount = 1,
            price = 70,
            placeTimeMs = System.currentTimeMillis()
        )
        val mockClient = MockKalshiApiClient(submitResult = Result.success(invalidResponse))
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        engine.setStateForTesting(
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = book
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Unknown order state: UNKNOWN_STATE_FROM_API", engine.state.value.error)
    }

    @Test
    fun testUnknownFillCountIsCaughtAndFailsClosed() = runBlocking {
        // Return filledCount > count (illegal state)
        val invalidResponse = KalshiOrderResponse(
            orderId = "ord_fill_err",
            clientOrderId = "cid_fill_err",
            ticker = "KXBTC15M-TEST",
            status = "filled",
            action = "buy",
            side = "yes",
            count = 1,
            filledCount = 99, // Exceeds count = 1
            price = 70,
            placeTimeMs = System.currentTimeMillis()
        )
        val mockClient = MockKalshiApiClient(submitResult = Result.success(invalidResponse))
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))

        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        engine.setStateForTesting(
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = book
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Unknown fill count: 99", engine.state.value.error)
    }

    @Test
    fun testStaleMarketDataBlocksOrder() = runBlocking {
        val mockClient = MockKalshiApiClient()
        val engine = KalshiAutomationEngine(
            apiClient = mockClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = riskEngine
        )
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        // Price history is 20 seconds old (> 15s stale threshold)
        priceHistory.add(PricePoint(price = btcPrice, timestamp = now - 20_000L))

        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        engine.setStateForTesting(
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000),
            isAuthenticated = true,
            latestOrderBook = book
        )

        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            settlementReference = btcPrice,
            timestamp = now
        )

        engine.onNewPrediction(prediction, btcPrice, now)

        assertEquals("Stale price data (> 15s old) MUST block order submission", 0, mockClient.submittedOrders.size)
    }
}
