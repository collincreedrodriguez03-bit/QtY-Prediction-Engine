package com.example

import com.example.data.PricePoint
import com.example.engine.IndicatorSnapshot
import com.example.engine.PerformanceTracker
import com.example.engine.PredictionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit & Walk-Forward tests for Phase 4 & Phase 5 Performance & Learning Engine.
 */
class PerformanceTrackerTests {

    private lateinit var tracker: PerformanceTracker

    @Before
    fun setup() {
        tracker = PerformanceTracker()
    }

    private fun createDummyRecord(
        decision: String,
        currentPrice: Double,
        timestamp: Long,
        horizonSec: Int = 30
    ): PredictionRecord {
        return PredictionRecord(
            timestamp = timestamp,
            decision = decision,
            score = if (decision == "UP") 0.75 else 0.25,
            strength = "STRONG",
            currentPrice = currentPrice,
            predictedPrice = if (decision == "UP") currentPrice + 50.0 else currentPrice - 50.0,
            predictionHorizon = horizonSec,
            maturityTimestamp = timestamp + (horizonSec * 1000L),
            inputs = IndicatorSnapshot(
                ema9 = currentPrice + 5.0,
                ema21 = currentPrice - 5.0,
                rsi = 65.0,
                momentum = 20.0,
                velocity = 5.0,
                volatility = 25.0
            )
        )
    }

    @Test
    fun testPendingPredictionRegistration() {
        val now = 1700000000000L
        val rec = createDummyRecord("UP", 90000.0, now)
        tracker.registerPrediction(rec)

        assertEquals(1, tracker.getPendingPredictions().size)
        assertEquals(0, tracker.getResolvedPredictions().size)
    }

    @Test
    fun testPrematureResolutionDoesNotResolve() {
        val now = 1700000000000L
        val rec = createDummyRecord("UP", 90000.0, now)
        tracker.registerPrediction(rec)

        // Try resolving at +10s (before 30s maturity)
        val resolved = tracker.resolveMatured(90050.0, now + 10000L)
        assertTrue("Should not resolve before maturity timestamp", resolved.isEmpty())
        assertEquals(1, tracker.getPendingPredictions().size)
        assertEquals(0, tracker.getResolvedPredictions().size)
    }

    @Test
    fun testCorrectUpPredictionResolution() {
        val now = 1700000000000L
        val rec = createDummyRecord("UP", 90000.0, now)
        tracker.registerPrediction(rec)

        // Resolve at +30s with price increase ($90,000 -> $90,080)
        val resolved = tracker.resolveMatured(90080.0, now + 30000L)
        assertEquals(1, resolved.size)
        assertEquals("CORRECT", resolved[0].result)
        assertEquals(90080.0, resolved[0].actualPrice)

        val stats = tracker.computeStats(null)
        assertEquals(1, stats.totalResolved)
        assertEquals(1, stats.correctCount)
        assertEquals(0, stats.incorrectCount)
        assertEquals(100.0, stats.winRatePercent, 0.01)
        assertEquals(100.0, stats.upWinRatePercent, 0.01)
    }

    @Test
    fun testIncorrectDownPredictionResolution() {
        val now = 1700000000000L
        val rec = createDummyRecord("DOWN", 90000.0, now)
        tracker.registerPrediction(rec)

        // Resolve at +30s with price increase ($90,000 -> $90,040), opposite of DOWN prediction
        val resolved = tracker.resolveMatured(90040.0, now + 30000L)
        assertEquals(1, resolved.size)
        assertEquals("INCORRECT", resolved[0].result)

        val stats = tracker.computeStats(null)
        assertEquals(1, stats.totalResolved)
        assertEquals(0, stats.correctCount)
        assertEquals(1, stats.incorrectCount)
        assertEquals(0.0, stats.winRatePercent, 0.01)
        assertEquals(0.0, stats.downWinRatePercent, 0.01)
    }

