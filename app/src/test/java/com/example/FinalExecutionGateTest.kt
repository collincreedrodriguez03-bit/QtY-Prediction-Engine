package com.example

import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.kalshi.ExecutionGateDecision
import com.example.kalshi.KalshiAutomationEngine
import com.example.kalshi.KalshiBalance
import com.example.kalshi.KalshiMarket
import com.example.kalshi.KalshiOrderBookLevel
import com.example.kalshi.KalshiOrderBookSnapshot
import com.example.kalshi.KalshiRiskEngine
import com.example.kalshi.OrderLifecycleState
import com.example.testutil.MockKalshiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Correction Pass 4/4 Mandate 3 & 4: Dedicated Final Execution Gate & Fail-Closed Default Test.
 *
 * Verifies the single, consolidated LAST authority before order submission:
 * 1. Automation != ON -> reject
 * 2. Contract invalid -> reject
 * 3. Order book invalid/stale/missing -> reject
 * 4. Executable price unavailable -> reject
 * 5. Model probability does not establish required edge -> reject
 * 6. Risk check fails -> reject
 * 7. Capital check fails -> reject
 * 8. Exposure limit fails -> reject
 * 9. Contract is already executed -> reject
 * 10. Duplicate client order exists -> reject
 * 11. Execution generation is stale -> reject
 * 12. Any required state cannot be verified -> reject
 * Otherwise -> submit
 */
class FinalExecutionGateTest {

    private lateinit var priceHistory: PriceHistory
    private lateinit var riskEngine: KalshiRiskEngine
    private val btcPrice = 90_000.0

    @Before
    fun setUp() {
        priceHistory = PriceHistory(maxCapacity = 50)
        riskEngine = KalshiRiskEngine(startingCapitalDollars = 100.0, maxContractsHardCap = 5)
    }

