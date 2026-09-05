package com.example.engine.external

import com.example.data.PricePoint
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 1. TRADINGVIEW → TREND SCORE
 *
 * POLICY & ETHICAL DIRECTIVES:
 * - DO NOT scrape TradingView.
 * - DO NOT reverse-engineer TradingView data.
 * - DO NOT use TradingView website values as an automated prediction input.
 * - QtY RECREATES THE CONCEPT OF A TREND SCORE using legally accessible underlying
 *   BTC market data already available to QtY (Consolidated Spot Feeds).
 *
 * CONCEPT & COMPONENTS:
 * - Multi-timeframe EMA structure (EMA 9, EMA 21, EMA 50 on 2s micro-timeframe)
 * - Linear Price Slope across rolling 20s and 60s windows
 * - Micro-Momentum (5-period and 15-period rate of change)
 * - Directional Consistency (ratio of positive vs negative returns)
 * - Multi-Timeframe Trend Agreement (alignment between micro 20s, short 60s, and anchor 180s)
 *
 * OUTPUT:
 * trendScore ∈ [0.0, 1.0]
 * ~0.0 = strong bearish trend
 * ~0.5 = neutral / no meaningful trend
 * ~1.0 = strong bullish trend
 *
 * NOTE: Does NOT assume these components are predictive. Must be independently tested out-of-sample.
 */
class TradingViewTrendFeature {

    companion object {
        const val SOURCE_NAME = "TRADINGVIEW_CONCEPT"
        const val METRIC_NAME = "BTC_SYNTHESIZED_TREND_SCORE"
        const val MAX_ALLOWABLE_STALENESS_MS = 10_000L // 10 seconds in live micro-scalping
    }

