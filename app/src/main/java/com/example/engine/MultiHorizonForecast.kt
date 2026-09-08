package com.example.engine

import com.example.engine.external.ExternalPredictionFeatures

/**
 * Canonical supported multi-horizon forecast durations.
 * Defined strictly per quantitative specifications:
 * 5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s.
 * Obsolete horizons (90s, 180s, 240s, 1200s) deleted.
 */
enum class PredictionHorizon(val seconds: Int, val description: String) {
    H_5S(5, "5-Second Micro Scalp"),
    H_10S(10, "10-Second Fast Scalp"),
    H_30S(30, "30-Second Primary Baseline"),
    H_60S(60, "60-Second Trend"),
    H_120S(120, "120-Second Short Swing"),
    H_300S(300, "300-Second 5-Minute Structure"),
    H_600S(600, "600-Second 10-Minute Macro Regime"),
    H_900S(900, "900-Second 15-Minute Contract Target");

    companion object {
        val ALL_HORIZONS: List<PredictionHorizon> = values().toList()
        val ALL_SECONDS: List<Int> = ALL_HORIZONS.map { it.seconds }
        fun fromSeconds(seconds: Int): PredictionHorizon? = values().find { it.seconds == seconds }
    }
}

/**
 * Audit provenance tracking for a specific horizon forecast.
 * Retains authentic exchange source, input timestamps, and advisory status.
 */
data class HorizonProvenance(
    val sourceExchange: String = "CONSOLIDATED_USD",
    val marketTimestamp: Long,
    val localReceiptTimestamp: Long,
    val isResearchAdvisory: Boolean,
    val formulaDisplay: String = "",
    val researchExternalFeatures: ExternalPredictionFeatures? = null
)

/**
 * An independent econometric forecast for a specific time horizon.
 * Each horizon possesses its own distinct data-learned parameters, forecast timestamp,
 * price projection dynamics, uncertainty metrics, and later independent outcome resolution.
 */
data class HorizonForecast(
    val horizonSeconds: Int,
    val inputTimestamp: Long,
    val maturityTimestamp: Long,
    val modelVersion: String,
    val score: Double, // honest model score in [0.0, 1.0], no fake confidence values
    val decision: String, // "UP", "DOWN", "NO-TRADE"
    val strength: String, // "WEAK", "MEDIUM", "STRONG"
    val predictedPrice: Double,
    val currentPrice: Double,
    val settlementReference: Double,
    val featureSnapshot: IndicatorSnapshot,
    val provenance: HorizonProvenance,
    val predictedReturn: Double = 0.0,
    val uncertainty: Double = 0.0,
    val standardizedScore: Double = 0.0,
    val direction: String = decision,
    val eligibility: String = "ELIGIBLE",
    var actualPrice: Double? = null,
    var actualReturn: Double? = null,
    var result: String? = null, // "CORRECT", "INCORRECT", "TIE", "UNRESOLVED", "PENDING"
    var resolvedTimestamp: Long? = null
)

/**
 * Statistical performance tracking record for a single horizon.
 */
data class HorizonPerformanceStats(
    val horizonSeconds: Int,
    val totalForecasts: Int = 0,
    val resolvedCount: Int = 0,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0,
    val unresolvedCount: Int = 0,
    val winRate: Double = 0.0
)

/**
 * Timescale-calibrated factor weights for horizon calculations.
 */
data class HorizonFactorWeights(
    val wEma: Double,
    val wRsi: Double,
    val wMom: Double,
    val wVel: Double,
    val wVol: Double,
    val wVolume: Double,
    val wBuf: Double
)

