package com.example

import com.example.data.PricePoint
import com.example.engine.EngineLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end unit tests for EngineLoop 7-Step Cycle and Closed-Loop Feedback.
 */
class ClosedLoopEngineTests {

    private lateinit var loop: EngineLoop

    @Before
    fun setup() {
        loop = EngineLoop()
    }

    @Test
    fun testEngineLoopWarmupAndPrediction() {
        var baseTime = 1700000000000L
        var price = 90000.0

        // Inject 20 price points at 2-second intervals
        for (i in 1..20) {
            price += (if (i % 2 == 0) 10.0 else -5.0)
            baseTime += 2000L
            val pred = loop.processPricePoint(
                PricePoint(
                    price = price,
                    timestamp = baseTime,
                    exchange = "BINANCE",
                    volume = 1.0,
                    bidPrice = price - 0.5,
                    askPrice = price + 0.5
                )
            )
            assertNotNull(pred)
            assertTrue(pred.score in 0.0..1.0)
            assertTrue(pred.inputs.formulaDisplay.startsWith("S(t) ="))
        }

        assertEquals(20, loop.priceHistory.size())
    }

    @Test
    fun testLiveMaturityResolutionInEngineLoop() {
        var time = 1700000000000L
        var price = 90000.0

        // Step 1 to 45 (0s to 90s)
        for (i in 1..45) {
            price += 15.0 // Continuous rising trend
            time += 2000L
            loop.processPricePoint(
                PricePoint(
                    price = price,
                    timestamp = time,
                    exchange = "BINANCE",
                    volume = 1.0,
                    bidPrice = price - 0.5,
                    askPrice = price + 0.5
                )
            )
        }

        val resolved = loop.performanceTracker.getResolvedPredictions()
        // Since horizon is 60s (30 steps) and we ran 45 steps (90s), at least 15 predictions should have matured
        assertTrue("Expected matured predictions to resolve after 60s", resolved.isNotEmpty())
        for (rec in resolved) {
            assertNotNull(rec.actualPrice)
            assertNotNull(rec.result)
        }

        val stats = loop.performanceTracker.computeStats(null)
        assertTrue(stats.totalResolved > 0)
    }
}
