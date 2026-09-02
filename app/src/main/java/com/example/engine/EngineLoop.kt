package com.example.engine

import com.example.data.BtcDataFeed
import com.example.data.ConsolidatedMarketState
import com.example.data.DataSourceStatus
import com.example.data.DataValidator
import com.example.data.EngineRepository
import com.example.data.ExchangeComparison
import com.example.data.JsonPredictionLogger
import com.example.data.MarketDataConsolidator
import com.example.data.PriceHistory
import com.example.data.PricePoint
import com.example.data.SafeLog
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
    val totalTicks: Long = 0L,
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
 * RECEIVE (Spot WebSockets) -> CONSOLIDATE -> VERIFY -> BUILD -> WEIGH -> PREDICT (30s) -> PROJECT -> RESOLVE & LEARN -> REPEAT
 */
class EngineLoop(
    val dataFeed: BtcDataFeed = BtcDataFeed(),
    private val validator: DataValidator = DataValidator(),
    val consolidator: MarketDataConsolidator = MarketDataConsolidator(),
    val priceHistory: PriceHistory = PriceHistory(300),
    val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    val predictionEngine: PredictionEngine = PredictionEngine(predictionHorizonSeconds = 30),
    val performanceTracker: PerformanceTracker = PerformanceTracker(),
    val logger: JsonPredictionLogger = JsonPredictionLogger(),
    val repository: EngineRepository? = null
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

        // Start background multi-exchange WebSockets
        dataFeed.startStreaming()

        // Fetch authentic 15-minute real market candles on launch if priceHistory is empty
        scope.launch {
            if (priceHistory.isEmpty()) {
                val historicalCandles = dataFeed.fetchRecent15mCandles()
                if (historicalCandles.isNotEmpty()) {
                    priceHistory.addAll(historicalCandles)
                    if (referencePrice == null && historicalCandles.isNotEmpty()) {
                        referencePrice = historicalCandles.first().price
                    }
                    _state.value = _state.value.copy(
                        recentPrices = priceHistory.getPrices(),
                        latestPrice = historicalCandles.last().price
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
                val sleepTime = (2000L - elapsed).coerceAtLeast(200L)
                delay(sleepTime)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        dataFeed.stopStreaming()
        _state.value = _state.value.copy(isRunning = false)
    }

    /**
     * Executes a single ~2-second quantitative prediction cycle.
     */
    suspend fun executeSingleCycle(timestamp: Long = System.currentTimeMillis()): PredictionRecord? {
        cycleCounter++

        // 1. [GATHER SPOT QUOTES] Retrieve latest spot points from streaming WebSockets
        val cachedSpots = dataFeed.getLatestSpotPoints().toMutableList()

        // If WebSockets haven't populated yet, fetch via fast REST
        if (cachedSpots.isEmpty()) {
            dataFeed.fetchBinancePrice()?.let { cachedSpots.add(it) }
            dataFeed.fetchCoinbasePrice()?.let { cachedSpots.add(it) }
            dataFeed.fetchKrakenPrice()?.let { cachedSpots.add(it) }
        }

        val binancePoint = cachedSpots.find { it.exchange == "BINANCE" }
        val krakenPoint = cachedSpots.find { it.exchange == "KRAKEN" }
        val coinbasePoint = cachedSpots.find { it.exchange == "COINBASE" }

        // 2. [CONSOLIDATE & VERIFY] Multi-exchange Mathematical Consolidation
        val consolidatedState = consolidator.consolidate(cachedSpots, timestamp)
        val comparison = validator.validateCrossExchange(binancePoint, krakenPoint, coinbasePoint)

        val activePrice = if (consolidatedState.consolidatedPrice > 0.0) {
            consolidatedState.consolidatedPrice
        } else {
            binancePoint?.price ?: coinbasePoint?.price ?: krakenPoint?.price ?: (if (priceHistory.size() > 0) priceHistory.getLatest()?.price ?: 0.0 else 0.0)
        }

        if (activePrice <= 0.0) {
            _state.value = _state.value.copy(
                cycleCount = cycleCounter,
                sourceStatuses = dataFeed.sourceStatuses.value,
                totalTicks = dataFeed.getTotalTicks(),
                errorLog = "Connecting to spot exchanges...",
                mathDisplay = "CONNECTING SPOT EXCHANGES..."
            )
            return null
        }

        val primaryExchange = if (consolidatedState.activeSpotFeeds.size > 1) "CONSOLIDATED SPOT" else (cachedSpots.firstOrNull()?.exchange ?: "BINANCE")
        val primaryPricePoint = PricePoint(
            price = activePrice,
            timestamp = timestamp,
            exchange = primaryExchange,
            volume = cachedSpots.sumOf { it.volume }.coerceAtLeast(1.0)
        )

        // 3. [BUILD TIME SERIES] Add one clean point per 2s cycle to rolling price history
        if (referencePrice == null) {
            referencePrice = activePrice
        }
        priceHistory.add(primaryPricePoint)

        // 4. [RESOLVE & LEARN] Resolve matured 30s predictions against actual market price
        val newlyResolved = performanceTracker.resolveMatured(
            currentPrice = activePrice,
            currentTimestamp = timestamp
        )
        for (resolved in newlyResolved) {
            val resPrice = resolved.actualPrice ?: activePrice
            val resResult = resolved.result ?: "PENDING"
            logger.updateResolvedRecord(
                predictionId = resolved.predictionId,
                actualPrice = resPrice,
                result = resResult
            )
            repository?.updatePredictionResolution(
                predictionId = resolved.predictionId,
                actualPrice = resPrice,
                result = resResult
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

        // 5. [WEIGH & PREDICT] Generate 30s Directional Prediction
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
        repository?.recordPrediction(prediction)
        repository?.recordCycle(
            timestamp = timestamp,
            cycleNumber = cycleCounter,
            btcPrice = activePrice,
            primaryExchange = primaryExchange,
            krakenPrice = krakenPoint?.price,
            coinbasePrice = coinbasePoint?.price,
            binancePrice = binancePoint?.price,
            bitstampPrice = cachedSpots.find { it.exchange == "BITSTAMP" }?.price,
            divergencePercent = consolidatedState.divergencePercent,
            totalTicks = dataFeed.getTotalTicks()
        )
        if (perfStats.factorAttributions.isNotEmpty()) {
            repository?.recordCalibrations(
                attributions = perfStats.factorAttributions,
                learningBias = perfStats.learningBiasAdjustment,
                timestamp = timestamp
            )
        }

        // 7. [EMIT IMMUTABLE STATE]
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
            totalTicks = dataFeed.getTotalTicks(),
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

        val recent = logger.getRecentPredictions(10)
        val allHistoryPrices = priceHistory.getAll().map { it.price }

        _state.value = EngineState(
            isRunning = true,
            cycleCount = cycleCounter,
            latestPrice = point.price,
            latestTimestamp = point.timestamp,
            latestExchange = point.exchange,
            latestSnapshot = snapshot,
            latestPrediction = prediction,
            recentPredictions = recent,
            recentPrices = allHistoryPrices,
            mathDisplay = prediction.inputs.formulaDisplay,
            totalRecordedPredictions = logger.getAllPredictions().size,
            performanceStats = perfStats
        )

        return prediction
    }
}
