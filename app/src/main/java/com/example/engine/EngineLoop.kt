package com.example.engine

import com.example.data.BtcDataFeed
import com.example.data.DataValidator
import com.example.data.ExchangeComparison
import com.example.data.JsonPredictionLogger
import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.data.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real-time state emitted by the 2-second engine loop.
 */
data class EngineState(
    val isRunning: Boolean = false,
    val cycleCount: Long = 0L,
    val latestPrice: Double = 0.0,
    val latestTimestamp: Long = 0L,
    val latestExchange: String = "BINANCE",
    val krakenPrice: Double? = null,
    val exchangeComparison: ExchangeComparison? = null,
    val latestSnapshot: IndicatorSnapshot? = null,
    val latestPrediction: PredictionRecord? = null,
    val recentPredictions: List<PredictionRecord> = emptyList(),
    val recentPrices: List<Double> = emptyList(),
    val errorLog: String? = null,
    val mathDisplay: String = "INITIALIZING...",
    val totalRecordedPredictions: Int = 0
)

/**
 * 2-Second Prediction Loop Orchestrator.
 * Clean, non-blocking coroutine loop executing the core cycle:
 * FETCH -> VALIDATE -> COMPUTE INDICATORS -> PREDICT -> LOG -> EMIT
 */
class EngineLoop(
    private val dataFeed: BtcDataFeed = BtcDataFeed(),
    private val validator: DataValidator = DataValidator(),
    val priceHistory: PriceHistory = PriceHistory(300),
    val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    val predictionEngine: PredictionEngine = PredictionEngine(),
    val logger: JsonPredictionLogger = JsonPredictionLogger()
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var previousVelocity = 0.0
    private var referencePrice: Double? = null
    private var cycleCounter = 0L

    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            _state.value = _state.value.copy(isRunning = true, errorLog = null)

            while (isActive) {
                val cycleStartTime = System.currentTimeMillis()
                try {
                    executeSingleCycle(cycleStartTime)
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        errorLog = "Cycle error: ${e.message}"
                    )
                }

                // Compute exact sleep interval to maintain consistent 2000ms cycle cadence
                val elapsed = System.currentTimeMillis() - cycleStartTime
                val sleepTime = (2000L - elapsed).coerceAtLeast(100L)
                delay(sleepTime)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(isRunning = false)
    }

    /**
     * Executes a single 2-second cycle synchronously within coroutine.
     * Can also be invoked directly by tests or backtesters.
     */
    suspend fun executeSingleCycle(timestamp: Long = System.currentTimeMillis()): PredictionRecord? {
        cycleCounter++

        // 1. Fetch primary price (Binance) and secondary price (Kraken)
        val binancePoint = dataFeed.fetchBinancePrice()
        val krakenPoint = dataFeed.fetchKrakenPrice()

        // 2. Validate Binance price
        val valResult = validator.validate(binancePoint, timestamp)
        val validPrimaryPoint = when (valResult) {
            is ValidationResult.Valid -> valResult.pricePoint
            is ValidationResult.Invalid -> {
                // Try fallback to Kraken if Binance failed
                val krakenVal = validator.validate(krakenPoint, timestamp)
                if (krakenVal is ValidationResult.Valid) krakenVal.pricePoint else null
            }
        }

        if (validPrimaryPoint == null) {
            _state.value = _state.value.copy(
                cycleCount = cycleCounter,
                errorLog = "Failed to fetch valid price from Binance/Kraken",
                mathDisplay = "DATA FEED OFFLINE - RETRYING..."
            )
            return null
        }

        // 3. Cross-Exchange Validation
        val comparison = validator.validateCrossExchange(binancePoint, krakenPoint)

        // 4. Update Rolling Price History
        if (referencePrice == null) {
            referencePrice = validPrimaryPoint.price
        }
        priceHistory.add(validPrimaryPoint)

        // 5. Recalculate Indicators
        val snapshot = indicatorCalculator.computeSnapshot(
            points = priceHistory.getAll(),
            referencePrice = referencePrice,
            previousVelocity = previousVelocity,
            exchangeAgreement = comparison.agreementStatus.name
        )
        previousVelocity = snapshot.velocity

        // 6. Generate UP/DOWN Prediction
        val prediction = predictionEngine.predict(
            currentPrice = validPrimaryPoint.price,
            snapshot = snapshot,
            timestamp = validPrimaryPoint.timestamp
        )

        // 7. Record Prediction to JSON file & memory
        logger.log(prediction)

        // 8. Update State Flow
        val recent = logger.getRecentPredictions(10)
        val allHistoryPrices = priceHistory.getAll().map { it.price }
        _state.value = EngineState(
            isRunning = true,
            cycleCount = cycleCounter,
            latestPrice = validPrimaryPoint.price,
            latestTimestamp = validPrimaryPoint.timestamp,
            latestExchange = validPrimaryPoint.exchange,
            krakenPrice = krakenPoint?.price,
            exchangeComparison = comparison,
            latestSnapshot = snapshot,
            latestPrediction = prediction,
            recentPredictions = recent,
            recentPrices = allHistoryPrices,
            errorLog = null,
            mathDisplay = prediction.inputs.formulaDisplay,
            totalRecordedPredictions = logger.getAllPredictions().size
        )

        return prediction
    }

    /**
     * Manually injects a price point (used for backtesting or offline simulation).
     */
    fun processPricePoint(point: PricePoint): PredictionRecord {
        cycleCounter++
        if (referencePrice == null) {
            referencePrice = point.price
        }
        priceHistory.add(point)

        val snapshot = indicatorCalculator.computeSnapshot(
            points = priceHistory.getAll(),
            referencePrice = referencePrice,
            previousVelocity = previousVelocity
        )
        previousVelocity = snapshot.velocity

        val prediction = predictionEngine.predict(
            currentPrice = point.price,
            snapshot = snapshot,
            timestamp = point.timestamp
        )

        logger.log(prediction)
        return prediction
    }
}
