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
import com.example.kalshi.KalshiAutomationEngine
import com.example.kalshi.KalshiVerificationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
    val performanceStats: LivePerformanceStats = LivePerformanceStats(),
    val rollingReferencePrice: Double? = null,
    val contractSettlementReference: Double? = null,
    val kalshiVerification: KalshiVerificationResult? = null,
    val isAutomationEnabled: Boolean = false,
    val kalshiContractTicker: String? = null,
    val kalshiValidationMessage: String? = null
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
    val repository: EngineRepository? = null,
    val kalshiAutomation: KalshiAutomationEngine? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private val cycleMutex = Mutex()

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
        if (!cycleMutex.tryLock()) {
            SafeLog.w("QtY_EngineLoop", "Previous cycle still executing; skipping overlapping cycle.")
            return null
        }
        try {
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
        val bitstampPoint = cachedSpots.find { it.exchange == "BITSTAMP" }

        // 2. [CONSOLIDATE & VERIFY] Multi-exchange Mathematical Consolidation
        val consolidatedState = consolidator.consolidate(cachedSpots, timestamp)
        val comparison = validator.validateCrossExchange(binancePoint, krakenPoint, coinbasePoint, bitstampPoint)

        val activePrice = if (consolidatedState.consolidatedPrice > 0.0) {
            consolidatedState.consolidatedPrice
        } else {
            binancePoint?.price ?: coinbasePoint?.price ?: krakenPoint?.price ?: bitstampPoint?.price ?: 0.0
        }

        // FAIL CLOSED: If valid real market data is unavailable, pause predictions
        if (activePrice <= 0.0) {
            _state.value = _state.value.copy(
                cycleCount = cycleCounter,
                sourceStatuses = dataFeed.sourceStatuses.value,
                totalTicks = dataFeed.getTotalTicks(),
                errorLog = "Awaiting valid market data (Predictions Paused)...",
                mathDisplay = "AWAITING REAL-TIME MARKET FEEDS..."
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

        // 3. [BUILD TIME SERIES] Add clean point per 2s cycle to rolling price history
        priceHistory.add(primaryPricePoint)

        val allPoints = priceHistory.getAll()

        // 4. [RESOLVE & LEARN] Resolve matured 30s predictions using exact 30-second observation
        val newlyResolved = performanceTracker.resolveMatured(
            currentPrice = activePrice,
            currentTimestamp = timestamp,
            priceHistory = allPoints
        )
        for (resolved in newlyResolved) {
            val resPrice = resolved.actualPrice
            val resResult = resolved.result ?: "PENDING"
            logger.updateResolvedRecord(
                predictionId = resolved.predictionId,
                actualPrice = resPrice,
                result = resResult,
                actualPrice90s = resolved.actualPrice90s,
                result90s = resolved.result90s,
                kalshiTicker = resolved.kalshiContractTicker,
                kalshiOrderId = resolved.kalshiOrderId,
                kalshiOrderStatus = resolved.kalshiOrderStatus,
                kalshiFilledCount = resolved.kalshiFilledCount,
                kalshiOrderPrice = resolved.kalshiOrderPrice
            )
            repository?.updatePredictionResolution(
                predictionId = resolved.predictionId,
                actualPrice = resPrice ?: 0.0,
                result = resResult
            )
        }

        // Rolling anchor prevents buffer saturation during prolonged sessions
        val rollingRef = if (allPoints.size >= 45) {
            allPoints.takeLast(45).map { it.price }.average()
        } else {
            allPoints.firstOrNull()?.price ?: activePrice
        }
        referencePrice = rollingRef

        // Recalculate Indicators with dynamically updated rolling anchor
        val snapshot = indicatorCalculator.computeSnapshot(
            points = allPoints,
            referencePrice = rollingRef,
            previousVelocity = previousVelocity,
            exchangeAgreement = consolidatedState.agreementStatus.name
        )
        previousVelocity = snapshot.velocity

        // Compute verified performance stats & factor attribution
        val perfStats = performanceTracker.computeStats(snapshot)
        val factorOffsets = perfStats.factorAttributions.associate { it.factorName to it.suggestedWeightOffset }

        // Determine 15-minute Kalshi contract defined settlement reference
        val contractSettlementRef = priceHistory.get15mContractSettlementReference(timestamp) ?: activePrice

        // 5. [WEIGH & PREDICT] Generate Directional Prediction targeting 15m Contract Settlement Reference
        val rawPrediction = predictionEngine.predict(
            currentPrice = activePrice,
            snapshot = snapshot,
            timestamp = timestamp,
            learningBias = perfStats.learningBiasAdjustment,
            factorOffsets = factorOffsets,
            settlementReference = contractSettlementRef
        )

        // FAIL CLOSED: If cross-exchange feeds severely disagree, do NOT trade
        val prediction = if (comparison.agreementStatus == com.example.data.ExchangeAgreementStatus.DISAGREEMENT) {
            rawPrediction.copy(
                decision = "NO-TRADE",
                strength = "CONFLICTED_FEEDS"
            )
        } else {
            rawPrediction
        }

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

        // 6b. [ORDER-BOOK VERIFICATION & AUTOMATION]
        // Strictly runs as independent verification / confirmation data (never alters predictionEngine)
        kalshiAutomation?.let { kal ->
            scope.launch {
                kal.onNewPrediction(prediction, activePrice, timestamp)
            }
        }

        // 7. [EMIT IMMUTABLE STATE]
        val recent = logger.getRecentPredictions(10)
        val allHistoryPrices = priceHistory.getAll().map { it.price }
        val kalState = kalshiAutomation?.state?.value

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
            mathDisplay = if (comparison.agreementStatus == com.example.data.ExchangeAgreementStatus.DISAGREEMENT) {
                "FEEDS CONFLICTED (>0.5% divergence) -> FAILING CLOSED TO NO-TRADE"
            } else {
                prediction.inputs.formulaDisplay
            },
            totalRecordedPredictions = logger.getAllPredictions().size,
            performanceStats = perfStats,
            rollingReferencePrice = rollingRef,
            contractSettlementReference = contractSettlementRef,
            kalshiVerification = kalState?.latestVerification,
            isAutomationEnabled = kalState?.isAutomationEnabled ?: false,
            kalshiContractTicker = kalState?.activeContract?.ticker,
            kalshiValidationMessage = kalState?.contractValidationMessage
        )

        return prediction
        } finally {
            cycleMutex.unlock()
        }
    }

    /**
     * Manually injects a price point (used for tests or offline backtesting).
     */
    fun processPricePoint(point: PricePoint): PredictionRecord {
        cycleCounter++
        priceHistory.add(point)

        val allPoints = priceHistory.getAll()

        // Resolve matured using authentic observation
        performanceTracker.resolveMatured(
            currentPrice = point.price,
            currentTimestamp = point.timestamp,
            priceHistory = allPoints
        )

        // Dynamically compute rolling anchor to prevent buffer saturation
        val rollingRef = if (allPoints.size >= 45) {
            allPoints.takeLast(45).map { it.price }.average()
        } else {
            allPoints.firstOrNull()?.price ?: point.price
        }
        referencePrice = rollingRef

        val snapshot = indicatorCalculator.computeSnapshot(
            points = allPoints,
            referencePrice = rollingRef,
            previousVelocity = previousVelocity
        )
        previousVelocity = snapshot.velocity

        val perfStats = performanceTracker.computeStats(snapshot)
        val factorOffsets = perfStats.factorAttributions.associate { it.factorName to it.suggestedWeightOffset }

        val contractSettlementRef = priceHistory.get15mContractSettlementReference(point.timestamp) ?: point.price

        val prediction = predictionEngine.predict(
            currentPrice = point.price,
            snapshot = snapshot,
            timestamp = point.timestamp,
            learningBias = perfStats.learningBiasAdjustment,
            factorOffsets = factorOffsets,
            settlementReference = contractSettlementRef
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
            performanceStats = perfStats,
            rollingReferencePrice = rollingRef,
            contractSettlementReference = contractSettlementRef
        )

        return prediction
    }
}