    @Test
    fun testMultiPredictionWalkForwardAccuracy() {
        val now = 1700000000000L

        // Predict 4 trades
        val rec1 = createDummyRecord("UP", 90000.0, now) // Correct
        val rec2 = createDummyRecord("DOWN", 90050.0, now + 2000L) // Correct
        val rec3 = createDummyRecord("UP", 90020.0, now + 4000L) // Incorrect
        val rec4 = createDummyRecord("UP", 90010.0, now + 6000L) // Correct

        tracker.registerPrediction(rec1)
        tracker.registerPrediction(rec2)
        tracker.registerPrediction(rec3)
        tracker.registerPrediction(rec4)

        tracker.resolveMatured(90050.0, now + 30000L) // resolves rec1: 90050 > 90000 (Correct)
        tracker.resolveMatured(90020.0, now + 32000L) // resolves rec2: 90020 < 90050 (Correct)
        tracker.resolveMatured(90005.0, now + 34000L) // resolves rec3: 90005 < 90020 (Incorrect)
        tracker.resolveMatured(90040.0, now + 36000L) // resolves rec4: 90040 > 90010 (Correct)

        val stats = tracker.computeStats(null)
        assertEquals(4, stats.totalResolved)
        assertEquals(3, stats.correctCount)
        assertEquals(1, stats.incorrectCount)
        assertEquals(75.0, stats.winRatePercent, 0.1)
    }

    @Test
    fun testMarketRegimeClassification() {
        // High volatility regime
        val highVolSnapshot = IndicatorSnapshot(volatility = 95.0)
        val statsHighVol = tracker.computeStats(highVolSnapshot)
        assertEquals("HIGH VOLATILITY", statsHighVol.marketRegime)

        // Bull trend regime
        val bullSnapshot = IndicatorSnapshot(ema9 = 90100.0, ema21 = 90050.0, momentum = 30.0, volatility = 35.0)
        val statsBull = tracker.computeStats(bullSnapshot)
        assertEquals("TRENDING BULL", statsBull.marketRegime)

        // Bear trend regime
        val bearSnapshot = IndicatorSnapshot(ema9 = 90000.0, ema21 = 90050.0, momentum = -30.0, volatility = 35.0)
        val statsBear = tracker.computeStats(bearSnapshot)
        assertEquals("TRENDING BEAR", statsBear.marketRegime)
    }

    @Test
    fun testFactorAttributionCalculation() {
        val now = 1700000000000L
        val rec = createDummyRecord("UP", 90000.0, now)
        tracker.registerPrediction(rec)
        tracker.resolveMatured(90050.0, now + 30000L)

        val stats = tracker.computeStats(null)
        assertNotNull(stats.factorAttributions)
        assertTrue(stats.factorAttributions.any { it.factorName == "MOMENTUM" })
    }

    @Test
    fun test30sResolutionObservationTiming() {
        val now = 1700000000000L
        val rec = createDummyRecord("UP", 90000.0, now) // maturityTimestamp = now + 30,000ms
        tracker.registerPrediction(rec)

        // Simulate price history points
        val priceHistory = listOf(
            PricePoint(price = 90000.0, timestamp = now, exchange = "BINANCE"),
            PricePoint(price = 90050.0, timestamp = now + 15000L, exchange = "BINANCE"),
            PricePoint(price = 90080.0, timestamp = now + 30000L, exchange = "BINANCE"), // exact 30s price
            PricePoint(price = 89900.0, timestamp = now + 40000L, exchange = "BINANCE")  // delayed cycle price
        )

        // Delayed cycle arrives at now + 40,000ms with a dropped market price of $89,900
        val resolved = tracker.resolveMatured(
            currentPrice = 89900.0,
            currentTimestamp = now + 40000L,
            priceHistory = priceHistory
        )

        assertEquals(1, resolved.size)
        // Must resolve to the 30-second observation (90080.0), NOT the delayed cycle price (89900.0)
        assertEquals(90080.0, resolved[0].actualPrice)
        assertEquals("CORRECT", resolved[0].result)
    }

    @Test
    fun testContractSettlementReferenceValidation() {
        val now = 1700000000000L
        val strikeRef = 90100.0 // Contract defined strike reference

        // Model trades UP at currentPrice 90050.0, but strikeRef is 90100.0
        val rec = PredictionRecord(
            timestamp = now,
            decision = "UP",
            score = 0.8,
            strength = "STRONG",
            currentPrice = 90050.0,
            settlementReference = strikeRef,
            predictedPrice = 90150.0,
            predictionHorizon = 30,
            maturityTimestamp = now + 30000L,
            inputs = IndicatorSnapshot()
        )
        tracker.registerPrediction(rec)

        // Price goes up to 90080.0 (higher than currentPrice 90050, but below contract reference 90100)
        val resolved = tracker.resolveMatured(
            currentPrice = 90080.0,
            currentTimestamp = now + 30000L
        )

        assertEquals(1, resolved.size)
        // Since price settled below the contract reference (90080 < 90100), the UP contract is INCORRECT
        assertEquals("INCORRECT", resolved[0].result)
    }
}
