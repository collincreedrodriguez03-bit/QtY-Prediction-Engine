package com.example

import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.kalshi.*
import com.example.testutil.MockKalshiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite verifying:
 * 1. Duplicate Order Protection & Hydration
 * 2. Order Lifecycle State Machine
 * 3. Fill Verification & Fill Timeout
 * 4. Post-Order Reconciliation & Fail-Closed
 * 5. Hard Loss Limit & Exposure Limit
 * 6. Profit-Only Capital Accounting
 */
class KalshiOrderLifecycleAndRiskTest {

    private lateinit var mockApiClient: MockKalshiApiClient
    private lateinit var priceHistory: PriceHistory
    private lateinit var executionStore: InMemoryKalshiExecutionStore
    private lateinit var riskEngine: KalshiRiskEngine
    private lateinit var engine: KalshiAutomationEngine

    @Before
    fun setUp() {
        mockApiClient = MockKalshiApiClient()
        priceHistory = PriceHistory(maxCapacity = 100)
        executionStore = InMemoryKalshiExecutionStore()
        riskEngine = KalshiRiskEngine(
            startingCapitalDollars = 10.0,
            maxContractsHardCap = 5,
            hardLossLimitDollars = 5.0,
            hardExposureLimitDollars = 20.0
        )
        engine = KalshiAutomationEngine(
            apiClient = mockApiClient,
            priceHistory = priceHistory,
            tradeSizeLimit = 2,
            riskEngine = riskEngine,
            executionStore = executionStore,
            fillTimeoutMs = 1_000L
        )
    }

    private fun createPrediction(decision: String = "UP", score: Double = 0.85, now: Long = System.currentTimeMillis()): PredictionRecord {
        return PredictionRecord(
            inputs = IndicatorSnapshot(volatility = 15.0),
            decision = decision,
            score = score,
            strength = "STRONG",
            predictedPrice = 90100.0,
            currentPrice = 90000.0,
            settlementReference = 90000.0,
            timestamp = now
        )
    }

    private fun createMarket(now: Long, ticker: String): KalshiMarket {
        return KalshiMarket(
            ticker = ticker,
            eventTicker = "KXBTC15M",
            seriesTicker = KalshiApiClient.BTC_15M_SERIES,
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
            strikePrice = 90000.0
        )
    }

    private fun createBook(now: Long, ticker: String): KalshiOrderBookSnapshot {
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
    fun testDuplicateOrderProtection_HydrationPreventsDuplicate() = runBlocking {
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = 90000.0, timestamp = now))

        val existingOrder = KalshiOrderRecord(
            clientOrderId = "client_order_dup_1",
            ticker = "KXBTC15M-260905-1500",
            side = "bid",
            action = "buy",
            requestedCount = 1,
            filledCount = 0,
            remainingCount = 1,
            limitPriceCents = 50,
            lifecycleState = OrderLifecycleState.SUBMITTED,
            placedTimestamp = now,
            updatedTimestamp = now
        )
        executionStore.recordOrder(existingOrder)

        // Hydrate the engine from the store
        engine.hydrateFromStore()

        val state = engine.state.value
        assertEquals(1, state.activeOrderRecords.size)
        assertEquals("client_order_dup_1", state.activeOrderRecords[0].clientOrderId)