    fun calculate(
        points: List<PricePoint>,
        nowMs: Long = System.currentTimeMillis()
    ): ResearchFeatureValue {
        if (points.isEmpty()) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "Price history is empty"
            )
        }

        val latestPoint = points.last()

        // 1. Future timestamp validation (Strict no-lookahead check)
        if (latestPoint.timestamp > nowMs + 1000L) { // Allow 1s tolerance for clock drift
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.FUTURE_DATED,
                reason = "Observation timestamp (${latestPoint.timestamp}) is future-dated relative to clock ($nowMs)"
            )
        }

        // 2. Staleness validation
        val ageMs = nowMs - latestPoint.timestamp
        if (ageMs > MAX_ALLOWABLE_STALENESS_MS) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "Latest price point is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)"
            )
        }

        // 3. Minimum points validation (Need at least 15 points = ~30s of 2s ticks)
        if (points.size < 15) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "Insufficient price history (${points.size} < 15 points)"
            )
        }

        val prices = points.map { it.price }
        val currentPrice = prices.last()
        if (currentPrice <= 0.0) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Current price is non-positive ($currentPrice)"
            )
        }

        // --- COMPONENT 1: EMA Structure (EMA 9 vs EMA 21 vs EMA 50) ---
        val ema9 = computeEma(prices, 9)
        val ema21 = computeEma(prices, 21)
        val ema50 = computeEma(prices, 50.coerceAtMost(prices.size))

        val emaAlignment: Double = when {
            ema9 > ema21 && ema21 > ema50 -> 1.0  // Full bullish stack
            ema9 < ema21 && ema21 < ema50 -> -1.0 // Full bearish stack
            ema9 > ema21 -> 0.5                   // Partial bullish
            ema9 < ema21 -> -0.5                  // Partial bearish
            else -> 0.0
        }
        val emaDiffPct = ((ema9 - ema21) / currentPrice) * 1000.0 // bps scaled
        val emaStrength = tanh(emaDiffPct).coerceIn(-1.0, 1.0)
        val emaComponent = (0.5 * emaAlignment + 0.5 * emaStrength).coerceIn(-1.0, 1.0)

        // --- COMPONENT 2: Price Slope (Linear regression on recent 15 points) ---
        val recentWindow = prices.takeLast(15)
        val slope = computeLinearSlope(recentWindow)
        val normalizedSlope = tanh((slope / currentPrice) * 2000.0).coerceIn(-1.0, 1.0)

        // --- COMPONENT 3: Momentum (Rate of change across 5 and 15 periods) ---
        val mom5 = (currentPrice - prices[(prices.size - 5).coerceAtLeast(0)]) / currentPrice
        val mom15 = (currentPrice - prices[(prices.size - 15).coerceAtLeast(0)]) / currentPrice
        val normalizedMom = tanh((mom5 * 1500.0) + (mom15 * 1000.0)).coerceIn(-1.0, 1.0)

        // --- COMPONENT 4: Directional Consistency (Proportion of positive returns) ---
        var positiveMoves = 0
        var negativeMoves = 0
        for (i in (prices.size - 14) until prices.size) {
            val delta = prices[i] - prices[i - 1]
            if (delta > 0) positiveMoves++
            else if (delta < 0) negativeMoves++
        }
        val totalActiveMoves = positiveMoves + negativeMoves
        val directionalConsistency = if (totalActiveMoves > 0) {
            ((positiveMoves - negativeMoves).toDouble() / totalActiveMoves).coerceIn(-1.0, 1.0)
        } else 0.0

        // --- COMPONENT 5: Multi-Timeframe Agreement ---
        // Fast (10s = 5 periods), Medium (30s = 15 periods), Slow (60s = 30 periods)
        val pFast = prices[(prices.size - 5).coerceAtLeast(0)]
        val signFast = when {
            currentPrice > pFast -> 1.0
            currentPrice < pFast -> -1.0
            else -> 0.0
        }
        val pMed = prices[(prices.size - 15).coerceAtLeast(0)]
        val signMed = when {
            currentPrice > pMed -> 1.0
            currentPrice < pMed -> -1.0
            else -> 0.0
        }
        val slowIndex = (prices.size - 30).coerceAtLeast(0)
        val pSlow = prices[slowIndex]
        val signSlow = when {
            currentPrice > pSlow -> 1.0
            currentPrice < pSlow -> -1.0
            else -> 0.0
        }
        val mtfAgreement = ((signFast + signMed + signSlow) / 3.0).coerceIn(-1.0, 1.0)

        // --- SYNTHESIZE COMBINED RAW TREND SCORE ---
        // Weights: EMA (0.25) + Slope (0.25) + Momentum (0.20) + Consistency (0.15) + MTF (0.15) = 1.00
        val rawDirectional = (
            emaComponent * 0.25 +
            normalizedSlope * 0.25 +
            normalizedMom * 0.20 +
            directionalConsistency * 0.15 +
            mtfAgreement * 0.15
        ).coerceIn(-1.0, 1.0)

        // Convert from [-1.0, +1.0] to strictly [0.0, 1.0]
        // -1.0 -> 0.0 (strong bearish), 0.0 -> 0.5 (neutral), +1.0 -> 1.0 (strong bullish)
        val normalizedTrendScore = Math.round(((rawDirectional + 1.0) / 2.0).coerceIn(0.0, 1.0) * 1000.0) / 1000.0

        return ResearchFeatureValue(
            isAvailable = true,
            normalizedValue = normalizedTrendScore,
            rawObservation = currentPrice,
            provenance = ExternalObservationProvenance(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                sourceTimestampMs = latestPoint.timestamp,
                retrievalTimestampMs = nowMs,
                apiVersion = "1.0",
                rawValue = "currentPrice=$currentPrice, slope=$slope, ema9=$ema9, ema21=$ema21",
                derivedValue = normalizedTrendScore,
                provenanceStatus = ExternalFeatureProvenanceStatus.AUTHENTIC_DERIVED,
                notes = "Recreated trend score concept via authentic spot price series"
            )
        )
    }

    private fun computeEma(prices: List<Double>, period: Int): Double {
        if (prices.isEmpty()) return 0.0
        val alpha = 2.0 / (period + 1.0)
        var ema = prices.first()
        for (i in 1 until prices.size) {
            ema = (prices[i] * alpha) + (ema * (1.0 - alpha))
        }
        return ema
    }

    private fun computeLinearSlope(prices: List<Double>): Double {
        val n = prices.size
        if (n < 2) return 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            val y = prices[i]
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        val denom = (n * sumXX - sumX * sumX)
        if (abs(denom) < 1e-9) return 0.0
        return (n * sumXY - sumX * sumY) / denom
    }
}
