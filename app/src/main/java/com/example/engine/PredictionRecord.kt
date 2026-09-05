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
 * Prediction Record model holding full context and lineage across its lifecycle.
 *
 * Lifecycle Architecture:
 * - Time T (Immutable Context): Prediction inputs, indicator snapshot, model score,
 *   decision, eligibility state, market timestamps, and source instruments are established
 *   at prediction time and MUST NOT be altered.
 * - Time T + Δt (Execution & Resolution Phase): Execution tracking fields (Kalshi order,
 *   fill count, execution price) and maturity evaluation fields (actualPrice30s, actualPrice90s,
 *   result30s, result90s) are updated as events occur and MUST be immediately persisted to
 *   durable storage via [EngineRepository].
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
    var result: String? = null, // "CORRECT" | "INCORRECT" | "TIE" | "UNRESOLVED" | "PENDING"
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
    // Lineage & Audit Fields (Pass 3 Requirement)
    val sourceInstrument: String = "BTC-USD",
    val localReceiptTimestamp: Long = timestamp,
    val marketDataUsed: String = "",
    val eligibilityState: String = "ELIGIBLE",
    val noTradeReason: String? = null,
    // Kalshi order and execution lineage fields
    var kalshiContractTicker: String? = null,
    var strikePrice: Double? = null,
    var kalshiOrderId: String? = null,
    var kalshiOrderStatus: String? = null,
    var kalshiFilledCount: Int? = null,
    var kalshiOrderPrice: Int? = null,
    var executionPrice: Double? = null,
    var kalshiClientOrderId: String? = null,
    var resolutionTimestamp: Long? = null,
    var resolutionNotes: String? = null,
    // Unified external research features container (Never alters core model weights)
    val researchExternalFeatures: com.example.engine.external.ExternalPredictionFeatures? = null
) {
    val raw_model_score: Double get() = score
}
