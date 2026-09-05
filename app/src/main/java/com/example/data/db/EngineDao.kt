package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EngineDao {

    // --- Cycles ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycle(cycle: EngineCycleEntity)

    @Query("SELECT * FROM engine_cycles ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentCycles(limit: Int): Flow<List<EngineCycleEntity>>

    @Query("SELECT COUNT(*) FROM engine_cycles")
    suspend fun getCycleCount(): Long

    // --- Predictions ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrediction(prediction: PredictionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPredictions(predictions: List<PredictionEntity>)

    @Update
    suspend fun updatePrediction(prediction: PredictionEntity)

    @Query("UPDATE predictions SET actualPrice = :actualPrice, result = :result WHERE predictionId = :predictionId")
    suspend fun updatePredictionResult(predictionId: String, actualPrice: Double, result: String)

    @Query("""
        UPDATE predictions SET 
            actualPrice = :actualPrice, 
            result = :result,
            actualPrice30s = :actualPrice30s,
            result30s = :result30s,
            actualPrice90s = :actualPrice90s,
            result90s = :result90s,
            kalshiContractTicker = COALESCE(:kalshiContractTicker, kalshiContractTicker),
            strikePrice = COALESCE(:strikePrice, strikePrice),
            kalshiOrderId = COALESCE(:kalshiOrderId, kalshiOrderId),
            kalshiOrderStatus = COALESCE(:kalshiOrderStatus, kalshiOrderStatus),
            kalshiFilledCount = COALESCE(:kalshiFilledCount, kalshiFilledCount),
            kalshiOrderPrice = COALESCE(:kalshiOrderPrice, kalshiOrderPrice),
            executionPrice = COALESCE(:executionPrice, executionPrice),
            kalshiClientOrderId = COALESCE(:kalshiClientOrderId, kalshiClientOrderId),
            resolutionTimestamp = COALESCE(:resolutionTimestamp, resolutionTimestamp),
            resolutionNotes = COALESCE(:resolutionNotes, resolutionNotes)
        WHERE predictionId = :predictionId
    """)
    suspend fun updatePredictionResolution(
        predictionId: String,
        actualPrice: Double?,
        result: String?,
        actualPrice30s: Double?,
        result30s: String?,
        actualPrice90s: Double?,
        result90s: String?,
        kalshiContractTicker: String? = null,
        strikePrice: Double? = null,
        kalshiOrderId: String? = null,
        kalshiOrderStatus: String? = null,
        kalshiFilledCount: Int? = null,
        kalshiOrderPrice: Int? = null,
        executionPrice: Double? = null,
        kalshiClientOrderId: String? = null,
        resolutionTimestamp: Long? = null,
        resolutionNotes: String? = null
    )

    @Query("SELECT * FROM predictions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPredictions(limit: Int): Flow<List<PredictionEntity>>

    @Query("SELECT * FROM predictions WHERE result != 'PENDING' AND result IS NOT NULL ORDER BY timestamp ASC")
    suspend fun getAllResolvedPredictions(): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE result = 'PENDING' OR result IS NULL ORDER BY timestamp ASC")
    suspend fun getPendingPredictions(): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE predictionId = :predictionId LIMIT 1")
    suspend fun getPredictionById(predictionId: String): PredictionEntity?

    @Query("SELECT * FROM predictions WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getPredictionsByTimeRange(startTime: Long, endTime: Long): List<PredictionEntity>

    @Query("DELETE FROM predictions WHERE timestamp < :cutoffTimestamp")
    suspend fun trimOldPredictions(cutoffTimestamp: Long): Int

    @Query("DELETE FROM engine_cycles WHERE timestamp < :cutoffTimestamp")
    suspend fun trimOldCycles(cutoffTimestamp: Long): Int

    @Query("SELECT * FROM predictions ORDER BY timestamp ASC")
    suspend fun getAllPredictions(): List<PredictionEntity>

    @Query("SELECT COUNT(*) FROM predictions")
    suspend fun getTotalPredictionsCount(): Long

    // --- Backtests ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBacktestRecord(backtest: BacktestRecordEntity)

    @Query("SELECT * FROM backtest_records ORDER BY timestamp DESC")
    fun getBacktestHistory(): Flow<List<BacktestRecordEntity>>

    @Query("SELECT * FROM backtest_records ORDER BY timestamp DESC")
    suspend fun getAllBacktestRecords(): List<BacktestRecordEntity>

    // --- Calibrations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalibration(calibration: AdaptiveCalibrationEntity)

    @Query("SELECT * FROM adaptive_calibrations ORDER BY timestamp DESC LIMIT 50")
    fun getRecentCalibrations(): Flow<List<AdaptiveCalibrationEntity>>
}
