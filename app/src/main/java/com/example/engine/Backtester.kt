package com.example.engine

import com.example.data.PricePoint
import java.util.UUID

data class BacktestResult(
    val totalSamples: Int,
    // Statistical Non-Overlapping 15-Step / 30-Second Evaluation
    val statisticalTotalTrades: Int = 0,
    val statisticalCorrect: Int = 0,
    val statisticalIncorrect: Int = 0,
    val statisticalTies: Int = 0,
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
    val operationalTies: Int = 0,
    val operationalWinRatePercent: Double = 0.0,

    // Backward-compatible fields for existing UI bindings
    val totalTrades: Int = statisticalTotalTrades,
    val upPredictions: Int = statisticalUpCount,
    val downPredictions: Int = statisticalDownCount,
    val noTrades: Int = statisticalNoTradeCount,
    val correctPredictions: Int = statisticalCorrect,
    val incorrectPredictions: Int = statisticalIncorrect,
    val ties: Int = statisticalTies,
    val winRatePercent: Double = statisticalWinRatePercent,
    val baselineAlwaysUpWinRate: Double = activeBaselineAlwaysUpWinRate,
    val baselineAlwaysDownWinRate: Double = activeBaselineAlwaysDownWinRate,
    val samplePredictions: List<PredictionRecord> = emptyList(),
    val horizonStats: Map<Int, HorizonPerformanceStats> = emptyMap()
)

/**
 * Historical replay backtesting engine for Phase 1.
 * Chronologically feeds authentic historical data through IndicatorCalculator and PredictionEngine.
 * Computes:
 * 1. Formal Statistical Evaluation using 15-step (30-second) non-overlapping evaluation windows.
 * 2. Operational Replay using continuous 1-step evaluation.
 * 3. Independent multi-horizon resolution across all supported timeframes without lookahead bias.
 */
