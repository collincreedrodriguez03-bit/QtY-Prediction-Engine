package com.example.engine

import com.example.data.BtcDataFeed
import com.example.data.ConsolidatedMarketState
import com.example.data.DataSourceStatus
import com.example.data.DataValidator
import com.example.data.ExchangeComparison
import com.example.data.JsonPredictionLogger
import com.example.data.MarketDataConsolidator
import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.data.SafeLog
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
 * Real-time state emitted by the ~2-second engine loop.
 */
data class EngineState(
    val isRunning: Boolean = false,
    val cycleCount: Long = 0L,
    val latestPrice: Double = 0.0,
    val latestTimestamp: Long = 0L,
    val latestExchange: String = "BINANCE",
    val krakenPrice: Double? = null,
    val coinbasePrice: Double? = null,
    val exchangeComparison: ExchangeComparison? = null,
    val consolidatedMarketState: ConsolidatedMarketState? = null,
    val sourceStatuses: Map<String, DataSourceStatus> = emptyMap(),
    val latestSnapshot: IndicatorSnapshot? = null,
    val latestPrediction: PredictionRecord? = null,
    val recentPredictions: List<PredictionRecord> = emptyList(),
    val recentPrices: List<Double> = emptyList(),
    val errorLog: String? = null,
    val mathDisplay: String = "INITIALIZING...",
    val totalRecordedPredictions: Int = 0,
    val performanceStats: LivePerformanceStats = LivePerformanceStats()
)

/**
 * ~2-Second Prediction Loop Orchestrator.
 * Clean, non-blocking coroutine loop executing the core quantitative cycle:
 * RECEIVE (Multi-Exchange) -> CONSOLIDATE -> VERIFY -> BUILD -> WEIGH -> PREDICT (60s) -> PROJECT -> RESOLVE & LEARN -> REPEAT
 */