        val market = createMarket(now, "KXBTC15M-260905-1500")
        val book = createBook(now, market.ticker)
        engine.setStateForTesting(
            isAuthenticated = true,
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000L),
            latestOrderBook = book
        )
        engine.toggleAutomation(true)

        // Attempt new prediction on the same contract ticker
        val prediction = createPrediction("UP", 0.85, now)
        engine.onNewPrediction(prediction, 90000.0, now)

        // Submission should be skipped because of active order in execution store
        assertEquals(0, mockApiClient.submittedOrders.size)
    }

    @Test
    fun testOrderLifecycle_SuccessfulSubmissionPersistsStates() = runBlocking {
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = 90000.0, timestamp = now))

        val market = createMarket(now, "KXBTC15M-260905-1600")
        val book = createBook(now, market.ticker)
        engine.setStateForTesting(
            isAuthenticated = true,
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000L),
            latestOrderBook = book
        )
        mockApiClient.submitResult = Result.success(
            KalshiOrderResponse(
                orderId = "ord_success_1",
                clientOrderId = "cid_1",
                ticker = market.ticker,
                status = "executed",
                action = "buy",
                side = "bid",
                count = 1,
                filledCount = 1,
                price = 75,
                placeTimeMs = now
            )
        )
        engine.toggleAutomation(true)

        val prediction = createPrediction("UP", 0.85, now)
        engine.onNewPrediction(prediction, 90000.0, now)

        assertEquals(1, mockApiClient.submittedOrders.size)
        val submittedOrder = mockApiClient.submittedOrders[0]
        assertEquals("KXBTC15M-260905-1600", submittedOrder.ticker)

        val orderRecord = executionStore.getOrderByClientOrderId(submittedOrder.clientOrderId)
        assertNotNull(orderRecord)
        assertEquals(OrderLifecycleState.FILLED, orderRecord!!.lifecycleState)
        assertEquals(1, orderRecord.filledCount)
    }

    @Test
    fun testOrderLifecycle_RejectedOrderTransitionsToFailed() = runBlocking {
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = 90000.0, timestamp = now))

        val market = createMarket(now, "KXBTC15M-260905-1700")
        val book = createBook(now, market.ticker)
        engine.setStateForTesting(
            isAuthenticated = true,
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000L),
            latestOrderBook = book
        )
        mockApiClient.submitResult = Result.success(
            KalshiOrderResponse(
                orderId = "ord_rej_1",
                clientOrderId = "cid_rej",
                ticker = market.ticker,
                status = "rejected",
                action = "buy",
                side = "bid",
                count = 1,
                filledCount = 0,
                price = 75,
                placeTimeMs = now
            )
        )
        engine.toggleAutomation(true)

        val prediction = createPrediction("UP", 0.85, now)
        engine.onNewPrediction(prediction, 90000.0, now)

        val ordersInStore = executionStore.getOrdersByContract("KXBTC15M-260905-1700")
        assertEquals(1, ordersInStore.size)
        assertEquals(OrderLifecycleState.FAILED, ordersInStore[0].lifecycleState)
    }

    @Test
    fun testFillTimeout_CancelsRestingOrder() = runBlocking {
        val placedTime = System.currentTimeMillis() - 2_000L // 2s ago > 1s timeout
        val restingRecord = KalshiOrderRecord(
            clientOrderId = "client_resting_1",
            orderId = "ord_mock_resting_1",
            ticker = "KXBTC15M-260905-1800",
            side = "bid",
            action = "buy",
            requestedCount = 2,
            filledCount = 0,
            remainingCount = 2,
            limitPriceCents = 50,
            lifecycleState = OrderLifecycleState.SUBMITTED,
            placedTimestamp = placedTime,
            updatedTimestamp = placedTime
        )
        executionStore.recordOrder(restingRecord)
        mockApiClient.getOrderResult = { orderId ->
            Result.success(
                KalshiOrderResponse(
                    orderId = orderId,
                    clientOrderId = "client_resting_1",
                    ticker = "KXBTC15M-260905-1800",
                    side = "bid",
                    action = "buy",
                    count = 2,
                    filledCount = 0,
                    remainingCount = 2,
                    price = 50,
                    status = "resting",
                    placeTimeMs = placedTime
                )
            )
        }
        engine.toggleAutomation(true)

        val updatedRecord = engine.handleFillTimeoutAndVerification(restingRecord, 0L)
        assertEquals(OrderLifecycleState.CANCELLED, updatedRecord.lifecycleState)
        assertTrue(mockApiClient.cancelledOrders.contains("ord_mock_resting_1"))
    }

    @Test
    fun testPostOrderReconciliation_MismatchTriggersFailClosed() = runBlocking {
        val restingRecord = KalshiOrderRecord(
            clientOrderId = "client_unknown_1",
            orderId = "ord_not_found_on_exchange",
            ticker = "KXBTC15M-260905-1900",
            side = "bid",
            action = "buy",
            requestedCount = 1,
            filledCount = 0,
            remainingCount = 1,
            limitPriceCents = 50,
            lifecycleState = OrderLifecycleState.SUBMITTED,
            placedTimestamp = System.currentTimeMillis(),
            updatedTimestamp = System.currentTimeMillis()
        )
        executionStore.recordOrder(restingRecord)

        // Mock exchange does not know this order and getOrder returns 404 failure
        mockApiClient.openOrdersResult = Result.success(emptyList())
        mockApiClient.getOrderResult = { Result.failure(Exception("Order not found on exchange")) }

        engine.toggleAutomation(true)
        assertTrue(engine.state.value.isAutomationEnabled)

        val reconcileRes = engine.reconcileWithExchange()
        assertTrue(reconcileRes.isFailure)

        // Verification: System must fail closed
        assertFalse(engine.state.value.isAutomationEnabled)
        assertTrue(engine.state.value.isReconciliationFailed)
        assertNotNull(engine.state.value.error)
    }

    @Test
    fun testHardLossLimit_ExecutionBlocker() {
        // Limit is $5.00
        val pred = createPrediction("UP", 0.85)

        // Trade 1 loses $3.00 -> cumulative loss $3.00 < $5.00
        riskEngine.recordSettlement(isWin = false, realizedProfit = -3.00)
        assertEquals(3.00, riskEngine.getCumulativeLossDollars(), 1e-4)

        // Sizing should be blocked because profit-only capital rule allows 0 eligible capital on loss
        val eval1 = riskEngine.evaluateOrderSizing(pred, 50, 5.0, 0.0)
        assertFalse(eval1.isApproved)

        // Now simulate reaching hard loss limit ($5.00)
        riskEngine.recordSettlement(isWin = false, realizedProfit = -2.50)
        assertEquals(5.50, riskEngine.getCumulativeLossDollars(), 1e-4)

        val eval2 = riskEngine.evaluateOrderSizing(pred, 50, 5.0, 0.0)
        assertFalse(eval2.isApproved)
        assertTrue(eval2.reason.contains("Hard loss limit"))
    }

    @Test
    fun testHardExposureLimit_ExecutionBlocker() {
        val pred = createPrediction("UP", 0.85)

        // Current exposure is $25.00, exceeding hardExposureLimitDollars ($20.00)
        val eval = riskEngine.evaluateOrderSizing(
            prediction = pred,
            contractPriceCents = 50,
            volatilityBps = 5.0,
            currentExposureDollars = 25.00
        )
        assertFalse(eval.isApproved)
        assertTrue(eval.reason.contains("Hard exposure limit"))
    }

    @Test
    fun testProfitOnlyCapitalAccounting() {
        val pred = createPrediction("UP", 0.85)

        // Trade 1: Starting capital $10.00
        val eval1 = riskEngine.evaluateOrderSizing(pred, 50, 5.0, 0.0)
        assertTrue(eval1.isApproved)
        assertEquals(10.0, eval1.eligibleCapitalDollars, 1e-4)

        // Trade 1 WIN: +$4.00 profit
        riskEngine.recordSettlement(isWin = true, realizedProfit = 4.00)
        val eval2 = riskEngine.evaluateOrderSizing(pred, 50, 5.0, 0.0)
        assertTrue(eval2.isApproved)
        // Only realized profit of $4.00 is eligible! Starting capital of $10 is NOT recycled
        assertEquals(4.00, eval2.eligibleCapitalDollars, 1e-4)

        // Trade 2 LOSS: -$4.00
        riskEngine.recordSettlement(isWin = false, realizedProfit = -4.00)
        val eval3 = riskEngine.evaluateOrderSizing(pred, 50, 5.0, 0.0)
        // Capital is now $0.00 -> blocked
        assertFalse(eval3.isApproved)
        assertEquals(0.00, eval3.eligibleCapitalDollars, 1e-4)
    }

    @Test
    fun testAutomationOffDuringActiveExecutionCoroutine() = runBlocking {
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = 90000.0, timestamp = now))

        val market = createMarket(now, "KXBTC15M-260905-2000")
        val book = createBook(now, market.ticker)
        engine.setStateForTesting(
            isAuthenticated = true,
            activeContract = market,
            balance = KalshiBalance(balanceCents = 10000L),
            latestOrderBook = book
        )

        // Turn ON automation then immediately turn it OFF (invalidating execution generation)
        engine.toggleAutomation(true)
        engine.toggleAutomation(false)

        val prediction = createPrediction("UP", 0.85, now)
        engine.onNewPrediction(prediction, 90000.0, now)

        // Must submit NO orders when automation is OFF
        assertEquals(0, mockApiClient.submittedOrders.size)
    }

    @Test
    fun testInsufficientBalanceRejection() = runBlocking {
        val now = System.currentTimeMillis()
        priceHistory.add(PricePoint(price = 90000.0, timestamp = now))

        val market = createMarket(now, "KXBTC15M-260905-2100")
        val book = createBook(now, market.ticker)
        engine.setStateForTesting(
            isAuthenticated = true,
            activeContract = market,
            // Only 10 cents in balance, contract costs 75 cents
            balance = KalshiBalance(balanceCents = 10L),
            latestOrderBook = book
        )
        engine.toggleAutomation(true)

        val prediction = createPrediction("UP", 0.85, now)
        engine.onNewPrediction(prediction, 90000.0, now)

        // Must reject order submission due to insufficient balance
        assertEquals(0, mockApiClient.submittedOrders.size)
        assertNotNull(engine.state.value.error)
        assertTrue(engine.state.value.error!!.contains("balance", ignoreCase = true))
    }

    @Test
    fun testPartialFillHandling() = runBlocking {
        val placedTime = System.currentTimeMillis() - 2_000L // 2s ago > 1s timeout
        val restingRecord = KalshiOrderRecord(
            clientOrderId = "client_partial_1",
            orderId = "ord_mock_partial_1",
            ticker = "KXBTC15M-260905-2200",
            side = "bid",
            action = "buy",
            requestedCount = 4,
            filledCount = 0,
            remainingCount = 4,
            limitPriceCents = 50,
            lifecycleState = OrderLifecycleState.SUBMITTED,
            placedTimestamp = placedTime,
            updatedTimestamp = placedTime
        )
        executionStore.recordOrder(restingRecord)

        // Order has 2 fills out of 4 on the exchange before/during cancellation
        mockApiClient.getOrderResult = { orderId ->
            Result.success(
                KalshiOrderResponse(
                    orderId = orderId,
                    clientOrderId = "client_partial_1",
                    ticker = "KXBTC15M-260905-2200",
                    side = "bid",
                    action = "buy",
                    count = 4,
                    filledCount = 2,
                    remainingCount = 2,
                    price = 50,
                    status = "resting",
                    placeTimeMs = placedTime
                )
            )
        }
        engine.toggleAutomation(true)

        val updatedRecord = engine.handleFillTimeoutAndVerification(restingRecord, 0L)
        // Must accurately transition to PARTIALLY_FILLED instead of claiming 0 or full filled
        assertEquals(OrderLifecycleState.PARTIALLY_FILLED, updatedRecord.lifecycleState)
        assertEquals(2, updatedRecord.filledCount)
        assertEquals(2, updatedRecord.remainingCount)
        assertTrue(mockApiClient.cancelledOrders.contains("ord_mock_partial_1"))
    }
}
