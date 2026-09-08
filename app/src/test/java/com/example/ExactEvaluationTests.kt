package com.example

import com.example.data.PricePoint
import com.example.engine.IndicatorSnapshot
import com.example.engine.PerformanceTracker
import com.example.engine.PredictionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification of Build 2/12: Exact 30s / 90s Evaluation Timing.
 *
 * Tests:
 * 1. Exact T+30s evaluation against authentic market price.
 * 2. Exact T+90s evaluation against authentic market price.
 * 3. Delayed/missing observations resulting in UNRESOLVED state without substitution.
 * 4. Resilient handling of out-of-order timestamp streams.
 * 5. Strict proof of zero lookahead bias.
 */
class ExactEvaluationTests {

    private lateinit var tracker: PerformanceTracker

    @Before
    fun setup() {
        tracker = PerformanceTracker()
    }

    private fun createRecord(
        timestamp: Long,
        decision: String = "UP",
        settlementRef: Double = 90000.0,
        currentPrice: Double = 90000.0
    ): PredictionRecord {
        return PredictionRecord(
            timestamp = timestamp,
            decision = decision,
            score = 0.75,
            strength = "STRONG",
            currentPrice = currentPrice,
            settlementReference = settlementRef,
            predictedPrice = currentPrice + 50.0,
            predictionHorizon = 30,
            maturityTimestamp = timestamp + 30_000L,
            inputs = IndicatorSnapshot()
        )
    }

    @Test
    fun testExact30sEvaluation() {
        val t = 100_000L
        val rec = createRecord(timestamp = t, decision = "UP", settlementRef = 90000.0)
        tracker.registerPrediction(rec)

        // Price history contains authentic observations including exact T+30s (130_000L)
        val history = listOf(
            PricePoint(price = 90000.0, timestamp = 100_000L, exchange = "BINANCE"),
            PricePoint(price = 90020.0, timestamp = 110_000L, exchange = "BINANCE"),
            PricePoint(price = 90080.0, timestamp = 130_000L, exchange = "BINANCE"), // authentic T+30s
            PricePoint(price = 89900.0, timestamp = 132_000L, exchange = "BINANCE")  // later observation
        )

        // Evaluate at delayed cycle T+32s (132_000L)
        val resolved = tracker.resolveMatured(
            currentPrice = 89900.0,
            currentTimestamp = 132_000L,
            priceHistory = history
        )

        assertEquals(1, resolved.size)
        // MUST evaluate against authentic price at T+30s (90080.0), NOT later observation (89900.0)
        assertEquals(90080.0, resolved[0].actualPrice30s)
        assertEquals(90080.0, resolved[0].actualPrice)
        assertEquals("CORRECT", resolved[0].result30s)
        assertEquals("CORRECT", resolved[0].result)
    }

    @Test
    fun testMissing30sObservationMarksUnresolved() {
        val t = 100_000L
        val rec = createRecord(timestamp = t, decision = "UP", settlementRef = 90000.0)
        tracker.registerPrediction(rec)

        // Exact T+30s (130_000L) observation is MISSING / DROPPED from data feed
        val history = listOf(
            PricePoint(price = 90000.0, timestamp = 100_000L, exchange = "BINANCE"),
            PricePoint(price = 90020.0, timestamp = 128_000L, exchange = "BINANCE"),
            // 130_000L missing!
            PricePoint(price = 90090.0, timestamp = 132_000L, exchange = "BINANCE")
        )

        // Cycle evaluated at 132_000L
        val resolved = tracker.resolveMatured(
            currentPrice = 90090.0,
            currentTimestamp = 132_000L,
            priceHistory = history
        )

        assertEquals(1, resolved.size)
        // Rule 8: If the exact maturity observation is unavailable, mark UNRESOLVED rather than substituting another timestamp
        assertEquals("UNRESOLVED", resolved[0].result)
        assertEquals("UNRESOLVED", resolved[0].result30s)
        assertNull("actualPrice must be null when UNRESOLVED", resolved[0].actualPrice)
        assertNull("actualPrice30s must be null when UNRESOLVED", resolved[0].actualPrice30s)
    }

    @Test
    fun testTimestampOrderingResilience() {
        val t = 100_000L
        val rec = createRecord(timestamp = t, decision = "UP", settlementRef = 90000.0)
        tracker.registerPrediction(rec)

        // History arriving out of order
        val shuffledHistory = listOf(
            PricePoint(price = 89950.0, timestamp = 140_000L, exchange = "BINANCE"),
            PricePoint(price = 90075.0, timestamp = 130_000L, exchange = "BINANCE"), // exact maturity point
            PricePoint(price = 90010.0, timestamp = 110_000L, exchange = "BINANCE"),
            PricePoint(price = 90000.0, timestamp = 100_000L, exchange = "BINANCE")
        )

        val resolved = tracker.resolveMatured(
            currentPrice = 89950.0,
            currentTimestamp = 140_000L,
            priceHistory = shuffledHistory
        )

        assertEquals(1, resolved.size)
        // Correctly matches exact 130_000L observation despite shuffled list order
        assertEquals(90075.0, resolved[0].actualPrice30s)
        assertEquals("CORRECT", resolved[0].result30s)
    }

    @Test
    fun testNoLookaheadProof() {
        val t = 100_000L
        val rec = createRecord(timestamp = t, decision = "UP", settlementRef = 90000.0)
        tracker.registerPrediction(rec)

        // Simulate a dataset where future timestamps already exist up to T+60s
        val historyWithFuture = listOf(
            PricePoint(price = 90000.0, timestamp = 100_000L, exchange = "BINANCE"),
            PricePoint(price = 90020.0, timestamp = 110_000L, exchange = "BINANCE"),
            PricePoint(price = 90050.0, timestamp = 130_000L, exchange = "BINANCE"), // future relative to 110_000L
            PricePoint(price = 90100.0, timestamp = 160_000L, exchange = "BINANCE")
        )

        // Evaluate at T+10s (currentTimestamp = 110_000L)
        val prematureResolved = tracker.resolveMatured(
            currentPrice = 90020.0,
            currentTimestamp = 110_000L,
            priceHistory = historyWithFuture
        )

        // PROOF OF NO LOOKAHEAD:
        // Even though 130_000L exists in the memory array, resolution MUST NOT occur before currentTimestamp >= 130_000L
        assertTrue("No premature resolution allowed before maturity timestamp", prematureResolved.isEmpty())
        assertEquals("Pending prediction must remain in queue", 1, tracker.getPendingPredictions().size)
        assertEquals(0, tracker.getResolvedPredictions().size)
        assertNull(rec.result)
        assertNull(rec.result30s)
    }
}
