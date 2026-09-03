package com.example.testutil

import com.example.data.PricePoint

/**
 * Test-Only Synthetic Market Data Generator.
 *
 * Isolated exclusively within `src/test` to guarantee that synthetic / random / simulated data
 * cannot be referenced, invoked, or blended into production prediction, backtesting, evaluation,
 * calibration, performance metrics, or trading execution paths.
 */
object TestSyntheticDataGenerator {

    /**
     * Generates a realistic high-precision price sequence FOR ISOLATED UNIT TESTING AND BENCHMARKING ONLY.
     */
    fun generateSyntheticHistoricalData(
        startPrice: Double = 90000.0,
        count: Int = 200,
        intervalMs: Long = 2000L,
        baseTimestamp: Long = System.currentTimeMillis() - (200 * 2000L)
    ): List<PricePoint> {
        val list = mutableListOf<PricePoint>()
        var price = startPrice
        var time = baseTimestamp

        for (i in 0 until count) {
            val trendFactor = kotlin.math.sin(i / 15.0) * 8.0 + (kotlin.math.cos(i / 5.0) * 4.0)
            price += trendFactor + (Math.random() - 0.48) * 12.0
            time += intervalMs
            list.add(
                PricePoint(
                    price = Math.round(price * 100.0) / 100.0,
                    timestamp = time,
                    exchange = "SIMULATED_TEST",
                    volume = 0.5 + Math.random() * 2.0,
                    bidPrice = price - 0.25,
                    askPrice = price + 0.25
                )
            )
        }
        return list
    }
}