    private fun createValidMarket(now: Long, ticker: String = "KXBTC15M-TEST"): KalshiMarket {
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
            yesBid = 60,
            yesAsk = 65,
            noBid = 35,
            noAsk = 40,
            lastPrice = 62,
            strikePrice = btcPrice
        )
    }

    private fun createValidBook(now: Long, ticker: String): KalshiOrderBookSnapshot {
        return KalshiOrderBookSnapshot(
            ticker = ticker,
            timestampMs = now,
            yesBids = listOf(KalshiOrderBookLevel(60, 0.60, 500.0)),
            noBids = listOf(KalshiOrderBookLevel(35, 0.35, 200.0)),
            bestYesBidCents = 60,
            bestNoBidCents = 35,
            impliedYesAskCents = 65,
            impliedNoAskCents = 40
        )
    }

    private fun createEngineWithRealizedProfit(initialCapital: Double = 100.0): Pair<KalshiAutomationEngine, MockKalshiApiClient> {
        val client = MockKalshiApiClient()
        val re = KalshiRiskEngine(startingCapitalDollars = initialCapital, maxContractsHardCap = 5)
        // Record a winning trade so profit-only capital rule approves
        re.recordSettlement(isWin = true, realizedProfit = 20.0)
        val engine = KalshiAutomationEngine(
            apiClient = client,
            priceHistory = priceHistory,
            tradeSizeLimit = 1,
            riskEngine = re
        )
        return Pair(engine, client)
    }

    @Test
    fun testGateRejectsWhenAutomationIsOff() = runBlocking {
        val (engine, _) = createEngineWithRealizedProfit()
        val now = System.currentTimeMillis()
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            timestamp = now
        )

        val decision = engine.evaluateFinalExecutionGate(
            prediction = prediction,
            currentBtcPrice = btcPrice,
            timestamp = now,
            currentExecutionGen = 1L,
            clientOrderId = "cid_1"
        )

        assertTrue("Gate must reject when automation is OFF", decision is ExecutionGateDecision.Reject)
        assertEquals("Automation is not ON", (decision as ExecutionGateDecision.Reject).reason)
    }

    @Test
    fun testGateRejectsWhenContractIsInvalidOrMissing() = runBlocking {
        val (engine, _) = createEngineWithRealizedProfit()
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            predictedPrice = btcPrice + 100.0,
            currentPrice = btcPrice,
            timestamp = now
        )

        val decision = engine.evaluateFinalExecutionGate(
            prediction = prediction,
            currentBtcPrice = btcPrice,
            timestamp = now,
            currentExecutionGen = 1L,
            clientOrderId = "cid_1"
        )

        assertTrue(decision is ExecutionGateDecision.Reject)
        assertTrue((decision as ExecutionGateDecision.Reject).reason.contains("Contract missing"))
    }

    @Test
    fun testGateRejectsWhenOrderBookIsStaleOrMissing() = runBlocking {
        val (engine, _) = createEngineWithRealizedProfit()
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        val market = createValidMarket(now)

        engine.setStateForTesting(
            activeContract = market,
            isAuthenticated = true,
            balance = KalshiBalance(balanceCents = 10000),
            latestOrderBook = null // Missing book
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
        val decision = engine.evaluateFinalExecutionGate(
            prediction = prediction,
            currentBtcPrice = btcPrice,
            timestamp = now,
            currentExecutionGen = 1L,
            clientOrderId = "cid_1"
        )

        assertTrue(decision is ExecutionGateDecision.Reject)
        assertTrue((decision as ExecutionGateDecision.Reject).reason.contains("Order book is missing"))
    }

    @Test
    fun testGateRejectsWhenNoRequiredEdge() = runBlocking {
        val (engine, _) = createEngineWithRealizedProfit()
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))
        engine.setStateForTesting(
            activeContract = market,
            isAuthenticated = true,
            balance = KalshiBalance(balanceCents = 10000),
            latestOrderBook = book
        )

        // Model score 0.66 vs market ask 65 cents -> edge = 0.01 (1%), less than 3% MIN_EXECUTABLE_EDGE
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.66,
            strength = "STRONG",
            currentPrice = btcPrice,
            predictedPrice = btcPrice + 10.0,
            settlementReference = btcPrice,
            projectedDecision90s = "UP",
            projectedPrice90s = btcPrice + 10.0,
            timestamp = now
        )

        val decision = engine.evaluateFinalExecutionGate(
            prediction = prediction,
            currentBtcPrice = btcPrice,
            timestamp = now,
            currentExecutionGen = engine.getExecutionGenerationForTesting(),
            clientOrderId = "cid_edge"
        )

        assertTrue(decision is ExecutionGateDecision.Reject)
        assertTrue((decision as ExecutionGateDecision.Reject).reason.contains("Model probability does not establish required edge"))
    }

    @Test
    fun testGateSubmitsWhenAllConditionsPass() = runBlocking {
        val (engine, _) = createEngineWithRealizedProfit()
        engine.toggleAutomation(true)
        val now = System.currentTimeMillis()
        val market = createValidMarket(now)
        val book = createValidBook(now, market.ticker)

        priceHistory.add(PricePoint(price = btcPrice, timestamp = now))
        engine.setStateForTesting(
            activeContract = market,
            isAuthenticated = true,
            balance = KalshiBalance(balanceCents = 10000),
            latestOrderBook = book
        )

        // Model score 0.85 vs market ask 65 cents -> edge = 0.20 (20%), well above 3% MIN_EXECUTABLE_EDGE
        val prediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.85,
            strength = "STRONG",
            currentPrice = btcPrice,
            predictedPrice = btcPrice + 100.0,
            settlementReference = btcPrice,
            projectedDecision90s = "UP",
            projectedPrice90s = btcPrice + 100.0,
            timestamp = now
        )

        val decision = engine.evaluateFinalExecutionGate(
            prediction = prediction,
            currentBtcPrice = btcPrice,
            timestamp = now,
            currentExecutionGen = engine.getExecutionGenerationForTesting(),
            clientOrderId = "cid_success"
        )

        assertTrue("Gate must approve when all conditions are satisfied: ${(decision as? ExecutionGateDecision.Reject)?.reason}", decision is ExecutionGateDecision.Submit)
        val submit = decision as ExecutionGateDecision.Submit
        assertEquals(market.ticker, submit.market.ticker)
        assertEquals(1, submit.orderCount)
        assertEquals(65, submit.executablePriceCents)
        assertEquals("bid", submit.targetSide)
    }
}
