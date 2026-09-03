package com.example.kalshi

import com.example.engine.PredictionRecord
import kotlin.math.roundToInt

/**
 * Kalshi Order-Book Verification Engine.
 *
 * Core Mandates:
 * 1. Read available YES/NO order-book and market pricing data from Kalshi.
 * 2. Determine market-implied directional bias from existing available data.
 * 3. Compare bias against QtY's existing 30-second and 90-second predictions.
 * 4. Record agreement/disagreement for analysis and trade validation.
 * 5. NEVER feed order-book information backward into the prediction calculation.
 * 6. Do NOT manufacture missing order-book data (fail closed or mark unavailable).
 * 7. Do NOT claim predictive accuracy from agreement alone (verification/confirmation only).
 * 8. Preserve existing prediction behavior.
 */
object KalshiOrderBookVerifier {

    /**
     * Evaluates Kalshi order book and market pricing against QtY predictions.
     * Pure function: Does NOT alter prediction engine or backward feed into prediction calculations.
     */
    fun verify(
        market: KalshiMarket?,
        orderBook: KalshiOrderBookSnapshot?,
        prediction: PredictionRecord?,
        nowMs: Long = System.currentTimeMillis()
    ): KalshiVerificationResult {
        if (market == null) {
            return KalshiVerificationResult(
                ticker = "NONE",
                timestampMs = nowMs,
                marketBias = "UNAVAILABLE",
                agreement30s = "UNCONFIRMED",
                agreement90s = "UNCONFIRMED",
                verificationSummary = "UNCONFIRMED",
                detailExplanation = "No active Kalshi 15m contract found. Order book verification unavailable."
            )
        }

        val ticker = market.ticker

        // Check for stale order book data (> 30s old)
        val isStaleBook = orderBook != null && (nowMs - orderBook.timestampMs > 30_000L)

        // 1. Extract pricing from order book or market object without manufacturing data
        val validBook = if (isStaleBook) null else orderBook

        val bestYesBid = validBook?.bestYesBidCents?.takeIf { it in 1..99 }
            ?: if (market.yesBid in 1..99) market.yesBid else null
        val impliedYesAsk = validBook?.impliedYesAskCents?.takeIf { it in 1..99 }
            ?: if (market.yesAsk in 1..99) market.yesAsk else null
        val bestNoBid = validBook?.bestNoBidCents?.takeIf { it in 1..99 }
            ?: if (market.noBid in 1..99) market.noBid else null
        val impliedNoAsk = validBook?.impliedNoAskCents?.takeIf { it in 1..99 }
            ?: if (market.noAsk in 1..99) market.noAsk else null

        // Detect crossed/inverted order book anomaly
        val isCrossedBook = bestYesBid != null && impliedYesAsk != null && bestYesBid >= impliedYesAsk

        // Detect one-sided order book (bids only or asks only)
        val isOneSidedBook = (bestYesBid != null && impliedYesAsk == null) || (bestYesBid == null && impliedYesAsk != null)

        // Determine Yes Mid Price (in cents) strictly from two-sided quotes when possible
        val yesMid: Double? = when {
            isStaleBook -> null // Fail closed on stale order book
            isCrossedBook -> null // Fail closed on crossed book anomaly
            bestYesBid != null && impliedYesAsk != null -> (bestYesBid + impliedYesAsk) / 2.0
            market.lastPrice in 1..99 -> market.lastPrice.toDouble()
            else -> null // Do not fabricate mid price on one-sided book without last price
        }

        val noMid: Double? = yesMid?.let { 100.0 - it }

        // Depth & Imbalance
        val yesDepth = validBook?.totalYesDepth ?: 0.0
        val noDepth = validBook?.totalNoDepth ?: 0.0
        val totalDepth = yesDepth + noDepth
        val bookImbalance: Double? = if (totalDepth > 0.0) {
            (yesDepth - noDepth) / totalDepth
        } else null

        // 2. Determine Market-Implied Directional Bias
        val marketBias: String = when {
            isStaleBook -> "UNAVAILABLE" // Do not trade or verify against stale book
            isCrossedBook -> "UNAVAILABLE" // Crossed/inverted book is an anomaly
            yesMid != null -> {
                when {
                    yesMid >= 52.0 -> "UP"
                    yesMid <= 48.0 -> "DOWN"
                    // In the 48..52 neutral zone, check book imbalance if depth exists
                    bookImbalance != null && bookImbalance > 0.25 && yesDepth >= 5.0 -> "UP"
                    bookImbalance != null && bookImbalance < -0.25 && noDepth >= 5.0 -> "DOWN"
                    else -> "NEUTRAL"
                }
            }
            market.lastPrice in 1..99 -> {
                when {
                    market.lastPrice >= 52 -> "UP"
                    market.lastPrice <= 48 -> "DOWN"
                    else -> "NEUTRAL"
                }
            }
            else -> "UNAVAILABLE" // DO NOT manufacture missing data
        }

        val marketProbability = yesMid?.let { it / 100.0 }

        // 3. Compare with QtY's existing 30s and 90s predictions
        val pred30s = prediction?.decision ?: "NO-TRADE"
        val pred90s = prediction?.projectedDecision90s ?: "NO-TRADE"

        val agreement30s = when {
            marketBias == "UNAVAILABLE" -> "UNCONFIRMED"
            pred30s == "NO-TRADE" || marketBias == "NEUTRAL" -> "NEUTRAL"
            pred30s == marketBias -> "AGREEMENT"
            else -> "DISAGREEMENT"
        }

        val agreement90s = when {
            marketBias == "UNAVAILABLE" -> "UNCONFIRMED"
            pred90s == "NO-TRADE" || marketBias == "NEUTRAL" -> "NEUTRAL"
            pred90s == marketBias -> "AGREEMENT"
            else -> "DISAGREEMENT"
        }

        // 4. Overall Verification Summary (Verification/confirmation only - not claiming predictive accuracy)
        val summary = when {
            marketBias == "UNAVAILABLE" -> "UNCONFIRMED"
            agreement30s == "AGREEMENT" && agreement90s == "AGREEMENT" -> "FULL_AGREEMENT"
            agreement30s == "AGREEMENT" || agreement90s == "AGREEMENT" -> {
                if (agreement30s != "DISAGREEMENT" && agreement90s != "DISAGREEMENT") "PARTIAL_AGREEMENT"
                else "DIVERGENCE"
            }
            agreement30s == "DISAGREEMENT" || agreement90s == "DISAGREEMENT" -> "DIVERGENCE"
            else -> "NEUTRAL"
        }

        val explanation = buildString {
            append("Contract: $ticker")
            if (yesMid != null) {
                append(" | YES Mid: ${yesMid.roundToInt()}¢ (P(UP)=${((marketProbability ?: 0.5) * 100).roundToInt()}%)")
            } else if (market.lastPrice > 0) {
                append(" | Last: ${market.lastPrice}¢")
            } else {
                append(" | Pricing: UNAVAILABLE")
            }
            if (orderBook != null && totalDepth > 0) {
                append(" | Depth: YES=${yesDepth.roundToInt()} NO=${noDepth.roundToInt()}")
            }
            append(" | Market Bias: $marketBias")
            append(" | 30s: $pred30s ($agreement30s)")
            append(" | 90s: $pred90s ($agreement90s)")
            append(" => Status: $summary")
        }

        return KalshiVerificationResult(
            ticker = ticker,
            timestampMs = nowMs,
            marketPriceCents = market.lastPrice.takeIf { it > 0 },
            yesMidPriceCents = yesMid,
            noMidPriceCents = noMid,
            marketImpliedProbability = marketProbability,
            marketBias = marketBias,
            bookImbalanceRatio = bookImbalance,
            totalYesDepth = yesDepth,
            totalNoDepth = noDepth,
            agreement30s = agreement30s,
            agreement90s = agreement90s,
            verificationSummary = summary,
            detailExplanation = explanation
        )
    }
}
