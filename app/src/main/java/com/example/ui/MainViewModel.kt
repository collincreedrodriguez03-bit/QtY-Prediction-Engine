package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BtcDataFeed
import com.example.data.DataValidator
import com.example.data.JsonPredictionLogger
import com.example.data.PriceHistory
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

data class MainUiState(
    val engineState: EngineState = EngineState(),
    val backtestResult: BacktestResult? = null,
    val isBacktesting: Boolean = false,
    val activeTab: Int = 0 // 0: Live Engine, 1: Backtest CCXT, 2: JSON Feed
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logDir = File(application.filesDir, "predictions")
    val logger = JsonPredictionLogger(logDir)
    val priceHistory = PriceHistory(300)
    val indicatorCalc = IndicatorCalculator()
    val predictionEngine = PredictionEngine()
    val dataFeed = BtcDataFeed()
    val validator = DataValidator()

    val engineLoop = EngineLoop(
        dataFeed = dataFeed,
        validator = validator,
        priceHistory = priceHistory,
        indicatorCalculator = indicatorCalc,
        predictionEngine = predictionEngine,
        logger = logger
    )

    val backtester = Backtester(
        indicatorCalculator = indicatorCalc,
        predictionEngine = predictionEngine
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Collect EngineLoop state
        viewModelScope.launch {
            engineLoop.state.collect { loopState ->
                _uiState.value = _uiState.value.copy(engineState = loopState)
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
            _uiState.value = _uiState.value.copy(
                backtestResult = result,
                isBacktesting = false,
                activeTab = 1
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
