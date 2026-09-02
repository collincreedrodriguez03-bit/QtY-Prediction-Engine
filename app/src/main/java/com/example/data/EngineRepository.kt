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
    }

    suspend fun recordPrediction(record: PredictionRecord) {
        val entity = PredictionEntity(
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
            ema9 = record.inputs.ema9,
            ema21 = record.inputs.ema21,
            rsi = record.inputs.rsi,
            momentum = record.inputs.momentum,
            velocity = record.inputs.velocity,
            acceleration = record.inputs.acceleration,
            volatility = record.inputs.volatility,
            volumeChange = record.inputs.volumeChange,
            buffer = record.inputs.buffer,
            formulaDisplay = record.inputs.formulaDisplay
        )
        dao.insertPrediction(entity)
    }

    suspend fun updatePredictionResolution(
        predictionId: String,
        actualPrice: Double,
        result: String
    ) {
        dao.updatePredictionResult(predictionId, actualPrice, result)
    }

    suspend fun recordBacktestResult(result: BacktestResult, horizon: Int = 30) {
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
    }

    suspend fun recordCalibrations(
        attributions: List<FactorAttribution>,
        learningBias: Double,
        timestamp: Long = System.currentTimeMillis()
    ) {
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
    }

    fun getBacktestHistory(): Flow<List<BacktestRecordEntity>> {
        return dao.getBacktestHistory()
    }

    suspend fun loadHistoricalPredictions(): List<PredictionRecord> {
        val entities = dao.getAllPredictions()
        return entities.map { entity ->
            val snapshot = IndicatorSnapshot(
                ema9 = entity.ema9,
                ema21 = entity.ema21,
                rsi = entity.rsi,
                momentum = entity.momentum,
                velocity = entity.velocity,
                acceleration = entity.acceleration,
                volatility = entity.volatility,
                volumeChange = entity.volumeChange,
                buffer = entity.buffer,
                formulaDisplay = entity.formulaDisplay
            )
            PredictionRecord(
                predictionId = entity.predictionId,
                timestamp = entity.timestamp,
                inputs = snapshot,
                decision = entity.decision,
                score = entity.score,
                strength = entity.strength,
                predictedPrice = entity.predictedPrice,
                currentPrice = entity.currentPrice,
                predictionHorizon = entity.predictionHorizon,
                maturityTimestamp = entity.maturityTimestamp,
                actualPrice = entity.actualPrice,
                result = entity.result
            )
        }
    }
}