class EngineLoop(
    val dataFeed: BtcDataFeed = BtcDataFeed(),
    private val validator: DataValidator = DataValidator(),
    val consolidator: MarketDataConsolidator = MarketDataConsolidator(),
    val priceHistory: PriceHistory = PriceHistory(300),
    val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    val predictionEngine: PredictionEngine = PredictionEngine(),
    val performanceTracker: PerformanceTracker = PerformanceTracker(),
    val logger: JsonPredictionLogger = JsonPredictionLogger()
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private var tickCollectorJob: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var previousVelocity = 0.0
    private var referencePrice: Double? = null
    private var cycleCounter = 0L

    fun start() {
        if (job?.isActive == true) return

        // Start background multi-exchange streaming
        dataFeed.startStreaming()

        // Fast tick observer for high-frequency price history updates
        tickCollectorJob = scope.launch {
            dataFeed.tickFlow.collect { tick ->
                if (tick.price > 0.0) {
                    priceHistory.add(tick)
                    val currentPrices = priceHistory.getAll().map { it.price }
                    _state.value = _state.value.copy(
                        latestPrice = tick.price,
                        latestTimestamp = tick.timestamp,
                        latestExchange = tick.exchange,
                        recentPrices = currentPrices
                    )
                }
            }
        }

        // Core 2.0-second Engine Heartbeat Loop
        job = scope.launch {
            _state.value = _state.value.copy(isRunning = true, errorLog = null)

            while (isActive) {
                val cycleStartTime = System.currentTimeMillis()
                try {
                    executeSingleCycle(cycleStartTime)
                } catch (e: Exception) {
                    SafeLog.e("QtY_EngineLoop", "Cycle execution failed: ${e.message}")
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
        tickCollectorJob?.cancel()
        tickCollectorJob = null
        dataFeed.stopStreaming()
        _state.value = _state.value.copy(isRunning = false)
    }

    /**
     * Executes a single ~2-second quantitative prediction cycle synchronously within coroutine.
     */
    suspend fun executeSingleCycle(timestamp: Long = System.currentTimeMillis()): PredictionRecord? {
        cycleCounter++

        // 1. [RECEIVE & POLL] Fetch all available spot feeds (Binance, Coinbase, Kraken)
        val binancePoint = dataFeed.fetchBinancePrice()
        val krakenPoint = dataFeed.fetchKrakenPrice()
        val coinbasePoint = dataFeed.fetchCoinbasePrice()

        val candidateSpotPoints = mutableListOf<PricePoint>()
        binancePoint?.let { candidateSpotPoints.add(it) }
        krakenPoint?.let { candidateSpotPoints.add(it) }
        coinbasePoint?.let { candidateSpotPoints.add(it) }

        // Also incorporate any other cached active spot ticks
        val cachedSpots = dataFeed.getLatestSpotPoints()
        for (spot in cachedSpots) {
            if (candidateSpotPoints.none { it.exchange == spot.exchange }) {
                candidateSpotPoints.add(spot)
            }
        }

        // 2. [CONSOLIDATE & VERIFY] Multi-source Mathematical Consolidation
        val consolidatedState = consolidator.consolidate(candidateSpotPoints, timestamp)
        val comparison = validator.validateCrossExchange(binancePoint, krakenPoint, coinbasePoint)

        val activePrice = if (consolidatedState.consolidatedPrice > 0.0) {
            consolidatedState.consolidatedPrice
        } else {
            binancePoint?.price ?: krakenPoint?.price ?: coinbasePoint?.price ?: 0.0
        }

        if (activePrice <= 0.0) {
            _state.value = _state.value.copy(
                cycleCount = cycleCounter,
                sourceStatuses = dataFeed.sourceStatuses.value,
                errorLog = "Failed to obtain valid market price from connected spot exchanges",
                mathDisplay = "DATA FEEDS OFFLINE - RECONNECTING..."
            )
            return null
        }

        val primaryExchange = if (consolidatedState.activeSpotFeeds.size > 1) "CONSOLIDATED SPOT" else (candidateSpotPoints.firstOrNull()?.exchange ?: "BINANCE")
        val primaryPricePoint = PricePoint(
            price = activePrice,
            timestamp = timestamp,
            exchange = primaryExchange,
            volume = candidateSpotPoints.sumOf { it.volume }.coerceAtLeast(1.0)
        )

        // 3. [BUILD] Update Rolling Price History
        if (referencePrice == null) {
            referencePrice = activePrice
        }
        priceHistory.add(primaryPricePoint)

        // 4. [RESOLVE & LEARN] Resolve matured 60s predictions against actual market price
        val newlyResolved = performanceTracker.resolveMatured(
            currentPrice = activePrice,
            currentTimestamp = timestamp
        )
        for (resolved in newlyResolved) {
            logger.updateResolvedRecord(
                predictionId = resolved.predictionId,
                actualPrice = resolved.actualPrice ?: activePrice,
                result = resolved.result ?: "PENDING"
            )
        }

        // Recalculate Indicators
        val snapshot = indicatorCalculator.computeSnapshot(
            points = priceHistory.getAll(),
            referencePrice = referencePrice,
            previousVelocity = previousVelocity,
            exchangeAgreement = consolidatedState.agreementStatus.name
        )
        previousVelocity = snapshot.velocity

        // Compute verified performance stats & factor attribution
        val perfStats = performanceTracker.computeStats(snapshot)
        val factorOffsets = perfStats.factorAttributions.associate { it.factorName to it.suggestedWeightOffset }

        // 5. [WEIGH & PREDICT] Generate 60s Directional Prediction
        val prediction = predictionEngine.predict(
            currentPrice = activePrice,
            snapshot = snapshot,
            timestamp = timestamp,
            learningBias = perfStats.learningBiasAdjustment,
            factorOffsets = factorOffsets
        )

        // 6. [RECORD & REGISTER]
        performanceTracker.registerPrediction(prediction)
        logger.log(prediction)

        // 7. [EMIT STATE]
        val recent = logger.getRecentPredictions(10)
        val allHistoryPrices = priceHistory.getAll().map { it.price }

        _state.value = EngineState(
            isRunning = true,
            cycleCount = cycleCounter,
            latestPrice = activePrice,
            latestTimestamp = timestamp,
            latestExchange = primaryExchange,
            krakenPrice = krakenPoint?.price,
            coinbasePrice = coinbasePoint?.price,
            exchangeComparison = comparison,
            consolidatedMarketState = consolidatedState,
            sourceStatuses = dataFeed.sourceStatuses.value,
            latestSnapshot = snapshot,
            latestPrediction = prediction,
            recentPredictions = recent,
            recentPrices = allHistoryPrices,
            errorLog = null,
            mathDisplay = prediction.inputs.formulaDisplay,
            totalRecordedPredictions = logger.getAllPredictions().size,
            performanceStats = perfStats
        )

        return prediction
    }

    /**
     * Manually injects a price point (used for tests or offline backtesting).
     */
    fun processPricePoint(point: PricePoint): PredictionRecord {
        cycleCounter++
        if (referencePrice == null) {
            referencePrice = point.price
        }
        priceHistory.add(point)

        // Resolve matured
        performanceTracker.resolveMatured(point.price, point.timestamp)

        val snapshot = indicatorCalculator.computeSnapshot(
            points = priceHistory.getAll(),
            referencePrice = referencePrice,
            previousVelocity = previousVelocity
        )
        previousVelocity = snapshot.velocity

        val perfStats = performanceTracker.computeStats(snapshot)
        val factorOffsets = perfStats.factorAttributions.associate { it.factorName to it.suggestedWeightOffset }

        val prediction = predictionEngine.predict(
            currentPrice = point.price,
            snapshot = snapshot,
            timestamp = point.timestamp,
            learningBias = perfStats.learningBiasAdjustment,
            factorOffsets = factorOffsets
        )

        performanceTracker.registerPrediction(prediction)
        logger.log(prediction)
        return prediction
    }
}
