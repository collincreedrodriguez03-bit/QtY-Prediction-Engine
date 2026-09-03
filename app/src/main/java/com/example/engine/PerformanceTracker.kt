package com.example.engine

import com.example.data.PricePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Performance metrics and empirical factor association analysis for QtY Scalping Engine.
 * Note: Factor attribution represents empirical association/correlation under activation, not causal contribution.
 */
data class FactorAttribution(
    val factorName: String,
    val totalTimesActive: Int = 0,
    val correctTimesActive: Int = 0,
    val winRate: Double = 0.0,
    val suggestedWeightOffset: Double = 0.0
)

data class LivePerformanceStats(
    // 1. Operational Streaming Metrics (Complete continuous 2s execution stream)
    val operationalPredictionCount: Int = 0,
    val operationalResolvedCount: Int = 0,
    val operationalCorrectCount: Int = 0,
    val operationalIncorrectCount: Int = 0,
    val operationalWinRatePercent: Double = 0.0,

    // 2. Statistical Non-Overlapping 30s Evaluation Stream (T, T+30s, T+60s, ...)
    // Explicitly non-overlapping time windows; does NOT assume i.i.d. statistical independence
    val statisticalEvaluationCount: Int = 0,
    val statisticalCorrectCount: Int = 0,
    val statisticalIncorrectCount: Int = 0,
    val statisticalWinRatePercent: Double = 0.0,

    // Baselines on Active Trade Opportunities (Model-eligible samples)
    val baselineAlwaysUpWinRate: Double = 0.0,
    val baselineAlwaysDownWinRate: Double = 0.0,

    // Baselines on Global Non-Overlapping Intervals (All intervals including NO-TRADE)
    val globalBaselineAlwaysUpWinRate: Double = 0.0,
    val globalBaselineAlwaysDownWinRate: Double = 0.0,

    // Backward-compatible accessors for existing UI bindings
    val totalPredictions: Int = operationalPredictionCount,
    val totalResolved: Int = operationalResolvedCount,
    val correctCount: Int = operationalCorrectCount,
    val incorrectCount: Int = operationalIncorrectCount,
    val winRatePercent: Double = operationalWinRatePercent,

    val upWinRatePercent: Double = 0.0,
    val downWinRatePercent: Double = 0.0,
    val totalUpTrades: Int = 0,
    val totalDownTrades: Int = 0,
    val totalNoTrades: Int = 0,
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
 * Analyzes empirical factor association and provides zero-lookahead calibration adjustments.
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
     * Evaluates prediction validation strictly against the contract's defined settlement reference
     * using the authentic 30-second observation (record.timestamp + 30s).
     * - UP: winning if actualPrice > settlementReference; zero change or drop = INCORRECT
     * - DOWN: winning if actualPrice < settlementReference; zero change or rise = INCORRECT
     * - NO-TRADE: marked NEUTRAL / NO_ACTION
     */
    @Synchronized
    fun resolveMatured(
        currentPrice: Double,
        currentTimestamp: Long,
        priceHistory: List<PricePoint>? = null
    ): List<PredictionRecord> {
        val newlyResolved = mutableListOf<PredictionRecord>()
        val iterator = pendingPredictions.iterator()

        while (iterator.hasNext()) {
            val record = iterator.next()

            // 1. Resolve 30-second prediction at t + 30s
            if (currentTimestamp >= record.maturityTimestamp && record.result30s == null) {
                val eligible = priceHistory?.filter { it.timestamp >= record.timestamp }
                val nearestPoint = eligible?.minByOrNull { abs(it.timestamp - record.maturityTimestamp) }
                val observationPrice = nearestPoint?.price ?: currentPrice

                record.actualPrice = observationPrice
                record.actualPrice30s = observationPrice

                val contractDelta = observationPrice - record.settlementReference
                val outcome = when (record.decision) {
                    "UP" -> if (contractDelta > 0.0) "CORRECT" else "INCORRECT"
                    "DOWN" -> if (contractDelta < 0.0) "CORRECT" else "INCORRECT"
                    else -> "NO-TRADE"
                }
                record.result = outcome
                record.result30s = outcome

                if (!resolvedPredictions.contains(record)) {
                    resolvedPredictions.add(record)
                }
                newlyResolved.add(record)
                iterator.remove()
            }
        }

        // 2. Resolve 90-second prediction at t + 90s on resolved history
        for (record in resolvedPredictions) {
            if (currentTimestamp >= record.maturityTimestamp90s && record.result90s == null) {
                val eligible = priceHistory?.filter { it.timestamp >= record.timestamp }
                val nearestPoint90s = eligible?.minByOrNull { abs(it.timestamp - record.maturityTimestamp90s) }
                val observationPrice90s = nearestPoint90s?.price ?: currentPrice

                record.actualPrice90s = observationPrice90s
                val delta90s = observationPrice90s - record.settlementReference
                record.result90s = when (record.projectedDecision90s) {
                    "UP" -> if (delta90s > 0.0) "CORRECT" else "INCORRECT"
                    "DOWN" -> if (delta90s < 0.0) "CORRECT" else "INCORRECT"
                    else -> "NO-TRADE"
                }
                if (!newlyResolved.contains(record)) {
                    newlyResolved.add(record)
                }
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
     * Evaluates both the continuous operational stream and a dedicated non-overlapping 30s evaluation stream.
     */
    @Synchronized
    fun computeStats(currentSnapshot: IndicatorSnapshot?): LivePerformanceStats {
        val allResolved = resolvedPredictions.toList()
        val validTrades = allResolved.filter { it.decision == "UP" || it.decision == "DOWN" }
        val noTrades = allResolved.filter { it.decision != "UP" && it.decision != "DOWN" }

        val opCorrect = validTrades.count { it.result == "CORRECT" }
        val opIncorrect = validTrades.count { it.result == "INCORRECT" }

        val opWinRate = if (validTrades.isNotEmpty()) {
            ((opCorrect.toDouble() / validTrades.size) * 1000.0).roundToInt() / 10.0
        } else {
            0.0
        }

        // Dedicated Non-Overlapping 30s Statistical Evaluation Stream (T, T+30s, T+60s, ...)
        // Filters distinct predictions spaced by >= 30,000ms to eliminate overlapping evaluation horizons
        val nonOverlappingTrades = mutableListOf<PredictionRecord>()
        var lastEvaluatedTimestamp = 0L
        val sortedTrades = validTrades.sortedBy { it.timestamp }
        for (trade in sortedTrades) {
            if (trade.timestamp - lastEvaluatedTimestamp >= 30_000L) {
                nonOverlappingTrades.add(trade)
                lastEvaluatedTimestamp = trade.timestamp
            }
        }

        val statCorrect = nonOverlappingTrades.count { it.result == "CORRECT" }
        val statIncorrect = nonOverlappingTrades.count { it.result == "INCORRECT" }
        val statWinRate = if (nonOverlappingTrades.isNotEmpty()) {
            ((statCorrect.toDouble() / nonOverlappingTrades.size) * 1000.0).roundToInt() / 10.0
        } else 0.0

        // Benchmark Baselines evaluated on active model trade opportunities (same non-overlapping subset)
        var baseUpWins = 0
        var baseDownWins = 0
        for (trade in nonOverlappingTrades) {
            val delta = (trade.actualPrice ?: trade.settlementReference) - trade.settlementReference
            if (delta > 0.0) baseUpWins++
            if (delta < 0.0) baseDownWins++
        }
        val baseUpRate = if (nonOverlappingTrades.isNotEmpty()) {
            ((baseUpWins.toDouble() / nonOverlappingTrades.size) * 1000.0).roundToInt() / 10.0
        } else 0.0
        val baseDownRate = if (nonOverlappingTrades.isNotEmpty()) {
            ((baseDownWins.toDouble() / nonOverlappingTrades.size) * 1000.0).roundToInt() / 10.0
        } else 0.0

        // Global Non-Overlapping Baseline across ALL intervals (including NO-TRADE intervals)
        val globalNonOverlapping = mutableListOf<PredictionRecord>()
        var lastGlobalTimestamp = 0L
        val sortedAll = allResolved.sortedBy { it.timestamp }
        for (rec in sortedAll) {
            if (rec.timestamp - lastGlobalTimestamp >= 30_000L) {
                globalNonOverlapping.add(rec)
                lastGlobalTimestamp = rec.timestamp
            }
        }
        var globalUpWins = 0
        var globalDownWins = 0
        for (rec in globalNonOverlapping) {
            val delta = (rec.actualPrice ?: rec.settlementReference) - rec.settlementReference
            if (delta > 0.0) globalUpWins++
            if (delta < 0.0) globalDownWins++
        }
        val globalUpRate = if (globalNonOverlapping.isNotEmpty()) {
            ((globalUpWins.toDouble() / globalNonOverlapping.size) * 1000.0).roundToInt() / 10.0
        } else 0.0
        val globalDownRate = if (globalNonOverlapping.isNotEmpty()) {
            ((globalDownWins.toDouble() / globalNonOverlapping.size) * 1000.0).roundToInt() / 10.0
        } else 0.0

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

        val totalDelta = validTrades.sumOf { (it.actualPrice ?: it.settlementReference) - it.settlementReference }
        val avgDelta = if (validTrades.isNotEmpty()) totalDelta / validTrades.size else 0.0

        // Market Regime Detection
        val regime = determineMarketRegime(currentSnapshot)

        // Empirical Factor Association Analysis (Labeled Non-Causal)
        val factors = computeFactorAttributions(validTrades)

        // Closed-Loop Learning Bias Adjustment:
        val recentWindow = validTrades.takeLast(20)
        val recentUpWins = recentWindow.filter { it.decision == "UP" && it.result == "CORRECT" }.size
        val recentDownWins = recentWindow.filter { it.decision == "DOWN" && it.result == "CORRECT" }.size
        val learningBias = if (recentWindow.isNotEmpty()) {
            ((recentUpWins - recentDownWins).toDouble() / recentWindow.size * 0.04).coerceIn(-0.03, 0.03)
        } else 0.0

        return LivePerformanceStats(
            operationalPredictionCount = pendingPredictions.size + resolvedPredictions.size,
            operationalResolvedCount = validTrades.size,
            operationalCorrectCount = opCorrect,
            operationalIncorrectCount = opIncorrect,
            operationalWinRatePercent = opWinRate,
            statisticalEvaluationCount = nonOverlappingTrades.size,
            statisticalCorrectCount = statCorrect,
            statisticalIncorrectCount = statIncorrect,
            statisticalWinRatePercent = statWinRate,
            baselineAlwaysUpWinRate = baseUpRate,
            baselineAlwaysDownWinRate = baseDownRate,
            globalBaselineAlwaysUpWinRate = globalUpRate,
            globalBaselineAlwaysDownWinRate = globalDownRate,
            upWinRatePercent = upWinRate,
            downWinRatePercent = downWinRate,
            totalUpTrades = upTrades.size,
            totalDownTrades = downTrades.size,
            totalNoTrades = noTrades.size,
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
