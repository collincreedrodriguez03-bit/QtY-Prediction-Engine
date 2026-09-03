package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BtcDataFeed
import com.example.data.DataValidator
import com.example.data.EngineRepository
import com.example.data.JsonPredictionLogger
import com.example.data.PriceHistory
import com.example.data.SafeLog
import com.example.data.db.AppDatabase
import com.example.data.db.BacktestRecordEntity
import com.example.engine.BacktestResult
import com.example.engine.Backtester
import com.example.engine.EngineLoop
import com.example.engine.EngineState
import com.example.engine.IndicatorCalculator
import com.example.engine.PredictionEngine
import com.example.kalshi.KalshiAutomationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

data class CumulativeBacktestMetrics(
    val totalRuns: Int = 0,
    val totalSamples: Int = 0,
    val totalTrades: Int = 0,
    val correctPredictions: Int = 0,
    val incorrectPredictions: Int = 0,
    val winRatePercent: Double = 0.0,
    val historyList: List<BacktestRecordEntity> = emptyList()
)

data class MainUiState(
    val engineState: EngineState = EngineState(),
    val backtestResult: BacktestResult? = null,
    val cumulativeBacktest: CumulativeBacktestMetrics = CumulativeBacktestMetrics(),
    val isBacktesting: Boolean = false,
    val activeTab: Int = 0 // 0: LIVE ENGINE & GRAPHS, 1: ENGINE ROOM, 2: BACKTEST & LOGS
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logDir = File(application.filesDir, "predictions")
    val logger = JsonPredictionLogger(logDir)
    val priceHistory = PriceHistory(300)
    val indicatorCalc = IndicatorCalculator()
    val predictionEngine = PredictionEngine(predictionHorizonSeconds = 30)
    val dataFeed = BtcDataFeed()
    val validator = DataValidator()
    val database = AppDatabase.getDatabase(application)
    val repository = EngineRepository(database)
    val kalshiAutomation = KalshiAutomationEngine(priceHistory = priceHistory)

    val engineLoop = EngineLoop(
        dataFeed = dataFeed,
        validator = validator,
        priceHistory = priceHistory,
        indicatorCalculator = indicatorCalc,
        predictionEngine = predictionEngine,
        logger = logger,
        repository = repository,
        kalshiAutomation = kalshiAutomation
    )

    val backtester = Backtester(
        indicatorCalculator = indicatorCalc,
        predictionEngine = predictionEngine
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Start background Kalshi contract discovery and order-book sync
        kalshiAutomation.startSyncLoop()

        // Hydrate PerformanceTracker from permanent Room database on startup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val historical = repository.loadHistoricalPredictions()
                if (historical.isNotEmpty()) {
                    engineLoop.performanceTracker.loadFromHistory(historical)
                }
            } catch (e: Exception) {
                SafeLog.e("MainViewModel", "Error hydrating history: ${e.message}")
            }
        }

        // Collect EngineLoop state
        viewModelScope.launch {
            try {
                engineLoop.state.collect { loopState ->
                    _uiState.value = _uiState.value.copy(engineState = loopState)
                }
            } catch (e: Exception) {
                SafeLog.e("MainViewModel", "Error collecting engine state: ${e.message}")
            }
        }

        // Collect cumulative backtest history from Room database
        viewModelScope.launch {
            try {
                repository.getBacktestHistory()
                    .catch { e ->
                        SafeLog.e("MainViewModel", "Error in backtest history stream: ${e.message}")
                    }
                    .collect { history ->
                        val totalRuns = history.size
                        val totalSamples = history.sumOf { it.totalSamples }
                        val totalTrades = history.sumOf { it.totalTrades }
                        val correct = history.sumOf { it.correctPredictions }
                        val incorrect = history.sumOf { it.incorrectPredictions }
                        val winRate = if (totalTrades > 0) {
                            ((correct.toDouble() / totalTrades) * 1000.0).roundToInt() / 10.0
                        } else 0.0

                        _uiState.value = _uiState.value.copy(
                            cumulativeBacktest = CumulativeBacktestMetrics(
                                totalRuns = totalRuns,
                                totalSamples = totalSamples,
                                totalTrades = totalTrades,
                                correctPredictions = correct,
                                incorrectPredictions = incorrect,
                                winRatePercent = winRate,
                                historyList = history
                            )
                        )
                    }
            } catch (e: Exception) {
                SafeLog.e("MainViewModel", "Error observing backtest history: ${e.message}")
            }
        }

        // Automatically start the 2-second real-time loop
        engineLoop.start()
    }

    fun toggleEngineLoop() {
        if (_uiState.value.engineState.isRunning) {
            engineLoop.stop()
        } else {
            engineLoop.start()
        }
    }

    fun triggerSingleCycle() {
        viewModelScope.launch(Dispatchers.IO) {
            engineLoop.executeSingleCycle()
        }
    }

    fun runBacktestReplay(sampleCount: Int = 150) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isBacktesting = true)
            try {
                val realHistory = priceHistory.getAll()
                val replayData = if (realHistory.size >= 40) {
                    realHistory
                } else {
                    val candles = dataFeed.fetchRecent15mCandles()
                    if (candles.size >= 40) {
                        candles
                    } else {
                        emptyList()
                    }
                }

                // FAIL CLOSED: Never produce metrics or predictions from synthetic data
                if (replayData.size < 40) {
                    SafeLog.w("MainViewModel", "Insufficient authentic market data for backtest (available: ${replayData.size}, required: 40). Failing closed without synthetic fallback.")
                    _uiState.value = _uiState.value.copy(
                        isBacktesting = false,
                        engineState = _uiState.value.engineState.copy(
                            errorLog = "Backtest halted: Insufficient authentic market data (min 40 real observations required). Synthetic data disabled."
                        )
                    )
                    return@launch
                }

                val result = backtester.runBacktest(replayData)

                // Save backtest permanently to Room database
                repository.recordBacktestResult(result, 30)

                // Auto-navigate to BACKTEST tab (tab index 2)
                _uiState.value = _uiState.value.copy(
                    backtestResult = result,
                    isBacktesting = false,
                    activeTab = 2
                )
            } catch (e: Exception) {
                SafeLog.e("MainViewModel", "Backtest execution failed: ${e.message}")
                _uiState.value = _uiState.value.copy(isBacktesting = false)
            }
        }
    }

    fun setActiveTab(tab: Int) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun toggleAutomation() {
        val current = _uiState.value.engineState.isAutomationEnabled
        kalshiAutomation.toggleAutomation(!current)
        _uiState.value = _uiState.value.copy(
            engineState = _uiState.value.engineState.copy(
                isAutomationEnabled = !current
            )
        )
    }

    fun getExportedJson(limit: Int = 10): String {
        return logger.exportFormattedJson(limit)
    }

    override fun onCleared() {
        super.onCleared()
        kalshiAutomation.stopSyncLoop()
        engineLoop.stop()
    }
}
