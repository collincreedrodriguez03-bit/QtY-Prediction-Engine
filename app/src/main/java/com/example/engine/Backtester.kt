package com.example.engine

import com.example.data.PricePoint
import java.util.UUID

data class BacktestResult(
    val totalSamples: Int,
    val totalTrades: Int,
    val upPredictions: Int,
    val downPredictions: Int,
    val noTrades: Int,
    val correctPredictions: Int,
    val incorrectPredictions: Int,
    val winRatePercent: Double,
    val samplePredictions: List<PredictionRecord>
)

/**
 * Historical replay backtesting engine for Phase 1.
 * Chronologically feeds historical data through IndicatorCalculator and PredictionEngine
 * at 2-second simulated steps, and measures directional accuracy over a 30-second horizon (15 steps).
 */
class Backtester(
    private val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    private val predictionEngine: PredictionEngine = PredictionEngine(predictionHorizonSeconds = 60)
) {

    /**
     * Replays a list of sequential chronological PricePoints (e.g. at ~2s intervals)
     * and evaluates prediction accuracy against actual 60-second forward prices.
     */
    fun runBacktest(prices: List<PricePoint>): BacktestResult {
        if (prices.size < 40) {
            return BacktestResult(
                totalSamples = prices.size,
                totalTrades = 0,
                upPredictions = 0,
                downPredictions = 0,
                noTrades = 0,
                correctPredictions = 0,
                incorrectPredictions = 0,
                winRatePercent = 0.0,
                samplePredictions = emptyList()
            )
        }

        val rollingPoints = mutableListOf<PricePoint>()
        var previousVelocity = 0.0
        val refPrice = prices.first().price

        val predictionList = mutableListOf<PredictionRecord>()
        var upCount = 0
        var downCount = 0
        var noTradeCount = 0
        var correctCount = 0
        var incorrectCount = 0

        val horizonSteps = 30 // 30 steps * 2s = 60s horizon

        for (i in prices.indices) {
            val point = prices[i]
            rollingPoints.add(point)
            if (rollingPoints.size > 300) {
                rollingPoints.removeAt(0)
            }

            // Warm up indicators with at least 15 points
            if (rollingPoints.size < 15) continue

            val snapshot = indicatorCalculator.computeSnapshot(
                points = rollingPoints,
                referencePrice = refPrice,
                previousVelocity = previousVelocity
            )
            previousVelocity = snapshot.velocity

            val prediction = predictionEngine.predict(
                currentPrice = point.price,
                snapshot = snapshot,
                timestamp = point.timestamp
            )

            // Look forward 60 seconds (horizonSteps) if available
            val futureIndex = i + horizonSteps
            if (futureIndex < prices.size) {
                val futurePrice = prices[futureIndex].price
                prediction.actualPrice = futurePrice

                when (prediction.decision) {
                    "UP" -> {
                        upCount++
                        if (futurePrice > point.price) {
                            prediction.result = "CORRECT"
                            correctCount++
                        } else {
                            prediction.result = "INCORRECT"
                            incorrectCount++
                        }
                    }
                    "DOWN" -> {
                        downCount++
                        if (futurePrice < point.price) {
                            prediction.result = "CORRECT"
                            correctCount++
                        } else {
                            prediction.result = "INCORRECT"
                            incorrectCount++
                        }
                    }
                    else -> {
                        noTradeCount++
                        prediction.result = "NO-TRADE"
                    }
                }
            }

            predictionList.add(prediction)
        }

        val totalTrades = correctCount + incorrectCount
        val winRate = if (totalTrades > 0) (correctCount.toDouble() / totalTrades) * 100.0 else 0.0

        return BacktestResult(
            totalSamples = prices.size,
            totalTrades = totalTrades,
            upPredictions = upCount,
            downPredictions = downCount,
            noTrades = noTradeCount,
            correctPredictions = correctCount,
            incorrectPredictions = incorrectCount,
            winRatePercent = Math.round(winRate * 10.0) / 10.0,
            samplePredictions = predictionList.takeLast(10)
        )
    }

    /**
     * Generates a realistic high-precision price sequence for testing algorithms and backtesting.
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

        // Create a trend sequence with realistic micro-volatility
        for (i in 0 until count) {
            val trendFactor = kotlin.math.sin(i / 15.0) * 8.0 + (kotlin.math.cos(i / 5.0) * 4.0)
            price += trendFactor + (Math.random() - 0.48) * 12.0
            time += intervalMs
            list.add(
                PricePoint(
                    price = Math.round(price * 100.0) / 100.0,
                    timestamp = time,
                    exchange = "BINANCE",
                    volume = 0.5 + Math.random() * 2.0,
                    bidPrice = price - 0.25,
                    askPrice = price + 0.25
                )
            )
        }
        return list
    }
}
