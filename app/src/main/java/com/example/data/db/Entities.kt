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

@Entity(
    tableName = "predictions",
    indices = [
        androidx.room.Index(value = ["timestamp"]),
        androidx.room.Index(value = ["maturityTimestamp"]),
        androidx.room.Index(value = ["result"])
    ]
)
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
    val calibratedScore: Double? = null,
    // IndicatorSnapshot fields
    val ema9: Double,
    val ema21: Double,
    val rsi: Double,
    val momentum: Double,
    val velocity: Double,
    val acceleration: Double,
    val volatility: Double,
    val volume: Double = 0.0,
    val volumeChange: Double,
    val buffer: Double,
    val bidAskSpread: Double = 0.0,
    val exchangeAgreement: String = "STRONG_AGREEMENT",
    val formulaDisplay: String,
    // Settlement methodology & reference
    val settlementReference: Double = 0.0,
    val settlementMethodology: String = "15M_ROLLING_WINDOW",
    // 90s projection fields
    val projectedPrice90s: Double = 0.0,
    val projectedDecision90s: String = "NO-TRADE",
    val maturityTimestamp90s: Long = 0L,
    val actualPrice90s: Double? = null,
    val result90s: String? = null,
    // Dedicated 30s fields
    val actualPrice30s: Double? = null,
    val result30s: String? = null,
    // Source & market timestamps
    val sourceExchange: String = "CONSOLIDATED_USD",
    val marketTimestamp: Long = 0L,
    // Kalshi contract & execution lineage
    val kalshiContractTicker: String? = null,
    val strikePrice: Double? = null,
    val kalshiOrderId: String? = null,
    val kalshiOrderStatus: String? = null,
    val kalshiFilledCount: Int? = null,
    val kalshiOrderPrice: Int? = null,
    val executionPrice: Double? = null
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
