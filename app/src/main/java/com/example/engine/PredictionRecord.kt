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
    val score: Double,
    val strength: String, // "WEAK" | "MEDIUM" | "STRONG"
    val predictedPrice: Double,
    val currentPrice: Double = predictedPrice,
    val predictionHorizon: Int = 30, // seconds
    val maturityTimestamp: Long = timestamp + (predictionHorizon * 1000L),
    var actualPrice: Double? = null,
    var result: String? = null // "CORRECT" | "INCORRECT" | "PENDING"
)
