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

    @Query("SELECT * FROM predictions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPredictions(limit: Int): Flow<List<PredictionEntity>>

    @Query("SELECT * FROM predictions WHERE result != 'PENDING' AND result IS NOT NULL ORDER BY timestamp ASC")
    suspend fun getAllResolvedPredictions(): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE result = 'PENDING' OR result IS NULL ORDER BY timestamp ASC")
    suspend fun getPendingPredictions(): List<PredictionEntity>

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
