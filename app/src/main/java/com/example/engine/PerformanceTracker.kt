package com.example.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Performance metrics and factor attribution analysis for QtY Scalping Engine.
 */
data class FactorAttribution(
    val factorName: String,
    val totalTimesActive: Int = 0,
    val correctTimesActive: Int = 0,
    val winRate: Double = 0.0,
    val suggestedWeightOffset: Double = 0.0
)

data class LivePerformanceStats(
    val totalPredictions: Int = 0,
    val totalResolved: Int = 0,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0,
    val winRatePercent: Double = 0.0,
    val upWinRatePercent: Double = 0.0,
    val downWinRatePercent: Double = 0.0,
    val totalUpTrades: Int = 0,
    val totalDownTrades: Int = 0,
    val averageDeltaDollars: Double = 0.0,
    val marketRegime: String = "RANGING",
    val factorAttributions: List<FactorAttribution> = emptyList(),
    val learningBiasAdjustment: Double = 0.0,
    val lastResolvedTime: Long = 0L,
    val pendingCount: Int = 0
)

/**
 * Phase 4 & 5 Performance Tracking & Closed-Loop Learning Engine.
 *
 * Evaluates point-in-time predictions against actual market prices when maturity (30s) is reached.
 * Analyzes factor attribution and provides empirical, zero-lookahead calibration adjustments.
 */
class PerformanceTracker {
    private val pendingPredictions = mutableListOf<PredictionRecord>()
    private val resolvedPredictions = mutableListOf<PredictionRecord>()

    @Synchronized
    fun loadFromHistory(history: List<PredictionRecord>) {
        val now = System.currentTimeMillis()
        for (rec in history) {
            if (rec.result != null && rec.result != "PENDING") {
                if (resolvedPredictions.none { it.predictionId == rec.predictionId }) {
                    resolvedPredictions.add(rec)
                }
            } else if (rec.maturityTimestamp > now) {
                if (pendingPredictions.none { it.predictionId == rec.predictionId }) {
                    pendingPredictions.add(rec)
                }
            }
        }
    }

    @Synchronized
    fun registerPrediction(record: PredictionRecord) {
        pendingPredictions.add(record)
    }

    /**
     * Resolves all pending predictions that have reached or passed their maturity timestamp.
     * Returns the list of newly resolved predictions in this cycle.
     */
    @Synchronized
    fun resolveMatured(currentPrice: Double, currentTimestamp: Long): List<PredictionRecord> {
        val newlyResolved = mutableListOf<PredictionRecord>()
        val iterator = pendingPredictions.iterator()

        while (iterator.hasNext()) {
            val record = iterator.next()
            if (currentTimestamp >= record.maturityTimestamp) {
                record.actualPrice = currentPrice
                val priceDiff = currentPrice - record.currentPrice

                record.result = when (record.decision) {
                    "UP" -> if (priceDiff > 0.0) "CORRECT" else "INCORRECT"
                    "DOWN" -> if (priceDiff < 0.0) "CORRECT" else "INCORRECT"
                    else -> "NEUTRAL"
                }

                resolvedPredictions.add(record)
                newlyResolved.add(record)
                iterator.remove()
            }
        }

        return newlyResolved
    }

    @Synchronized
    fun getResolvedPredictions(): List<PredictionRecord> {
        return resolvedPredictions.toList()
    }

    @Synchronized
    fun getPendingPredictions(): List<PredictionRecord> {
        return pendingPredictions.toList()
    }

