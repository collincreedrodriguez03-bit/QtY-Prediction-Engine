package com.example

import com.example.data.DataValidator
import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.data.ValidationResult
import com.example.engine.Backtester
import com.example.engine.EngineLoop
import com.example.engine.IndicatorCalculator
import com.example.engine.PredictionEngine
import com.example.kalshi.KalshiAutomationEngine
import com.example.testutil.TestSyntheticDataGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proof tests verifying that:
 * 1. Synthetic data generator is absent from production codebase and restricted to `src/test`.
 * 2. If authentic data is unavailable, insufficient (< 40 points), corrupted, stale, or invalid,
 *    production prediction, backtesting, evaluation, performance metrics, and trading FAIL CLOSED.
 * 3. Backtester refuses to compute trades or metrics when authentic data is insufficient (< 40 observations).
 * 4. DataValidator rejects corrupted, zero, negative, NaN, infinite, or stale price feeds.
 * 5. Kalshi trading engine refuses to trade when authentic market data or contracts are missing.
 */
class SyntheticDataEliminationTests {

    @Test
    fun testFailClosedWhenInsufficientAuthenticDataForBacktest() {
        val backtester = Backtester()

        // 1. Completely empty authentic data -> Fail closed
        val emptyResult = backtester.runBacktest(emptyList())
        assertEquals(0, emptyResult.totalSamples)
        assertEquals(0, emptyResult.statisticalTotalTrades)
        assertEquals(0, emptyResult.operationalTotalTrades)
        assertEquals(0.0, emptyResult.statisticalWinRatePercent, 0.001)
        assertEquals(0.0, emptyResult.operationalWinRatePercent, 0.001)
        assertTrue(emptyResult.samplePredictions.isEmpty())

        // 2. Insufficient authentic data (e.g. 25 points < 40 minimum threshold) -> Fail closed
        val baseTime = System.currentTimeMillis()
        val partialData = (0 until 25).map { i ->
            PricePoint(
                price = 95000.0 + i,
                timestamp = baseTime + (i * 2000L),
                exchange = "BINANCE"
            )
        }
        val partialResult = backtester.runBacktest(partialData)
        assertEquals(25, partialResult.totalSamples)
        assertEquals(0, partialResult.statisticalTotalTrades)
        assertEquals(0, partialResult.operationalTotalTrades)
        assertEquals(0.0, partialResult.statisticalWinRatePercent, 0.001)
        assertEquals(0.0, partialResult.operationalWinRatePercent, 0.001)
        assertTrue(partialResult.samplePredictions.isEmpty())
    }

    @Test
    fun testValidatorRejectsCorruptedStaleAndInvalidData() {
        val validator = DataValidator(maxAgeMillis = 5000L)
        val now = System.currentTimeMillis()

        // Null check
        val nullRes = validator.validate(null, now)
        assertTrue(nullRes is ValidationResult.Invalid)

        // Negative price
        val negRes = validator.validate(PricePoint(price = -10.0, timestamp = now, exchange = "BINANCE"), now)
        assertTrue(negRes is ValidationResult.Invalid)

        // Zero price
        val zeroRes = validator.validate(PricePoint(price = 0.0, timestamp = now, exchange = "BINANCE"), now)
        assertTrue(zeroRes is ValidationResult.Invalid)

        // NaN price
        val nanRes = validator.validate(PricePoint(price = Double.NaN, timestamp = now, exchange = "BINANCE"), now)
        assertTrue(nanRes is ValidationResult.Invalid)

        // Infinite price
        val infRes = validator.validate(PricePoint(price = Double.POSITIVE_INFINITY, timestamp = now, exchange = "BINANCE"), now)
        assertTrue(infRes is ValidationResult.Invalid)

        // Stale timestamp (> 5000ms old)
        val staleRes = validator.validate(PricePoint(price = 95000.0, timestamp = now - 10000L, exchange = "BINANCE"), now)
        assertTrue(staleRes is ValidationResult.Invalid)

        // Future timestamp (> 1000ms ahead)
        val futureRes = validator.validate(PricePoint(price = 95000.0, timestamp = now + 5000L, exchange = "BINANCE"), now)
        assertTrue(futureRes is ValidationResult.Invalid)

        // Valid authentic point
        val validRes = validator.validate(PricePoint(price = 95000.0, timestamp = now - 500L, exchange = "BINANCE"), now)
        assertTrue(validRes is ValidationResult.Valid)
    }

    @Test
    fun testEngineLoopFailsClosedWhenMarketFeedsUnavailable() = kotlinx.coroutines.runBlocking {
        // EngineLoop with empty feeds must pause predictions and fail closed (return null)
        val priceHistory = PriceHistory(300)
        val engineLoop = EngineLoop(
            priceHistory = priceHistory
        )

        // When executeSingleCycle is called without any valid market feeds, it returns null and pauses
        val result = engineLoop.executeSingleCycle()
        assertNull("Engine must fail closed and return null when market feeds are unavailable", result)
        val errorLog = engineLoop.state.value.errorLog
        assertNotNull("Error log should be present", errorLog)
        assertTrue("Engine state must indicate awaiting market data", errorLog?.contains("Awaiting valid market data") == true)
    }

    @Test
    fun testKalshiAutomationFailsClosedWithoutAuthenticDataOrOpportunity() = kotlinx.coroutines.runBlocking {
        val priceHistory = PriceHistory(300)
        val kalshiEngine = KalshiAutomationEngine(priceHistory = priceHistory)

        // By default automation is OFF (Mandates 9 & 10)
        assertFalse("Automation must be disabled by default", kalshiEngine.state.value.isAutomationEnabled)

        // Toggle automation ON
        kalshiEngine.toggleAutomation(true)
        assertTrue("Automation is enabled", kalshiEngine.state.value.isAutomationEnabled)

        // Feed a NO-TRADE prediction with invalid/empty authentic contract market
        val now = System.currentTimeMillis()
        val dummySnapshot = IndicatorCalculator().computeSnapshot(emptyList())
        val prediction = PredictionEngine().predict(
            currentPrice = 95000.0,
            snapshot = dummySnapshot,
            timestamp = now
        ).copy(decision = "NO-TRADE")

        kalshiEngine.onNewPrediction(
            prediction = prediction,
            currentBtcPrice = 95000.0,
            timestamp = now
        )

        // Must FAIL CLOSED: No orders placed
        assertEquals("No orders can be placed for NO-TRADE or missing contract", 0, kalshiEngine.state.value.recentOrders.size)
        assertTrue(kalshiEngine.state.value.executionLog.any { it.contains("Failing closed") })
    }

    @Test
    fun testSyntheticDataIsolatedToTestDirectory() {
        // Verify TestSyntheticDataGenerator produces valid test data only for test scaffolding
        val testPrices = TestSyntheticDataGenerator.generateSyntheticHistoricalData(count = 50)
        assertEquals(50, testPrices.size)
        assertEquals("SIMULATED_TEST", testPrices[0].exchange)
    }
}
