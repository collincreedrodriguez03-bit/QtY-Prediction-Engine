package com.example.data

import com.example.data.db.AdaptiveCalibrationEntity
import com.example.data.db.AppDatabase
import com.example.data.db.BacktestRecordEntity
import com.example.data.db.EngineCycleEntity
import com.example.data.db.EngineDao
import com.example.data.db.PredictionEntity
import com.example.engine.BacktestResult
import com.example.engine.FactorAttribution
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class EngineRepository(
    private val database: AppDatabase
) {
    private val dao: EngineDao = database.engineDao()

    suspend fun recordCycle(
        timestamp: Long,
        cycleNumber: Long,
        btcPrice: Double,
        primaryExchange: String,
        krakenPrice: Double?,
        coinbasePrice: Double?,
        binancePrice: Double?,
        bitstampPrice: Double?,
        divergencePercent: Double,
        totalTicks: Long
    ) {
        try {
            val entity = EngineCycleEntity(
                timestamp = timestamp,
                cycleNumber = cycleNumber,
                btcPrice = btcPrice,
                primaryExchange = primaryExchange,
                krakenPrice = krakenPrice,
                coinbasePrice = coinbasePrice,
                binancePrice = binancePrice,
                bitstampPrice = bitstampPrice,
                divergencePercent = divergencePercent,
                totalTicks = totalTicks
            )
            dao.insertCycle(entity)
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to record cycle: ${e.message}")
        }
    }

    suspend fun recordPrediction(record: PredictionRecord) {
        try {
            val entity = toEntity(record)
            dao.insertPrediction(entity)
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to record prediction: ${e.message}")
        }
    }

    suspend fun updatePredictionResolution(record: PredictionRecord) {
        try {
            dao.updatePredictionResolution(
                predictionId = record.predictionId,
                actualPrice = record.actualPrice,
                result = record.result,
                actualPrice30s = record.actualPrice30s,
                result30s = record.result30s,
                actualPrice90s = record.actualPrice90s,
                result90s = record.result90s,
                kalshiContractTicker = record.kalshiContractTicker,
                strikePrice = record.strikePrice,
                kalshiOrderId = record.kalshiOrderId,
                kalshiOrderStatus = record.kalshiOrderStatus,
                kalshiFilledCount = record.kalshiFilledCount,
                kalshiOrderPrice = record.kalshiOrderPrice,
                executionPrice = record.executionPrice
            )
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to update prediction resolution: ${e.message}")
        }
    }

    suspend fun updatePredictionResolution(
        predictionId: String,
        actualPrice: Double,
        result: String
    ) {
        try {
            dao.updatePredictionResult(predictionId, actualPrice, result)
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to update prediction resolution: ${e.message}")
        }
    }

    suspend fun recordBacktestResult(result: BacktestResult, horizon: Int = 30) {
        try {
            val entity = BacktestRecordEntity(
                timestamp = System.currentTimeMillis(),
                totalSamples = result.totalSamples,
                totalTrades = result.totalTrades,
                upPredictions = result.upPredictions,
                downPredictions = result.downPredictions,
                correctPredictions = result.correctPredictions,
                incorrectPredictions = result.incorrectPredictions,
                winRatePercent = result.winRatePercent,
                horizonSeconds = horizon
            )
            dao.insertBacktestRecord(entity)
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to record backtest result: ${e.message}")
        }
    }

    suspend fun recordCalibrations(
        attributions: List<FactorAttribution>,
        learningBias: Double,
        timestamp: Long = System.currentTimeMillis()
    ) {
        try {
            for (attr in attributions) {
                val entity = AdaptiveCalibrationEntity(
                    timestamp = timestamp,
                    factorName = attr.factorName,
                    activeCount = attr.totalTimesActive,
                    winRate = attr.winRate,
                    weightOffset = attr.suggestedWeightOffset,
                    learningBias = learningBias
                )
                dao.insertCalibration(entity)
            }
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to record calibrations: ${e.message}")
        }
    }

    fun getBacktestHistory(): Flow<List<BacktestRecordEntity>> {
        return dao.getBacktestHistory().catch { e ->
            SafeLog.e("EngineRepository", "Failed to stream backtest history: ${e.message}")
            emit(emptyList())
        }
    }

    suspend fun loadHistoricalPredictions(): List<PredictionRecord> {
        return try {
            val entities = dao.getAllPredictions()
            entities.map { toRecord(it) }
        } catch (e: Exception) {
            SafeLog.e("EngineRepository", "Failed to load historical predictions: ${e.message}")
            emptyList()
        }
    }

    companion object {
        fun toEntity(record: PredictionRecord): PredictionEntity {
            return PredictionEntity(
                predictionId = record.predictionId,
                timestamp = record.timestamp,
                decision = record.decision,
                score = record.score,
                strength = record.strength,
                currentPrice = record.currentPrice,
                predictedPrice = record.predictedPrice,
                predictionHorizon = record.predictionHorizon,
                maturityTimestamp = record.maturityTimestamp,
                actualPrice = record.actualPrice,
                result = record.result ?: "PENDING",
                calibratedScore = record.calibratedScore,
                // Complete IndicatorSnapshot
                ema9 = record.inputs.ema9,
                ema21 = record.inputs.ema21,
                rsi = record.inputs.rsi,
                momentum = record.inputs.momentum,
                velocity = record.inputs.velocity,
                acceleration = record.inputs.acceleration,
                volatility = record.inputs.volatility,
                volume = record.inputs.volume,
                volumeChange = record.inputs.volumeChange,
                buffer = record.inputs.buffer,
                bidAskSpread = record.inputs.bidAskSpread,
                exchangeAgreement = record.inputs.exchangeAgreement,
                formulaDisplay = record.inputs.formulaDisplay,
                // Settlement methodology & reference
                settlementReference = record.settlementReference,
                settlementMethodology = record.settlementMethodology,
                // 90s projection
                projectedPrice90s = record.projectedPrice90s,
                projectedDecision90s = record.projectedDecision90s,
                maturityTimestamp90s = record.maturityTimestamp90s,
                actualPrice90s = record.actualPrice90s,
                result90s = record.result90s,
                // Dedicated 30s fields
                actualPrice30s = record.actualPrice30s,
                result30s = record.result30s,
                // Source & market timestamps
                sourceExchange = record.sourceExchange,
                marketTimestamp = record.marketTimestamp,
                // Kalshi contract & execution lineage
                kalshiContractTicker = record.kalshiContractTicker,
                strikePrice = record.strikePrice,
                kalshiOrderId = record.kalshiOrderId,
                kalshiOrderStatus = record.kalshiOrderStatus,
                kalshiFilledCount = record.kalshiFilledCount,
                kalshiOrderPrice = record.kalshiOrderPrice,
                executionPrice = record.executionPrice
            )
        }

        fun toRecord(entity: PredictionEntity): PredictionRecord {
            val snapshot = IndicatorSnapshot(
                ema9 = entity.ema9,
                ema21 = entity.ema21,
                rsi = entity.rsi,
                momentum = entity.momentum,
                velocity = entity.velocity,
                acceleration = entity.acceleration,
                volatility = entity.volatility,
                volume = entity.volume,
                volumeChange = entity.volumeChange,
                buffer = entity.buffer,
                bidAskSpread = entity.bidAskSpread,
                exchangeAgreement = entity.exchangeAgreement,
                formulaDisplay = entity.formulaDisplay
            )
            return PredictionRecord(
                predictionId = entity.predictionId,
                timestamp = entity.timestamp,
                inputs = snapshot,
                decision = entity.decision,
                score = entity.score,
                strength = entity.strength,
                predictedPrice = entity.predictedPrice,
                currentPrice = entity.currentPrice,
                settlementReference = entity.settlementReference,
                settlementMethodology = entity.settlementMethodology,
                predictionHorizon = entity.predictionHorizon,
                maturityTimestamp = entity.maturityTimestamp,
                calibratedScore = entity.calibratedScore,
                actualPrice = entity.actualPrice,
                result = entity.result,
                projectedPrice90s = entity.projectedPrice90s,
                projectedDecision90s = entity.projectedDecision90s,
                actualPrice30s = entity.actualPrice30s,
                result30s = entity.result30s,
                maturityTimestamp90s = entity.maturityTimestamp90s,
                actualPrice90s = entity.actualPrice90s,
                result90s = entity.result90s,
                sourceExchange = entity.sourceExchange,
                marketTimestamp = entity.marketTimestamp,
                kalshiContractTicker = entity.kalshiContractTicker,
                strikePrice = entity.strikePrice,
                kalshiOrderId = entity.kalshiOrderId,
                kalshiOrderStatus = entity.kalshiOrderStatus,
                kalshiFilledCount = entity.kalshiFilledCount,
                kalshiOrderPrice = entity.kalshiOrderPrice,
                executionPrice = entity.executionPrice
            )
        }
    }
}