    /**
     * Computes verified, real-time performance statistics from authentic resolved predictions.
     * Never fabricates numbers — returns 0.0% if no resolved trades exist yet.
     */
    @Synchronized
    fun computeStats(currentSnapshot: IndicatorSnapshot?): LivePerformanceStats {
        val validTrades = resolvedPredictions.filter { it.decision == "UP" || it.decision == "DOWN" }
        val correct = validTrades.count { it.result == "CORRECT" }
        val incorrect = validTrades.count { it.result == "INCORRECT" }

        val winRate = if (validTrades.isNotEmpty()) {
            ((correct.toDouble() / validTrades.size) * 1000.0).roundToInt() / 10.0
        } else {
            0.0
        }

        val upTrades = validTrades.filter { it.decision == "UP" }
        val upCorrect = upTrades.count { it.result == "CORRECT" }
        val upWinRate = if (upTrades.isNotEmpty()) {
            ((upCorrect.toDouble() / upTrades.size) * 1000.0).roundToInt() / 10.0
        } else 0.0

        val downTrades = validTrades.filter { it.decision == "DOWN" }
        val downCorrect = downTrades.count { it.result == "CORRECT" }
        val downWinRate = if (downTrades.isNotEmpty()) {
            ((downCorrect.toDouble() / downTrades.size) * 1000.0).roundToInt() / 10.0
        } else 0.0

        val totalDelta = validTrades.sumOf { (it.actualPrice ?: it.currentPrice) - it.currentPrice }
        val avgDelta = if (validTrades.isNotEmpty()) totalDelta / validTrades.size else 0.0

        // Market Regime Detection (Phase 5 Feature)
        val regime = determineMarketRegime(currentSnapshot)

        // Factor Attribution Analysis (Phase 4 Feature)
        val factors = computeFactorAttributions(validTrades)

        // Closed-Loop Learning Bias Adjustment:
        // If recent resolved predictions show a systematic directional bias, calculate empirical delta
        val recentWindow = validTrades.takeLast(20)
        val recentUpWins = recentWindow.filter { it.decision == "UP" && it.result == "CORRECT" }.size
        val recentDownWins = recentWindow.filter { it.decision == "DOWN" && it.result == "CORRECT" }.size
        val learningBias = if (recentWindow.isNotEmpty()) {
            ((recentUpWins - recentDownWins).toDouble() / recentWindow.size * 0.04).coerceIn(-0.03, 0.03)
        } else 0.0

        return LivePerformanceStats(
            totalPredictions = pendingPredictions.size + resolvedPredictions.size,
            totalResolved = validTrades.size,
            correctCount = correct,
            incorrectCount = incorrect,
            winRatePercent = winRate,
            upWinRatePercent = upWinRate,
            downWinRatePercent = downWinRate,
            totalUpTrades = upTrades.size,
            totalDownTrades = downTrades.size,
            averageDeltaDollars = avgDelta,
            marketRegime = regime,
            factorAttributions = factors,
            learningBiasAdjustment = learningBias,
            lastResolvedTime = resolvedPredictions.lastOrNull()?.maturityTimestamp ?: 0L,
            pendingCount = pendingPredictions.size
        )
    }

    private fun determineMarketRegime(snapshot: IndicatorSnapshot?): String {
        if (snapshot == null) return "RANGING"
        val vol = snapshot.volatility
        val emaDiff = abs(snapshot.ema9 - snapshot.ema21)
        val mom = snapshot.momentum

        return when {
            vol > 80.0 -> "HIGH VOLATILITY"
            emaDiff > 25.0 && mom > 15.0 -> "TRENDING BULL"
            emaDiff > 25.0 && mom < -15.0 -> "TRENDING BEAR"
            abs(mom) < 8.0 && vol < 30.0 -> "LOW VOL / STAGNANT"
            else -> "RANGING"
        }
    }

    private fun computeFactorAttributions(trades: List<PredictionRecord>): List<FactorAttribution> {
        val factorNames = listOf("EMA", "RSI", "MOMENTUM", "VELOCITY", "VOLATILITY", "BUFFER")
        if (trades.isEmpty()) {
            return factorNames.map { FactorAttribution(it, 0, 0, 0.0, 0.0) }
        }

        return factorNames.map { name ->
            val activeTrades = trades.filter { record ->
                when (name) {
                    "EMA" -> abs(record.inputs.ema9 - record.inputs.ema21) > 2.0
                    "RSI" -> record.inputs.rsi > 58.0 || record.inputs.rsi < 42.0
                    "MOMENTUM" -> abs(record.inputs.momentum) > 5.0
                    "VELOCITY" -> abs(record.inputs.velocity) > 2.0
                    "VOLATILITY" -> record.inputs.volatility > 15.0
                    "BUFFER" -> abs(record.inputs.buffer) > 10.0
                    else -> true
                }
            }

            val correct = activeTrades.count { it.result == "CORRECT" }
            val rate = if (activeTrades.isNotEmpty()) {
                ((correct.toDouble() / activeTrades.size) * 1000.0).roundToInt() / 10.0
            } else 0.0

            val weightOffset = if (activeTrades.size >= 5) {
                ((rate - 50.0) / 100.0 * 0.05).coerceIn(-0.04, 0.04)
            } else 0.0

            FactorAttribution(
                factorName = name,
                totalTimesActive = activeTrades.size,
                correctTimesActive = correct,
                winRate = rate,
                suggestedWeightOffset = weightOffset
            )
        }
    }

    @Synchronized
    fun clear() {
        pendingPredictions.clear()
        resolvedPredictions.clear()
    }
}
