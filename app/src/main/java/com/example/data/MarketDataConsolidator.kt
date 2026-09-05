package com.example.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Result of consolidating multiple active BTC spot feeds.
 */
data class ConsolidatedMarketState(
    val timestamp: Long,
    val consolidatedPrice: Double,
    val activeSpotFeeds: List<PricePoint>,
    val divergencePercent: Double,
    val agreementStatus: ExchangeAgreementStatus,
    val consolidationFormula: String,
    val sourceProvenance: Map<String, PricePoint>
)

/**
 * Explicit Mathematical Consolidator for Multiple BTC Spot Sources.
 *
 * Consolidation Method:
 * Given active fresh spot feeds { (P_i, Δt_i) }:
 * - Freshness decay weight: w_i = exp(-λ * Δt_i)  (where λ = 0.4 / sec)
 * - Volume units across exchanges (24h volume vs tick quantity vs ticker volume) are non-conforming
 *   and incomparable, so weights rely strictly on freshness decay across conforming spot feeds.
 * - Consolidated Spot Price:
 *     P_consolidated = ∑(w_i * P_i) / ∑(w_i)
 *
 * If cross-exchange divergence exceeds tolerance (> 0.20%), the algorithm computes
 * a trimmed median of conforming feeds and adjusts confidence accordingly.
 */
class MarketDataConsolidator(
    private val maxAgeMillis: Long = 5000L,
    private val decayLambda: Double = 0.4
) {
    private val TAG = "QtY_Consolidator"

    /**
     * Consolidates a list of candidate PricePoints from spot exchanges.
     * Enforces Quote Isolation: strictly isolates USD from USDT feeds.
     * Stale feeds receive weight 0.0. If all feeds are stale, fails closed (consolidatedPrice = 0.0).
     */
    fun consolidate(
        spotPoints: List<PricePoint>,
        currentTimestamp: Long = System.currentTimeMillis()
    ): ConsolidatedMarketState {
        // 1. Filter valid and fresh points
        val freshPoints = spotPoints.filter { pt ->
            pt.price > 0.0 &&
            !pt.price.isNaN() &&
            !pt.price.isInfinite() &&
            (currentTimestamp - pt.timestamp) in -1000L..maxAgeMillis
        }

        if (freshPoints.isEmpty()) {
            return ConsolidatedMarketState(
                timestamp = currentTimestamp,
                consolidatedPrice = 0.0,
                activeSpotFeeds = emptyList(),
                divergencePercent = 0.0,
                agreementStatus = ExchangeAgreementStatus.DISAGREEMENT,
                consolidationFormula = "P_consolidated = 0.0 (NO ACTIVE FRESH FEEDS - FAIL CLOSED)",
                sourceProvenance = emptyMap()
            )
        }

        // Quote Isolation: prioritize conforming USD feeds (Coinbase, Bitstamp, Kraken USD)
        // Never blend USD and USDT directly without quote isolation.
        val usdPoints = freshPoints.filter { it.quoteCurrency.equals("USD", ignoreCase = true) }
        val conformingPoints = if (usdPoints.isNotEmpty()) usdPoints else freshPoints

        if (conformingPoints.size == 1) {
            val single = conformingPoints.first()
            return ConsolidatedMarketState(
                timestamp = currentTimestamp,
                consolidatedPrice = single.price,
                activeSpotFeeds = conformingPoints,
                divergencePercent = 0.0,
                agreementStatus = ExchangeAgreementStatus.SINGLE_EXCHANGE,
                consolidationFormula = "P_consolidated = ${single.exchange}[${single.quoteCurrency}](${String.format(Locale.US, "%.2f", single.price)})",
                sourceProvenance = mapOf(single.exchange to single)
            )
        }

        // 2. Measure cross-exchange divergence across conforming quote feeds
        val minPrice = conformingPoints.minOf { it.price }
        val maxPrice = conformingPoints.maxOf { it.price }
        val meanPrice = conformingPoints.map { it.price }.average()
        val divergencePct = if (meanPrice > 0.0) ((maxPrice - minPrice) / meanPrice) * 100.0 else 0.0

        val agreementStatus = when {
            divergencePct <= 0.08 -> ExchangeAgreementStatus.STRONG_AGREEMENT
            divergencePct <= 0.20 -> ExchangeAgreementStatus.MODERATE_AGREEMENT
            else -> ExchangeAgreementStatus.DISAGREEMENT
        }

        // 3. Compute consolidated price
        // If feeds diverge > 0.20% (DISAGREEMENT), compute the trimmed median of conforming feeds.
        // Otherwise (STRONG or MODERATE agreement), compute freshness-weighted exponential consolidation.
        var sumWeightedPrice = 0.0
        var sumWeights = 0.0
        val provenance = mutableMapOf<String, PricePoint>()
        val formulaTerms = mutableListOf<String>()

        for (pt in conformingPoints) {
            provenance[pt.exchange] = pt
            val ageSec = max(0.0, (currentTimestamp - pt.timestamp) / 1000.0)
            val freshnessW = if (ageSec > (maxAgeMillis / 1000.0)) 0.0 else exp(-decayLambda * ageSec)

            sumWeightedPrice += pt.price * freshnessW
            sumWeights += freshnessW

            formulaTerms.add("${pt.sourceKey}[${String.format(Locale.US, "%.1f", pt.price)} * ${String.format(Locale.US, "%.2f", freshnessW)}]")
        }

        val consolidatedPrice: Double
        val formulaStr: String
        val quoteTag = conformingPoints.first().quoteCurrency

        if (agreementStatus == ExchangeAgreementStatus.DISAGREEMENT) {
            // Trimmed median algorithm for divergent cross-exchange feeds
            val sortedPrices = conformingPoints.map { it.price }.sorted()
            consolidatedPrice = if (sortedPrices.size >= 4) {
                // Trim lowest and highest outlier
                val trimmed = sortedPrices.subList(1, sortedPrices.size - 1)
                computeMedian(trimmed)
            } else {
                computeMedian(sortedPrices)
            }
            formulaStr = "P_cons[$quoteTag] = TrimmedMedian(${conformingPoints.size} feeds) = ${String.format(Locale.US, "%.2f", consolidatedPrice)} (div=${String.format(Locale.US, "%.3f", divergencePct)}% > 0.20% [DISAGREEMENT])"
        } else {
            // Freshness-weighted exponential decay consolidation
            consolidatedPrice = if (sumWeights > 0.0) {
                sumWeightedPrice / sumWeights
            } else {
                meanPrice
            }
            formulaStr = "P_cons[$quoteTag] = ∑(w_i·P_i)/∑w_i = ${String.format(Locale.US, "%.2f", consolidatedPrice)} (div=${String.format(Locale.US, "%.3f", divergencePct)}%)"
        }

        return ConsolidatedMarketState(
            timestamp = currentTimestamp,
            consolidatedPrice = Math.round(consolidatedPrice * 100.0) / 100.0,
            activeSpotFeeds = conformingPoints,
            divergencePercent = Math.round(divergencePct * 1000.0) / 1000.0,
            agreementStatus = agreementStatus,
            consolidationFormula = formulaStr,
            sourceProvenance = provenance
        )
    }

    private fun computeMedian(sortedList: List<Double>): Double {
        if (sortedList.isEmpty()) return 0.0
        val n = sortedList.size
        return if (n % 2 == 1) {
            sortedList[n / 2]
        } else {
            (sortedList[n / 2 - 1] + sortedList[n / 2]) / 2.0
        }
    }
}
