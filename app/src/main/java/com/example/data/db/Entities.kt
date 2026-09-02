package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "engine_cycles")
data class EngineCycleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val cycleNumber: Long,
    val btcPrice: Double,
    val primaryExchange: String,
    val krakenPrice: Double?,
    val coinbasePrice: Double?,
    val binancePrice: Double?,
    val bitstampPrice: Double?,
    val divergencePercent: Double,
    val totalTicks: Long
)

@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey val predictionId: String,
    val timestamp: Long,
    val decision: String,
    val score: Double,
    val strength: String,
    val currentPrice: Double,
    val predictedPrice: Double,
    val predictionHorizon: Int,
    val maturityTimestamp: Long,
    val actualPrice: Double?,
    val result: String?,
    val ema9: Double,
    val ema21: Double,
    val rsi: Double,
    val momentum: Double,
    val velocity: Double,
    val acceleration: Double,
    val volatility: Double,
    val volumeChange: Double,
    val buffer: Double,
    val formulaDisplay: String
)

@Entity(tableName = "backtest_records")
data class BacktestRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val totalSamples: Int,
    val totalTrades: Int,
    val upPredictions: Int,
    val downPredictions: Int,
    val correctPredictions: Int,
    val incorrectPredictions: Int,
    val winRatePercent: Double,
    val horizonSeconds: Int = 30
)

@Entity(tableName = "adaptive_calibrations")
data class AdaptiveCalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val factorName: String,
    val activeCount: Int,
    val winRate: Double,
    val weightOffset: Double,
    val learningBias: Double
)
