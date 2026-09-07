package com.example.ui.marketactivity

import com.example.data.PricePoint
import java.util.Locale

/**
 * Observed Market Activity Bar representing authentic historical volume and aggressor order flow.
 *
 * CRITICAL INTEGRITY REQUIREMENT:
 * - This represents exclusively OBSERVED real-market activity (t <= NOW).
 * - It is NEVER a predicted volume metric.
 * - Normalized relative to the maximum observed volume within the visible window.
 */
data class MarketActivityBar(
    val timestamp: Long,
    val price: Double,
    val volume: Double,
    val priceChange: Double,
    val isBuyerDominant: Boolean, // Aggressor flow: price >= prevPrice
    val isSurge: Boolean, // volume >= 1.5 * rolling baseline
    val normalizedHeight: Float, // 0.0f to 1.0f relative to visible window max
    val isMissingOrZero: Boolean,
    val sourceExchange: String
)

/**
 * Summary metrics of observed order flow across the active historical window.
 */
data class OrderFlowSummary(
    val totalVolume: Double,
    val buyerVolume: Double,
    val sellerVolume: Double,
    val orderFlowImbalancePercent: Double, // +100% (all buyers) to -100% (all sellers)
    val maxBarVolume: Double,
    val activeBarsCount: Int,
    val isDataAvailable: Boolean,
    val statusMessage: String
)

object MarketActivityProcessor {

    /**
     * Converts a historical sequence of PricePoints into normalized MarketActivityBars.
     * Enforces mathematical normalization across the displayed window.
     * When volume is zero or unavailable, flags isMissingOrZero rather than fabricating dummy values.
     */
    fun process(
        points: List<PricePoint>,
        lookbackCount: Int = 120
    ): List<MarketActivityBar> {
        if (points.isEmpty()) return emptyList()

        val sample = if (points.size <= lookbackCount) points else points.takeLast(lookbackCount)
        val validVolumes = sample.map { it.volume }.filter { it > 0.0 }
        val maxVolume = if (validVolumes.isNotEmpty()) validVolumes.maxOrNull() ?: 1.0 else 1.0
        val avgVolume = if (validVolumes.isNotEmpty()) validVolumes.average() else 1.0

        val bars = mutableListOf<MarketActivityBar>()
        for (i in sample.indices) {
            val pt = sample[i]
            val prevPrice = if (i > 0) sample[i - 1].price else pt.price
            val priceChange = pt.price - prevPrice
            val isBuyer = priceChange >= 0.0
            val vol = if (pt.volume > 0.0) pt.volume else pt.baseVolumeBtc
            val isMissing = vol <= 0.0
            val normHeight = if (isMissing || maxVolume <= 0.0) 0f else (vol / maxVolume).toFloat().coerceIn(0f, 1f)
            val isSurge = !isMissing && avgVolume > 0.0 && (vol >= avgVolume * 1.5)

            bars.add(
                MarketActivityBar(
                    timestamp = pt.timestamp,
                    price = pt.price,
                    volume = vol,
                    priceChange = priceChange,
                    isBuyerDominant = isBuyer,
                    isSurge = isSurge,
                    normalizedHeight = normHeight,
                    isMissingOrZero = isMissing,
                    sourceExchange = pt.exchange
                )
            )
        }
        return bars
    }

    /**
     * Computes authentic order flow imbalance from the processed bars.
     * Distinguishes directional aggressor flow from raw gross volume.
     */
    fun computeOrderFlowSummary(bars: List<MarketActivityBar>): OrderFlowSummary {
        val validBars = bars.filter { !it.isMissingOrZero && it.volume > 0.0 }
        if (validBars.isEmpty()) {
            return OrderFlowSummary(
                totalVolume = 0.0,
                buyerVolume = 0.0,
                sellerVolume = 0.0,
                orderFlowImbalancePercent = 0.0,
                maxBarVolume = 0.0,
                activeBarsCount = 0,
                isDataAvailable = false,
                statusMessage = "VOLUME FEED: LIMITED / REST FALLBACK"
            )
        }

        var buyVol = 0.0
        var sellVol = 0.0
        var maxVol = 0.0

        for (b in validBars) {
            if (b.volume > maxVol) maxVol = b.volume
            if (b.isBuyerDominant) {
                buyVol += b.volume
            } else {
                sellVol += b.volume
            }
        }

        val totalVol = buyVol + sellVol
        val imbalance = if (totalVol > 0.0) {
            ((buyVol - sellVol) / totalVol) * 100.0
        } else 0.0

        val dominantSide = if (imbalance > 5.0) {
            "NET BUY DELTA (+${String.format(Locale.US, "%.1f", imbalance)}%)"
        } else if (imbalance < -5.0) {
            "NET SELL DELTA (${String.format(Locale.US, "%.1f", imbalance)}%)"
        } else {
            "NEUTRAL FLOW (${String.format(Locale.US, "%.1f", imbalance)}%)"
        }

        return OrderFlowSummary(
            totalVolume = totalVol,
            buyerVolume = buyVol,
            sellerVolume = sellVol,
            orderFlowImbalancePercent = imbalance,
            maxBarVolume = maxVol,
            activeBarsCount = validBars.size,
            isDataAvailable = true,
            statusMessage = "ORDER FLOW: $dominantSide | MAX BAR: ${String.format(Locale.US, "%.2f", maxVol)}"
        )
    }
}