class Backtester(
    private val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    private val predictionEngine: PredictionEngine = PredictionEngine(predictionHorizonSeconds = 30)
) {

    /**
     * Replays a list of sequential chronological PricePoints (at ~2s intervals or 1m candles)
     * and evaluates prediction accuracy against actual forward prices.
     * Enforces zero future lookahead: settlement reference and indicator state only observe
     * data up to the current timestamp.
     */
    fun runBacktest(prices: List<PricePoint>): BacktestResult {
        // 1. Authenticate data and filter non-positive/malformed points
        val validPrices = prices.filter {
            it.price > 0.0 && !it.price.isNaN() && !it.price.isInfinite() && it.timestamp > 0L
        }.sortedBy { it.timestamp }

        if (validPrices.size < 40) {
            return BacktestResult(
                totalSamples = validPrices.size,
                statisticalTotalTrades = 0,
                statisticalCorrect = 0,
                statisticalIncorrect = 0,
                statisticalTies = 0,
                statisticalWinRatePercent = 0.0,
                statisticalUpCount = 0,
                statisticalDownCount = 0,
                statisticalNoTradeCount = 0,
                activeBaselineAlwaysUpWinRate = 0.0,
                activeBaselineAlwaysDownWinRate = 0.0,
                globalBaselineAlwaysUpWinRate = 0.0,
                globalBaselineAlwaysDownWinRate = 0.0,
                operationalTotalTrades = 0,
                operationalCorrect = 0,
                operationalIncorrect = 0,
                operationalTies = 0,
                operationalWinRatePercent = 0.0,
                samplePredictions = emptyList(),
                horizonStats = emptyMap()
            )
        }

        val rollingPoints = mutableListOf<PricePoint>()
        var previousVelocity = 0.0

        // Continuous Operational Stream Accumulators
        var opUpCount = 0
        var opDownCount = 0
        var opNoTradeCount = 0
        var opCorrectCount = 0
        var opIncorrectCount = 0
        var opTieCount = 0

        // Statistical Non-Overlapping Stream Accumulators (spaced >= 30,000ms)
        var statUpCount = 0
        var statDownCount = 0
        var statNoTradeCount = 0
        var statCorrectCount = 0
        var statIncorrectCount = 0
        var statTieCount = 0

        var statActiveBaseUpWins = 0
        var statActiveBaseDownWins = 0
        var statActiveBaseTotal = 0

        var statGlobalBaseUpWins = 0
        var statGlobalBaseDownWins = 0
        var statGlobalBaseTotal = 0

        var lastStatisticalTimestamp = 0L

        val predictionList = mutableListOf<PredictionRecord>()

        // Horizon-specific evaluation accumulators: horizonSeconds -> HorizonAccumulator
        val horizonAccumulators = PredictionHorizon.ALL_HORIZONS.associate { h ->
            h.seconds to HorizonAccumulator(h.seconds)
        }.toMutableMap()

        for (i in validPrices.indices) {
            val point = validPrices[i]
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

            // Strict zero lookahead: settlement reference derives strictly from past rolling observations
            val windowMs = 15 * 60 * 1000L
            val intervalStart = point.timestamp - (point.timestamp % windowMs)
            val settlementRef = rollingPoints.minByOrNull { kotlin.math.abs(it.timestamp - intervalStart) }?.price ?: point.price

            val prediction = predictionEngine.predict(
                currentPrice = point.price,
                snapshot = snapshot,
                timestamp = point.timestamp,
                settlementReference = settlementRef
            )

            // Look forward 30 seconds via timestamp lookup for primary baseline evaluation
            val point30s = findObservationAtOrAfter(point.timestamp + 30_000L, validPrices)
            if (point30s != null) {
                val futurePrice = point30s.price
                prediction.actualPrice = futurePrice
                prediction.actualPrice30s = futurePrice

                val contractDelta = futurePrice - prediction.settlementReference

                // Operational Continuous Replay Evaluation (Every step)
                when (prediction.decision) {
                    "UP" -> {
                        opUpCount++
                        when {
                            contractDelta > 0.0 -> {
                                prediction.result = "CORRECT"
                                prediction.result30s = "CORRECT"
                                opCorrectCount++
                            }
                            contractDelta < 0.0 -> {
                                prediction.result = "INCORRECT"
                                prediction.result30s = "INCORRECT"
                                opIncorrectCount++
                            }
                            else -> {
                                prediction.result = "TIE"
                                prediction.result30s = "TIE"
                                opTieCount++
                            }
                        }
                    }
                    "DOWN" -> {
                        opDownCount++
                        when {
                            contractDelta < 0.0 -> {
                                prediction.result = "CORRECT"
                                prediction.result30s = "CORRECT"
                                opCorrectCount++
                            }
                            contractDelta > 0.0 -> {
                                prediction.result = "INCORRECT"
                                prediction.result30s = "INCORRECT"
                                opIncorrectCount++
                            }
                            else -> {
                                prediction.result = "TIE"
                                prediction.result30s = "TIE"
                                opTieCount++
                            }
                        }
                    }
                    else -> {
                        opNoTradeCount++
                        prediction.result = "NO-TRADE"
                        prediction.result30s = "NO-TRADE"
                    }
                }

                // Formal Statistical Non-Overlapping Evaluation (Spaced >= 30,000ms)
                if (point.timestamp - lastStatisticalTimestamp >= 30_000L) {
                    lastStatisticalTimestamp = point.timestamp
                    statGlobalBaseTotal++
                    if (contractDelta > 0.0) statGlobalBaseUpWins++
                    if (contractDelta < 0.0) statGlobalBaseDownWins++

                    when (prediction.decision) {
                        "UP" -> {
                            statUpCount++
                            statActiveBaseTotal++
                            when {
                                contractDelta > 0.0 -> {
                                    statCorrectCount++
                                    statActiveBaseUpWins++
                                }
                                contractDelta < 0.0 -> {
                                    statIncorrectCount++
                                    statActiveBaseDownWins++
                                }
                                else -> {
                                    statTieCount++
                                }
                            }
                        }
                        "DOWN" -> {
                            statDownCount++
                            statActiveBaseTotal++
                            when {
                                contractDelta < 0.0 -> {
                                    statCorrectCount++
                                    statActiveBaseDownWins++
                                }
                                contractDelta > 0.0 -> {
                                    statIncorrectCount++
                                    statActiveBaseUpWins++
                                }
                                else -> {
                                    statTieCount++
                                }
                            }
                        }
                        else -> {
                            statNoTradeCount++
                        }
                    }
                }
            } else {
                prediction.result = "UNRESOLVED"
                prediction.result30s = "UNRESOLVED"
            }

            // Resolve each canonical horizon independently via timestamp lookup
            for (forecast in prediction.horizonForecasts) {
                val hSec = forecast.horizonSeconds
                val targetHTime = forecast.maturityTimestamp
                val accum = horizonAccumulators[hSec]
                if (accum != null) {
                    accum.totalForecasts++
                    val hPoint = findObservationAtOrAfter(targetHTime, validPrices)
                    if (hPoint != null) {
                        val hFuturePrice = hPoint.price
                        forecast.actualPrice = hFuturePrice
                        forecast.actualReturn = if (point.price > 0.0) kotlin.math.ln(hFuturePrice / point.price) else 0.0
                        forecast.resolvedTimestamp = hPoint.timestamp
                        val hDelta = hFuturePrice - forecast.settlementReference
                        val hResult = when (forecast.decision) {
                            "UP" -> when {
                                hDelta > 0.0 -> "CORRECT"
                                hDelta < 0.0 -> "INCORRECT"
                                else -> "TIE"
                            }
                            "DOWN" -> when {
                                hDelta < 0.0 -> "CORRECT"
                                hDelta > 0.0 -> "INCORRECT"
                                else -> "TIE"
                            }
                            else -> "NO-TRADE"
                        }
                        forecast.result = hResult
                        accum.resolvedCount++
                        when (hResult) {
                            "CORRECT" -> accum.correctCount++
                            "INCORRECT" -> accum.incorrectCount++
                            "TIE" -> accum.tieCount++
                            else -> {}
                        }
                    } else {
                        forecast.result = "UNRESOLVED"
                        accum.unresolvedCount++
                    }
                }
            }

            predictionList.add(prediction)
        }

        val opTotalTrades = opCorrectCount + opIncorrectCount
        val opWinRate = if (opTotalTrades > 0) (opCorrectCount.toDouble() / opTotalTrades) * 100.0 else 0.0

        val statTotalTrades = statCorrectCount + statIncorrectCount
        val statWinRate = if (statTotalTrades > 0) (statCorrectCount.toDouble() / statTotalTrades) * 100.0 else 0.0

        val activeBaseUpRate = if (statActiveBaseTotal > 0) (statActiveBaseUpWins.toDouble() / statActiveBaseTotal) * 100.0 else 0.0
        val activeBaseDownRate = if (statActiveBaseTotal > 0) (statActiveBaseDownWins.toDouble() / statActiveBaseTotal) * 100.0 else 0.0

        val globalBaseUpRate = if (statGlobalBaseTotal > 0) (statGlobalBaseUpWins.toDouble() / statGlobalBaseTotal) * 100.0 else 0.0
        val globalBaseDownRate = if (statGlobalBaseTotal > 0) (statGlobalBaseDownWins.toDouble() / statGlobalBaseTotal) * 100.0 else 0.0

        val computedHorizonStats = horizonAccumulators.mapValues { (_, accum) ->
            val decisive = accum.correctCount + accum.incorrectCount
            val winRate = if (decisive > 0) {
                Math.round((accum.correctCount.toDouble() / decisive) * 1000.0) / 10.0
            } else 0.0
            HorizonPerformanceStats(
                horizonSeconds = accum.horizonSeconds,
                totalForecasts = accum.totalForecasts,
                resolvedCount = accum.resolvedCount,
                correctCount = accum.correctCount,
                incorrectCount = accum.incorrectCount,
                unresolvedCount = accum.unresolvedCount,
                winRate = winRate
            )
        }

        return BacktestResult(
            totalSamples = validPrices.size,
            statisticalTotalTrades = statTotalTrades,
            statisticalCorrect = statCorrectCount,
            statisticalIncorrect = statIncorrectCount,
            statisticalTies = statTieCount,
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
            operationalTies = opTieCount,
            operationalWinRatePercent = Math.round(opWinRate * 10.0) / 10.0,
            samplePredictions = predictionList.takeLast(15),
            horizonStats = computedHorizonStats
        )
    }

    private fun findObservationAtOrAfter(
        targetTime: Long,
        sortedPrices: List<PricePoint>,
        maxDelayMs: Long = 3000L
    ): PricePoint? {
        val idx = sortedPrices.binarySearchBy(targetTime) { it.timestamp }
        if (idx >= 0) return sortedPrices[idx]
        val insertion = -idx - 1
        if (insertion < sortedPrices.size) {
            val candidate = sortedPrices[insertion]
            if (candidate.timestamp >= targetTime && candidate.timestamp - targetTime <= maxDelayMs) {
                return candidate
            }
        }
        return null
    }

    private class HorizonAccumulator(val horizonSeconds: Int) {
        var totalForecasts: Int = 0
        var resolvedCount: Int = 0
        var correctCount: Int = 0
        var incorrectCount: Int = 0
        var tieCount: Int = 0
        var unresolvedCount: Int = 0
    }
}

