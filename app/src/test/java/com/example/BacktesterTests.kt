package com.example

import com.example.engine.Backtester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktesterTests {

    @Test
    fun testBacktestReplayExecution() {
        val backtester = Backtester()
        val syntheticPrices = backtester.generateSyntheticHistoricalData(startPrice = 90000.0, count = 100)

        val result = backtester.runBacktest(syntheticPrices)

        assertNotNull(result)
        assertTrue("Total samples should match input size", result.totalSamples == 100)
        assertTrue("Total trades should be greater than 0", result.totalTrades > 0)
        assertTrue("Win rate should be bounded between 0 and 100", result.winRatePercent in 0.0..100.0)
        assertTrue("Sample predictions list should have items", result.samplePredictions.isNotEmpty())
    }
}
