package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BtcDataFeed
import com.example.data.DataValidator
import com.example.data.EngineRepository
import com.example.data.JsonPredictionLogger
import com.example.data.PriceHistory
import com.example.data.db.AppDatabase
import com.example.data.db.BacktestRecordEntity
import com.example.engine.BacktestResult
import com.example.engine.Backtester
import com.example.engine.EngineLoop
import com.example.engine.EngineState
import com.example.engine.IndicatorCalculator
import com.example.engine.PredictionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val engineLoop = EngineLoop(
        dataFeed = dataFeed,
        validator = validator,
        priceHistory = priceHistory,
        indicatorCalculator = indicatorCalc,
        predictionEngine = predictionEngine,
        logger = logger,
        repository = repository
    )

    val backtester = Backtester(
        indicatorCalculator = indicatorCalc,
        predictionEngine = predictionEngine
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Hydrate PerformanceTracker from permanent Room database on startup
        viewModelScope.launch(Dispatchers.IO) {
            val historical = repository.loadHistoricalPredictions()
            if (historical.isNotEmpty()) {
                engineLoop.performanceTracker.loadFromHistory(historical)
            }
        }

        // Collect EngineLoop state
        viewModelScope.launch {
            engineLoop.state.collect { loopState ->
                _uiState.value = _uiState.value.copy(engineState = loopState)
            }
        }

        // Collect cumulative backtest history from Room database
        viewModelScope.launch {
            repository.getBacktestHistory().collect { history ->
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
            val syntheticData = backtester.generateSyntheticHistoricalData(
                startPrice = if (_uiState.value.engineState.latestPrice > 0) _uiState.value.engineState.latestPrice else 90000.0,
                count = sampleCount
            )
            val result = backtester.runBacktest(syntheticData)

            // Save backtest permanently to Room database
            repository.recordBacktestResult(result, 30)

            // Auto-navigate to BACKTEST tab (tab index 2)
            _uiState.value = _uiState.value.copy(
                backtestResult = result,
                isBacktesting = false,
                activeTab = 2
            )
        }
    }

    fun setActiveTab(tab: Int) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun getExportedJson(limit: Int = 10): String {
        return logger.exportFormattedJson(limit)
    }

    override fun onCleared() {
        super.onCleared()
        engineLoop.stop()
    }
}
