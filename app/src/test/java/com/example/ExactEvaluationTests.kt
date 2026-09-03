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
        currentPrice: Double = 90000.0,
        projectedDecision90s: String = "UP"
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
            maturityTimestamp90s = timestamp + 90_000L,
            projectedPrice90s = currentPrice + 120.0,
            projectedDecision90s = projectedDecision90s,
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
    fun testExact90sEvaluation() {
        val t = 100_000L
        val rec = createRecord(
            timestamp = t,
            decision = "UP",
            settlementRef = 90000.0,
            projectedDecision90s = "DOWN"
        )
        tracker.registerPrediction(rec)

        // Price history with points up to 200_000L
        val history = listOf(
            PricePoint(price = 90000.0, timestamp = 100_000L, exchange = "BINANCE"),
            PricePoint(price = 90050.0, timestamp = 130_000L, exchange = "BINANCE"), // T+30s
            PricePoint(price = 89920.0, timestamp = 190_000L, exchange = "BINANCE"), // authentic T+90s
            PricePoint(price = 91000.0, timestamp = 192_000L, exchange = "BINANCE")  // later T+92s
        )

        // Cycle at T+30s resolves 30s horizon
        val resolved30s = tracker.resolveMatured(
            currentPrice = 90050.0,
            currentTimestamp = 130_000L,
            priceHistory = history
        )
        assertEquals(1, resolved30s.size)
        assertEquals("CORRECT", resolved30s[0].result30s)
        assertNull("90s should not be resolved yet at T+30s", resolved30s[0].result90s)

        // Cycle at T+92s resolves 90s horizon
        val resolved90s = tracker.resolveMatured(
            currentPrice = 91000.0,
            currentTimestamp = 192_000L,
            priceHistory = history
        )
        assertEquals(1, resolved90s.size)
        // MUST use authentic price at exact T+90s (89920.0 < 90000.0 -> DOWN is CORRECT)
        assertEquals(89920.0, resolved90s[0].actualPrice90s)
        assertEquals("CORRECT", resolved90s[0].result90s)
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
    fun testMissing90sObservationMarksUnresolved() {
        val t = 100_000L
        val rec = createRecord(timestamp = t, decision = "UP", settlementRef = 90000.0)
        tracker.registerPrediction(rec)

        // History contains 30s observation, but exact 90s observation (190_000L) is missing
        val history = listOf(
            PricePoint(price = 90000.0, timestamp = 100_000L, exchange = "BINANCE"),
            PricePoint(price = 90050.0, timestamp = 130_000L, exchange = "BINANCE"), // 30s present
            PricePoint(price = 90060.0, timestamp = 188_000L, exchange = "BINANCE"),
            // 190_000L missing!
            PricePoint(price = 90100.0, timestamp = 194_000L, exchange = "BINANCE")
        )

        // Resolve 30s at 130_000L
        tracker.resolveMatured(90050.0, 130_000L, history)

        // Evaluate at 194_000L
        val resolved90s = tracker.resolveMatured(90100.0, 194_000L, history)
        assertEquals(1, resolved90s.size)
        // 90s MUST be marked UNRESOLVED
        assertEquals("UNRESOLVED", resolved90s[0].result90s)
        assertNull("actualPrice90s must be null when UNRESOLVED", resolved90s[0].actualPrice90s)
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
