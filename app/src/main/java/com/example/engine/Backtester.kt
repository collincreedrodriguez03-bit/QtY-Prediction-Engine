package com.example.engine

import com.example.data.PricePoint
import java.util.UUID

data class BacktestResult(
    val totalSamples: Int,
    // Statistical Non-Overlapping 15-Step / 30-Second Evaluation
    val statisticalTotalTrades: Int = 0,
    val statisticalCorrect: Int = 0,
    val statisticalIncorrect: Int = 0,
    val statisticalWinRatePercent: Double = 0.0,
    val statisticalUpCount: Int = 0,
    val statisticalDownCount: Int = 0,
    val statisticalNoTradeCount: Int = 0,
    val activeBaselineAlwaysUpWinRate: Double = 50.0,
    val activeBaselineAlwaysDownWinRate: Double = 50.0,
    val globalBaselineAlwaysUpWinRate: Double = 50.0,
    val globalBaselineAlwaysDownWinRate: Double = 50.0,

    // Continuous Operational Step-by-Step Replay
    val operationalTotalTrades: Int = 0,
    val operationalCorrect: Int = 0,
    val operationalIncorrect: Int = 0,
    val operationalWinRatePercent: Double = 0.0,

    // Backward-compatible fields for existing UI bindings
    val totalTrades: Int = statisticalTotalTrades,
    val upPredictions: Int = statisticalUpCount,
    val downPredictions: Int = statisticalDownCount,
    val noTrades: Int = statisticalNoTradeCount,
    val correctPredictions: Int = statisticalCorrect,
    val incorrectPredictions: Int = statisticalIncorrect,
    val winRatePercent: Double = statisticalWinRatePercent,
    val baselineAlwaysUpWinRate: Double = activeBaselineAlwaysUpWinRate,
    val baselineAlwaysDownWinRate: Double = activeBaselineAlwaysDownWinRate,
    val samplePredictions: List<PredictionRecord> = emptyList()
)

/**
 * Historical replay backtesting engine for Phase 1.
 * Chronologically feeds historical data through IndicatorCalculator and PredictionEngine.
 * Computes:
 * 1. Formal Statistical Evaluation using 15-step (30-second) non-overlapping evaluation windows.
 * 2. Operational Replay using continuous 1-step evaluation.
 */
