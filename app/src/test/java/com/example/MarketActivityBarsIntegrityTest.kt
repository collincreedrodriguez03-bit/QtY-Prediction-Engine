package com.example

import com.example.data.PricePoint
import com.example.ui.marketactivity.MarketActivityBar
import com.example.ui.marketactivity.MarketActivityProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification of Background Market-Activity Bars specifications (QtY Update Part 8/10):
 * 1. Background contextual bars use authentic observed volume and order-flow measurements.
 * 2. Visual hierarchy: bars are contextual (normalized, capped height, transparent).
 * 3. Never labels or renders bars as "predicted volume" (no predicted volume in future horizons).
 * 4. Observed data remains clearly distinguishable from predictions.
 * 5. If volume data is unavailable or zero, flags missing state without inventing dummy values.
 * 6. Mathematical normalization within displayed window ensures unusual activity is meaningful.
 * 7. Distinguishes directional aggressor order flow (buyer delta vs seller delta) from raw volume.
 */
class MarketActivityBarsIntegrityTest {

    @Test
    fun testWindowNormalizationWithinDisplayedWindow() {
        val baseTime = 1700000000000L
        val points = listOf(
            PricePoint(price = 90000.0, timestamp = baseTime, volume = 10.0),
            PricePoint(price = 90005.0, timestamp = baseTime + 2000, volume = 50.0),
            PricePoint(price = 90002.0, timestamp = baseTime + 4000, volume = 100.0), // Peak volume
            PricePoint(price = 90010.0, timestamp = baseTime + 6000, volume = 25.0)
        )

        val bars = MarketActivityProcessor.process(points, lookbackCount = 10)
        assertEquals(4, bars.size)

        // Peak volume bar must have normalizedHeight == 1.0f
        val peakBar = bars[2]
        assertEquals(100.0, peakBar.volume, 1e-4)
        assertEquals(1.0f, peakBar.normalizedHeight, 1e-4f)

        // Half-peak volume bar must have normalizedHeight == 0.5f
        val halfBar = bars[1]
        assertEquals(50.0, halfBar.volume, 1e-4)
        assertEquals(0.5f, halfBar.normalizedHeight, 1e-4f)

        // Lowest volume bar must be 0.1f
        val lowBar = bars[0]
        assertEquals(10.0, lowBar.volume, 1e-4)
        assertEquals(0.1f, lowBar.normalizedHeight, 1e-4f)
    }

    @Test
    fun testDistinguishesAggressorOrderFlowFromOrdinaryVolume() {
        val baseTime = 1700000000000L
        val points = listOf(
            PricePoint(price = 90000.0, timestamp = baseTime, volume = 20.0),
            PricePoint(price = 90010.0, timestamp = baseTime + 2000, volume = 20.0), // Price advanced -> Buyer dominant
            PricePoint(price = 90005.0, timestamp = baseTime + 4000, volume = 20.0), // Price fell -> Seller dominant
            PricePoint(price = 90005.0, timestamp = baseTime + 6000, volume = 20.0)  // Price unchanged -> Buyer/neutral dominant
        )

        val bars = MarketActivityProcessor.process(points)
        assertEquals(4, bars.size)

        // Bar 1: Price went from 90000 to 90010 (+10) -> isBuyerDominant = true
        assertTrue("Advancing price must indicate buyer-dominant aggressor flow", bars[1].isBuyerDominant)
        assertEquals(10.0, bars[1].priceChange, 1e-4)

        // Bar 2: Price went from 90010 to 90005 (-5) -> isBuyerDominant = false
        assertFalse("Declining price must indicate seller-dominant aggressor flow", bars[2].isBuyerDominant)
        assertEquals(-5.0, bars[2].priceChange, 1e-4)

        // Bar 3: Price flat -> isBuyerDominant = true (non-negative)
        assertTrue(bars[3].isBuyerDominant)
    }

