package com.example.engine

import java.util.UUID

/**
 * Snapshot of all indicator values computed in the 2-second cycle.
 */
data class IndicatorSnapshot(
    val ema9: Double = 0.0,
    val ema21: Double = 0.0,
    val rsi: Double = 50.0,
    val momentum: Double = 0.0,
    val velocity: Double = 0.0,
    val acceleration: Double = 0.0,
    val volatility: Double = 0.0,
    val volume: Double = 0.0,
    val volumeChange: Double = 1.0,
    val buffer: Double = 0.0,
    val bidAskSpread: Double = 0.0,
    val exchangeAgreement: String = "STRONG_AGREEMENT",
    val formulaDisplay: String = ""
)

/**
 * Immutable Prediction Record model holding full context and lineage.
 */
data class PredictionRecord(
    val predictionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val inputs: IndicatorSnapshot,
    val decision: String, // "UP" | "DOWN" | "NO-TRADE"
    val score: Double, // raw_model_score
    val strength: String, // "WEAK" | "MEDIUM" | "STRONG"
    val predictedPrice: Double,
    val currentPrice: Double = predictedPrice,
    val settlementReference: Double = currentPrice,
    val settlementMethodology: String = "15M_ROLLING_WINDOW",
    val predictionHorizon: Int = 30, // seconds
    val maturityTimestamp: Long = timestamp + (predictionHorizon * 1000L),
    val calibratedScore: Double? = null,
    var actualPrice: Double? = null,
    var result: String? = null, // "CORRECT" | "INCORRECT" | "PENDING"
    val projectedPrice90s: Double = predictedPrice,
    val projectedDecision90s: String = decision,
    // Dedicated 30-second and 90-second resolution evaluation fields
    var actualPrice30s: Double? = null,
    var result30s: String? = null,
    val maturityTimestamp90s: Long = timestamp + 90_000L,
    var actualPrice90s: Double? = null,
    var result90s: String? = null,
    // Source / exchange provenance and market timestamp
    val sourceExchange: String = "CONSOLIDATED_USD",
    val marketTimestamp: Long = timestamp,
    // Kalshi order and execution lineage fields
    var kalshiContractTicker: String? = null,
    var strikePrice: Double? = null,
    var kalshiOrderId: String? = null,
    var kalshiOrderStatus: String? = null,
    var kalshiFilledCount: Int? = null,
    var kalshiOrderPrice: Int? = null,
    var executionPrice: Double? = null
) {
    val raw_model_score: Double get() = score
}