class Backtester(
    private val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    private val predictionEngine: PredictionEngine = PredictionEngine(predictionHorizonSeconds = 30)
) {

    /**
     * Replays a list of sequential chronological PricePoints (at ~2s intervals)
     * and evaluates prediction accuracy against actual 30-second forward prices.
     */
    fun runBacktest(prices: List<PricePoint>): BacktestResult {
        if (prices.size < 40) {
            return BacktestResult(
                totalSamples = prices.size,
                statisticalTotalTrades = 0,
                statisticalCorrect = 0,
                statisticalIncorrect = 0,
                statisticalWinRatePercent = 0.0,
                statisticalUpCount = 0,
                statisticalDownCount = 0,
                statisticalNoTradeCount = 0,
                activeBaselineAlwaysUpWinRate = 50.0,
                activeBaselineAlwaysDownWinRate = 50.0,
                globalBaselineAlwaysUpWinRate = 50.0,
                globalBaselineAlwaysDownWinRate = 50.0,
                operationalTotalTrades = 0,
                operationalCorrect = 0,
                operationalIncorrect = 0,
                operationalWinRatePercent = 0.0,
                samplePredictions = emptyList()
            )
        }

        val rollingPoints = mutableListOf<PricePoint>()
        var previousVelocity = 0.0
        val horizonSteps = 15 // 15 steps * 2s = 30s horizon

        // Continuous Operational Stream Accumulators
        var opUpCount = 0
        var opDownCount = 0
        var opNoTradeCount = 0
        var opCorrectCount = 0
        var opIncorrectCount = 0

        // Statistical Non-Overlapping 15-step Stream Accumulators
        var statUpCount = 0
        var statDownCount = 0
        var statNoTradeCount = 0
        var statCorrectCount = 0
        var statIncorrectCount = 0

        var statActiveBaseUpWins = 0
        var statActiveBaseDownWins = 0
        var statActiveBaseTotal = 0

        var statGlobalBaseUpWins = 0
        var statGlobalBaseDownWins = 0
        var statGlobalBaseTotal = 0

        var lastStatisticalIndex = -horizonSteps // Ensure first eligible sample starts immediately after warmup

        val predictionList = mutableListOf<PredictionRecord>()

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
                referencePrice = null,
                previousVelocity = previousVelocity
            )
            previousVelocity = snapshot.velocity

            val prediction = predictionEngine.predict(
                currentPrice = point.price,
                snapshot = snapshot,
                timestamp = point.timestamp
            )

            // Look forward 30 seconds (15 steps) if available
            val futureIndex = i + horizonSteps
            if (futureIndex < prices.size) {
                val futurePrice = prices[futureIndex].price
                prediction.actualPrice = futurePrice

                val priceDelta = futurePrice - point.price

                // Operational Continuous Replay Evaluation (Every step)
                when (prediction.decision) {
                    "UP" -> {
                        opUpCount++
                        if (priceDelta > 0.0) {
                            prediction.result = "CORRECT"
                            opCorrectCount++
                        } else {
                            prediction.result = "INCORRECT"
                            opIncorrectCount++
                        }
                    }
                    "DOWN" -> {
                        opDownCount++
                        if (priceDelta < 0.0) {
                            prediction.result = "CORRECT"
                            opCorrectCount++
                        } else {
                            prediction.result = "INCORRECT"
                            opIncorrectCount++
                        }
                    }
                    else -> {
                        opNoTradeCount++
                        prediction.result = "NO-TRADE"
                    }
                }

                // Formal Statistical Non-Overlapping Evaluation (Strided by 15 steps)
                if (i - lastStatisticalIndex >= horizonSteps) {
                    lastStatisticalIndex = i
                    statGlobalBaseTotal++
                    if (priceDelta > 0.0) statGlobalBaseUpWins++
                    if (priceDelta < 0.0) statGlobalBaseDownWins++

                    when (prediction.decision) {
                        "UP" -> {
                            statUpCount++
                            statActiveBaseTotal++
                            if (priceDelta > 0.0) {
                                statCorrectCount++
                                statActiveBaseUpWins++
                            } else {
                                statIncorrectCount++
                                if (priceDelta < 0.0) statActiveBaseDownWins++
                            }
                        }
                        "DOWN" -> {
                            statDownCount++
                            statActiveBaseTotal++
                            if (priceDelta < 0.0) {
                                statCorrectCount++
                                statActiveBaseDownWins++
                            } else {
                                statIncorrectCount++
                                if (priceDelta > 0.0) statActiveBaseUpWins++
                            }
                        }
                        else -> {
                            statNoTradeCount++
                        }
                    }
                }
            }

            predictionList.add(prediction)
        }

        val opTotalTrades = opCorrectCount + opIncorrectCount
        val opWinRate = if (opTotalTrades > 0) (opCorrectCount.toDouble() / opTotalTrades) * 100.0 else 0.0

        val statTotalTrades = statCorrectCount + statIncorrectCount
        val statWinRate = if (statTotalTrades > 0) (statCorrectCount.toDouble() / statTotalTrades) * 100.0 else 0.0

        val activeBaseUpRate = if (statActiveBaseTotal > 0) (statActiveBaseUpWins.toDouble() / statActiveBaseTotal) * 100.0 else 50.0
        val activeBaseDownRate = if (statActiveBaseTotal > 0) (statActiveBaseDownWins.toDouble() / statActiveBaseTotal) * 100.0 else 50.0

        val globalBaseUpRate = if (statGlobalBaseTotal > 0) (statGlobalBaseUpWins.toDouble() / statGlobalBaseTotal) * 100.0 else 50.0
        val globalBaseDownRate = if (statGlobalBaseTotal > 0) (statGlobalBaseDownWins.toDouble() / statGlobalBaseTotal) * 100.0 else 50.0

        return BacktestResult(
            totalSamples = prices.size,
            statisticalTotalTrades = statTotalTrades,
            statisticalCorrect = statCorrectCount,
            statisticalIncorrect = statIncorrectCount,
            statisticalWinRatePercent = Math.round(statWinRate * 10.0) / 10.0,
            statisticalUpCount = statUpCount,
            statisticalDownCount = statDownCount,
            statisticalNoTradeCount = statNoTradeCount,
            activeBaselineAlwaysUpWinRate = Math.round(activeBaseUpRate * 10.0) / 10.0,
            activeBaselineAlwaysDownWinRate = Math.round(activeBaseDownRate * 10.0) / 10.0,
            globalBaselineAlwaysUpWinRate = Math.round(globalBaseUpRate * 10.0) / 10.0,
            globalBaselineAlwaysDownWinRate = Math.round(globalBaseDownRate * 10.0) / 10.0,
            operationalTotalTrades = opTotalTrades,
            operationalCorrect = opCorrectCount,
            operationalIncorrect = opIncorrectCount,
            operationalWinRatePercent = Math.round(opWinRate * 10.0) / 10.0,
            samplePredictions = predictionList.takeLast(10)
        )
    }

    /**
     * Generates a realistic high-precision price sequence FOR ISOLATED UNIT TESTING AND SIMULATION ONLY.
     * Not used in live engine prediction, validation, persistence, or live calibration paths.
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