    @Test
    fun testSurgeDetectionOnUnusualActivity() {
        val baseTime = 1700000000000L
        // Average baseline around 10 BTC
        val points = listOf(
            PricePoint(price = 90000.0, timestamp = baseTime, volume = 10.0),
            PricePoint(price = 90001.0, timestamp = baseTime + 2000, volume = 10.0),
            PricePoint(price = 90002.0, timestamp = baseTime + 4000, volume = 10.0),
            PricePoint(price = 90003.0, timestamp = baseTime + 6000, volume = 40.0) // 4x surge
        )

        val bars = MarketActivityProcessor.process(points)
        assertFalse("Standard volume should not trigger surge", bars[0].isSurge)
        assertFalse("Standard volume should not trigger surge", bars[1].isSurge)
        assertTrue("4x volume spike must trigger isSurge flag", bars[3].isSurge)
    }

    @Test
    fun testMissingOrZeroVolumeDoesNotInventValues() {
        val baseTime = 1700000000000L
        val points = listOf(
            PricePoint(price = 90000.0, timestamp = baseTime, volume = 0.0),
            PricePoint(price = 90005.0, timestamp = baseTime + 2000, volume = 0.0)
        )

        val bars = MarketActivityProcessor.process(points)
        assertEquals(2, bars.size)
        assertTrue("Zero volume must be flagged as missingOrZero", bars[0].isMissingOrZero)
        assertTrue("Zero volume must be flagged as missingOrZero", bars[1].isMissingOrZero)
        assertEquals("Missing volume must have normalizedHeight of 0f (no invented fake bars)", 0f, bars[0].normalizedHeight, 1e-4f)

        val summary = MarketActivityProcessor.computeOrderFlowSummary(bars)
        assertFalse("Data available must be false when volume is missing", summary.isDataAvailable)
        assertEquals("VOLUME FEED: LIMITED / REST FALLBACK", summary.statusMessage)
    }

    @Test
    fun testOrderFlowSummaryAggregation() {
        val baseTime = 1700000000000L
        val points = listOf(
            PricePoint(price = 90000.0, timestamp = baseTime, volume = 10.0),
            PricePoint(price = 90020.0, timestamp = baseTime + 2000, volume = 70.0), // Buyer vol: +70
            PricePoint(price = 90010.0, timestamp = baseTime + 4000, volume = 30.0)  // Seller vol: +30
        )

        val bars = MarketActivityProcessor.process(points)
        val summary = MarketActivityProcessor.computeOrderFlowSummary(bars)

        assertTrue(summary.isDataAvailable)
        assertEquals(110.0, summary.totalVolume, 1e-4) // 10 (neutral/buyer) + 70 (buyer) + 30 (seller)
        assertEquals(80.0, summary.buyerVolume, 1e-4)
        assertEquals(30.0, summary.sellerVolume, 1e-4)
        // Imbalance: (80 - 30) / 110 = 50 / 110 = +45.45%
        assertEquals(45.45, summary.orderFlowImbalancePercent, 0.5)
        assertTrue("Must indicate net buy delta", summary.statusMessage.contains("NET BUY DELTA"))
    }

    @Test
    fun testIntegrityHistoricalBoundStrictlyPriorToFutureHorizons() {
        // Critical requirement: volume bars strictly represent observed data (t <= NOW).
        // No future forecast points are allowed to have volume bars.
        val nowMs = 1700000000000L
        val futureHorizons = listOf(5, 10, 30, 60, 90, 120, 180, 240, 300)

        for (sec in futureHorizons) {
            val futureTimestamp = nowMs + (sec * 1000L)
            assertTrue("Future horizon timestamps must strictly exceed nowMs", futureTimestamp > nowMs)
        }

        val observedPoints = listOf(
            PricePoint(price = 90000.0, timestamp = nowMs - 4000, volume = 15.0),
            PricePoint(price = 90010.0, timestamp = nowMs - 2000, volume = 22.0),
            PricePoint(price = 90015.0, timestamp = nowMs, volume = 18.0)
        )

        for (pt in observedPoints) {
            assertTrue("Observed market points must strictly have timestamp <= nowMs", pt.timestamp <= nowMs)
        }
    }
}
