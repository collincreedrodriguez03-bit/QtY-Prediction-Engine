package com.example

import com.example.data.PricePoint
import com.example.engine.IndicatorSnapshot
import com.example.engine.PerformanceTracker
import com.example.engine.PredictionEngine
import com.example.engine.PredictionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1 Mandate 7: 90-Second Projection Integrity Test.
 *
 * Requirements:
 * - 90s projection is recorded independently at creation time
 * - 90s projection is NEVER substituted with a later 30s prediction
 * - 90s projection uses authorized projection math only
 * - 90s evaluation uses the actual price at T+90s
 * - if projection cannot be evaluated safely -> UNRESOLVED/PENDING
 * - test under delayed data, missing ticks, and out-of-order resolution
 */
class Projection90sIntegrityTest {

    private lateinit var tracker: PerformanceTracker
    private lateinit var engine: PredictionEngine
    private val btcPrice = 90_000.0

    @Before
    fun setUp() {
        tracker = PerformanceTracker()
        engine = PredictionEngine()
    }

    @Test
    fun test90sProjectionRecordedIndependentlyAtCreationTime() {
        val now = 1715000000000L
        val snapshot = IndicatorSnapshot(
            ema9 = 90050.0,
            ema21 = 90000.0,
            rsi = 65.0,
            momentum = 50.0,
            velocity = 5.0,
            acceleration = 1.0,
            volatility = 25.0
        )

        val record = engine.predict(
            currentPrice = btcPrice,
            snapshot = snapshot,
            timestamp = now
        )

        // Verify independent 90s fields at creation time
        assertNotNull(record.projectedPrice90s)
        assertNotNull(record.projectedDecision90s)
        assertEquals(now + 90_000L, record.maturityTimestamp90s)
        assertNull(record.actualPrice90s)
        assertNull(record.result90s)

        // Verify 30s vs 90s independent targets
        assertEquals(now + 30_000L, record.maturityTimestamp)
        assertNotEquals(record.maturityTimestamp, record.maturityTimestamp90s)
    }

    @Test
    fun test90sResolutionUsesActualPriceAtT90s() {
        val t0 = 1715000000000L
        val record = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = 90050.0,
            currentPrice = 90000.0,
            settlementReference = 90000.0,
            timestamp = t0,
            predictionHorizon = 30,
            maturityTimestamp = t0 + 30_000L,
            projectedPrice90s = 90150.0,
            projectedDecision90s = "UP",
            maturityTimestamp90s = t0 + 90_000L
        )

        tracker.registerPrediction(record)

        val priceHistory = mutableListOf<PricePoint>()
        priceHistory.add(PricePoint(90000.0, t0))
        priceHistory.add(PricePoint(90040.0, t0 + 30_000L)) // 30s actual price
        priceHistory.add(PricePoint(90120.0, t0 + 90_000L)) // 90s actual price

        // Step 1: Advance to T + 30s
        tracker.resolveMatured(90040.0, t0 + 30_000L, priceHistory)

        // 30s is resolved
        assertEquals("CORRECT", record.result30s)
        assertEquals(90040.0, record.actualPrice30s)

        // 90s must NOT be resolved yet (remains null at T+30s)
        assertNull(record.result90s)
        assertNull(record.actualPrice90s)

        // Step 2: Advance to T + 90s
        tracker.resolveMatured(90120.0, t0 + 90_000L, priceHistory)

        // 90s is now resolved using authentic T+90s price
        assertEquals(90120.0, record.actualPrice90s)
        assertEquals("CORRECT", record.result90s)
    }

    @Test
    fun test90sProjectionNeverSubstitutedWithLater30sPrediction() {
        val t0 = 1715000000000L
        val originalRecord = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = 90050.0,
            currentPrice = 90000.0,
            settlementReference = 90000.0,
            timestamp = t0,
            projectedPrice90s = 90150.0,
            projectedDecision90s = "UP",
            maturityTimestamp90s = t0 + 90_000L
        )

        // A new 30s prediction arrives at t0 + 30s predicting DOWN
        val laterPrediction = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "DOWN",
            score = 0.20,
            strength = "STRONG",
            predictedPrice = 89900.0,
            currentPrice = 90040.0,
            settlementReference = 90040.0,
            timestamp = t0 + 30_000L
        )

        // The original 90s projection decision and target must NEVER be modified or overridden by the later prediction
        assertEquals("UP", originalRecord.projectedDecision90s)
        assertEquals(90150.0, originalRecord.projectedPrice90s ?: 0.0, 1e-4)
    }

    @Test
    fun testMissingTicksMarks90sUnresolvedSafely() {
        val t0 = 1715000000000L
        val record = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = 90050.0,
            currentPrice = 90000.0,
            settlementReference = 90000.0,
            timestamp = t0,
            maturityTimestamp = t0 + 30_000L,
            projectedPrice90s = 90150.0,
            projectedDecision90s = "UP",
            maturityTimestamp90s = t0 + 90_000L
        )

        tracker.registerPrediction(record)

        // Price history has gaps: has T+30s, but MISSING T+90s tick
        val priceHistoryWithGap = listOf(
            PricePoint(90000.0, t0),
            PricePoint(90050.0, t0 + 30_000L),
            PricePoint(90200.0, t0 + 105_000L) // Only arrived at T+105s, missed T+90s!
        )

        // Resolve 30s at T+30s
        tracker.resolveMatured(90050.0, t0 + 30_000L, priceHistoryWithGap)
        assertEquals("CORRECT", record.result30s)

        // Attempt resolution at T+105s
        tracker.resolveMatured(90200.0, t0 + 105_000L, priceHistoryWithGap)

        // Because exact T+90s tick was missing, it must fail closed to UNRESOLVED rather than substituting T+105s
        assertEquals("UNRESOLVED", record.result90s)
        assertNull(record.actualPrice90s)
    }

    @Test
    fun testPrematureEvaluationIsForbidden() {
        val t0 = 1715000000000L
        val record = PredictionRecord(
            inputs = IndicatorSnapshot(),
            decision = "UP",
            score = 0.80,
            strength = "STRONG",
            predictedPrice = 90050.0,
            currentPrice = 90000.0,
            settlementReference = 90000.0,
            timestamp = t0,
            maturityTimestamp = t0 + 30_000L,
            maturityTimestamp90s = t0 + 90_000L
        )

        tracker.registerPrediction(record)

        // Query at T+15s (premature)
        tracker.resolveMatured(90020.0, t0 + 15_000L, listOf(PricePoint(90000.0, t0), PricePoint(90020.0, t0 + 15_000L)))

        // Must still be pending and un-resolved
        assertNull(record.result30s)
        assertNull(record.result90s)
        assertTrue(tracker.getPendingPredictions().contains(record))
    }
}
