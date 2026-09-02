package com.example.engine

import java.util.UUID

/**
 * Snapshot of all indicator values computed in the 2-second cycle.
 */
data class IndicatorSnapshot(
    val ema9: Double,
    val ema21: Double,
    val rsi: Double,
    val momentum: Double,
    val velocity: Double,
    val acceleration: Double,
    val volatility: Double,
    val volume: Double,
    val volumeChange: Double,
    val buffer: Double,
    val bidAskSpread: Double,
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
    val score: Double,
    val strength: String, // "WEAK" | "MEDIUM" | "STRONG"
    val predictedPrice: Double,
    val currentPrice: Double = predictedPrice,
    val predictionHorizon: Int = 60, // seconds
    val maturityTimestamp: Long = timestamp + (predictionHorizon * 1000L),
    var actualPrice: Double? = null,
    var result: String? = null // "CORRECT" | "INCORRECT" | "PENDING"
)
