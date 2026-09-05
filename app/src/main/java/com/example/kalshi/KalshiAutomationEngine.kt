package com.example.kalshi

import com.example.data.PriceHistory
import com.example.data.SafeLog
import com.example.engine.PredictionRecord
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Kalshi Automated Trading Execution Engine.
 *
 * Core Mandates:
 * 1. Connect authenticated user account.
 * 2. Identify active BTC 15-minute contract (series KXBTC15M).
 * 3. Connect existing QtY predictions to execution.
 * 4. Preserve BOTH 30-second and 90-second predictions.
 * 5. Validate correct Kalshi contract and settlement methodology before trading.
 * 6. Implement order submission, fill detection, position tracking, balance checking, and order status handling.
 * 7. Prevent duplicate orders (via contract ID deduplication + idempotency key).
 * 8. Enforce configured small trade-size limit (default: 1 contract, max 5).
 * 9. Automation MUST be OFF by default.
 * 10. OFF = absolutely no live orders.
 * 11. Invalid/stale data, NO-TRADE, API errors, or ambiguous contracts = FAIL CLOSED.
 * 12. Switching automation OFF immediately prevents new orders.
 */
class KalshiAutomationEngine(
    val apiClient: KalshiApiClient = KalshiApiClient(),
    val priceHistory: PriceHistory,
    val tradeSizeLimit: Int = 1, // Strict small trade-size limit
    val riskEngine: KalshiRiskEngine = KalshiRiskEngine(),
    val executionStore: KalshiExecutionStore = InMemoryKalshiExecutionStore(),
    val fillTimeoutMs: Long = 10_000L // Explicit fill timeout (10 seconds)
) {
    companion object {
        private const val TAG = "KalshiAutomationEngine"
        const val MAX_CONTRACTS_PER_ORDER = 5
        private const val ORDER_COOLDOWN_MS = 60_000L // Min 60s between orders on the same contract

        /**
         * Minimum required statistical edge to justify automated order placement:
         * Edge = (model_probability - executable_market_probability).
         *
         * Documented Rationale:
         * In fast 15-minute binary markets, a minimum edge threshold of 2.5% (0.025) is strictly required:
         * 1. Exchange taker fees on Kalshi are approximately 1.5% to 2.0% of notional (~1-2¢ per contract).
         * 2. Statistical calibration error margin accounts for empirical confidence limits.
         * 3. Directional agreement alone is NOT sufficient — if market already prices YES at 80¢ and model
         *    predicts UP with 75% probability, buying YES has negative expected value (-5% edge).
         * If calculated edge < MIN_EXECUTABLE_EDGE, the engine fails closed with NO-TRADE.
         */
        const val MIN_EXECUTABLE_EDGE = 0.025 // 2.5% minimum required statistical edge
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var syncJob: Job? = null

    // Automation state - isAutomationEnabled is FALSE by default
    private val _state = MutableStateFlow(KalshiAutomationState(isAutomationEnabled = false))
    val state: StateFlow<KalshiAutomationState> = _state.asStateFlow()

    // Execution generation token to protect against already-running coroutines when toggled OFF
    private val executionGeneration = AtomicLong(0L)

    // Mutex to strictly prevent concurrent execution / order submission races
    private val executionMutex = Mutex()

    // Deduplication tracking: contractTicker -> lastOrderTimestamp
    private val tradedContracts = ConcurrentHashMap<String, Long>()
    private val submittedClientOrderIds = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            hydrateFromStore()
        }
    }

    /**
     * Hydrates risk and order state from durable store upon startup.
     */
    suspend fun hydrateFromStore() {
        try {
            riskEngine.hydrateFromLedger()
            val clientIds = executionStore.getAllClientOrderIds()
            submittedClientOrderIds.addAll(clientIds)
            val activeOrders = executionStore.getActiveOrders()
            for (order in activeOrders) {
                tradedContracts[order.ticker] = order.placedTimestamp
            }
            _state.value = _state.value.copy(activeOrderRecords = activeOrders)
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to hydrate from execution store: ${e.message}")
        }
    }

    fun invalidateExecutionGeneration() {
        executionGeneration.incrementAndGet()
    }

    fun getExecutionGenerationForTesting(): Long = executionGeneration.get()

    fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                try {
                    syncMarketAndAccount()
                } catch (e: Exception) {
                    SafeLog.e(TAG, "Sync loop error: ${e.message}")
                }
                delay(10_000L) // Refresh contract & balance every 10s
            }
        }
    }

    fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * User toggles automation ON or OFF via the single UI button.
     * When toggled OFF, immediately halts any pending execution.
     */
    fun toggleAutomation(enabled: Boolean) {
        if (!enabled) {
            invalidateExecutionGeneration()
            _state.value = _state.value.copy(
                isAutomationEnabled = false,
                contractValidationMessage = "Automation OFF (fail-safe active)",
                error = null
            )
            SafeLog.i(TAG, "Automation turned OFF immediately. No orders will be submitted.")
        } else {
            _state.value = _state.value.copy(
                isAutomationEnabled = true,
                contractValidationMessage = "Automation ON (verifying active contract & credentials...)",
                error = null
            )
            SafeLog.i(TAG, "Automation turned ON. Commencing contract & credential validation.")
            scope.launch {
                hydrateFromStore()
                syncMarketAndAccount()
            }
        }
    }

    /**
     * Evaluates incoming QtY prediction against Kalshi 15-minute market.
     * Preserves both 30s and 90s predictions in the audit trail.
     * Performs order-book verification (confirmation only) independently of prediction engine.
     */
    suspend fun onNewPrediction(
        prediction: PredictionRecord,
        currentBtcPrice: Double,
        timestamp: Long
    ) {
        val currentState = _state.value
        val currentExecutionGen = executionGeneration.get()

        // ORDER-BOOK VERIFICATION (Always runs for independent confirmation & telemetry)
        val activeMarket = currentState.activeContract
        val orderBook = currentState.latestOrderBook
        val verification = KalshiOrderBookVerifier.verify(
            market = activeMarket,
            orderBook = orderBook,
            prediction = prediction,
            nowMs = timestamp
        )
        _state.value = _state.value.copy(latestVerification = verification)
        SafeLog.i(TAG, "Order Book Verification: ${verification.detailExplanation}")

        // MANDATE 9 & 10: If automation is OFF, absolutely no orders are placed
        if (!currentState.isAutomationEnabled) {
            return
        }

        // Concurrency Guard: Ensure only one order evaluation runs at a time
        if (!executionMutex.tryLock()) {
            appendLog("Concurrent execution skipped: another order evaluation is in progress.")
            return
        }

        try {
            val candidateClientOrderId = "qty_${activeMarket?.ticker ?: "KXBTC15M"}_${timestamp}_${UUID.randomUUID().toString().take(6)}"

            // MANDATE 3: Evaluate ONE FINAL FAIL-CLOSED EXECUTION GATE as the LAST authority
            val gateDecision = evaluateFinalExecutionGate(
                prediction = prediction,
                currentBtcPrice = currentBtcPrice,
                timestamp = timestamp,
                currentExecutionGen = currentExecutionGen,
                clientOrderId = candidateClientOrderId
            )

            val submitDecision = when (gateDecision) {
                is ExecutionGateDecision.Reject -> {
                    appendLog("Execution blocked by Final Execution Gate: ${gateDecision.reason}. Failing closed — no order submitted.")
                    if (gateDecision.reason.contains("credentials", ignoreCase = true) || gateDecision.reason.contains("authenticated", ignoreCase = true)) {
                        _state.value = _state.value.copy(error = "Kalshi credentials required for trading")
                    } else if (gateDecision.reason.contains("balance", ignoreCase = true)) {
                        _state.value = _state.value.copy(error = "Insufficient Kalshi balance")
                    } else if (gateDecision.reason.contains("Contract invalid", ignoreCase = true)) {
                        _state.value = _state.value.copy(contractValidationMessage = gateDecision.reason)
                    }
                    return
                }
                is ExecutionGateDecision.Submit -> gateDecision
            }

            val targetMarket = submitDecision.market
            val clientOrderId = submitDecision.clientOrderId
            val targetSide = submitDecision.targetSide
            val displaySide = submitDecision.displaySide
            val orderCount = submitDecision.orderCount
            val executablePriceCents = submitDecision.executablePriceCents
            val riskEval = submitDecision.riskEvaluation
            val calculatedEdge = submitDecision.calculatedEdge

            submittedClientOrderIds.add(clientOrderId)

            appendLog(
                "Final Execution Gate APPROVED: $targetSide ($displaySide) $orderCount contract(s) @ ${executablePriceCents}¢ | " +
                "Edge=${String.format(java.util.Locale.US, "%+.2f", calculatedEdge * 100)}% | " +
                "Risk=${riskEval.riskLevel.name} (${(riskEval.allocationFraction * 100).toInt()}%)"
            )

            // V2 EVENT ORDER REQUEST (Requirement 3)
            val orderRequest = KalshiOrderRequest(
                ticker = targetMarket.ticker,
                clientOrderId = clientOrderId,
                side = targetSide,
                count = orderCount,
                price = String.format(java.util.Locale.US, "%.4f", executablePriceCents / 100.0),
                priceCents = executablePriceCents,
                timeInForce = "good_till_canceled",
                selfTradePreventionType = "taker_at_cross"
            )

            // ORDER LIFECYCLE: Transition to SUBMITTING and persist durable state record BEFORE calling network
            val orderRecord = KalshiOrderRecord(
                clientOrderId = clientOrderId,
                ticker = targetMarket.ticker,
                side = targetSide,
                action = "buy",
                requestedCount = orderCount,
                filledCount = 0,
                remainingCount = orderCount,
                limitPriceCents = executablePriceCents,
                lifecycleState = OrderLifecycleState.SUBMITTING,
                placedTimestamp = timestamp,
                updatedTimestamp = timestamp
            )
            executionStore.recordOrder(orderRecord)
            _state.value = _state.value.copy(
                lastLifecycleState = OrderLifecycleState.SUBMITTING,
                activeOrderRecords = executionStore.getActiveOrders()
            )

            appendLog(
                "Submitting $targetSide ($displaySide) order on ${targetMarket.ticker} for $orderCount contract(s) @ ${executablePriceCents}¢ " +
                "(QtY 30s: ${prediction.decision} -> ${prediction.predictedPrice}, 90s: ${prediction.projectedDecision90s} -> ${prediction.projectedPrice90s})"
            )

            // Submit order via official Kalshi API client (DO NOT mark traded before confirmation - Requirement 8)
            val result = apiClient.submitOrder(orderRequest)
            var postSubmitRecord: KalshiOrderRecord? = null

            result.onSuccess { response ->
                // Execution State Safety: Validate order response state
                val validStatuses = setOf("resting", "filled", "executed", "canceled", "rejected", "pending")
                if (!validStatuses.contains(response.status.lowercase())) {
                    val failedRec = orderRecord.copy(
                        lifecycleState = OrderLifecycleState.FAILED,
                        failureReason = "Unknown order state: ${response.status}",
                        updatedTimestamp = System.currentTimeMillis()
                    )
                    executionStore.updateOrder(failedRec)
                    appendLog("Unknown order state '${response.status}' received from Kalshi API. Failing closed.")
                    _state.value = _state.value.copy(
                        error = "Unknown order state: ${response.status}",
                        lastLifecycleState = OrderLifecycleState.FAILED,
                        activeOrderRecords = executionStore.getActiveOrders()
                    )
                    return@onSuccess
                }

                // Execution State Safety: Validate fill count bounds
                if (response.filledCount < 0 || response.filledCount > response.count) {
                    val failedRec = orderRecord.copy(
                        lifecycleState = OrderLifecycleState.FAILED,
                        failureReason = "Unknown fill count: ${response.filledCount} out of ${response.count}",
                        updatedTimestamp = System.currentTimeMillis()
                    )
                    executionStore.updateOrder(failedRec)
                    appendLog("Unknown fill count: ${response.filledCount} out of ${response.count}. Failing closed.")
                    _state.value = _state.value.copy(
                        error = "Unknown fill count: ${response.filledCount}",
                        lastLifecycleState = OrderLifecycleState.FAILED,
                        activeOrderRecords = executionStore.getActiveOrders()
                    )
                    return@onSuccess
                }

                if (response.status.equals("rejected", ignoreCase = true)) {
                    val failedRec = orderRecord.copy(
                        lifecycleState = OrderLifecycleState.FAILED,
                        failureReason = "Order rejected by Kalshi API",
                        updatedTimestamp = System.currentTimeMillis()
                    )
                    executionStore.updateOrder(failedRec)
                    appendLog("Order rejected by Kalshi API. Failing closed.")
                    _state.value = _state.value.copy(
                        error = "Order rejected by exchange",
                        lastLifecycleState = OrderLifecycleState.FAILED,
                        activeOrderRecords = executionStore.getActiveOrders()
                    )
                    return@onSuccess
                }

                val initialLifecycle = when {
                    response.filledCount >= response.count -> OrderLifecycleState.FILLED
                    response.filledCount > 0 -> OrderLifecycleState.PARTIALLY_FILLED
                    else -> OrderLifecycleState.SUBMITTED
                }

                val updatedRecord = orderRecord.copy(
                    orderId = response.orderId,
                    filledCount = response.filledCount,
                    remainingCount = response.remainingCount,
                    averageFillPriceCents = response.averageFillPrice?.let { it * 100.0 },
                    feesCents = response.feesCents,
                    lifecycleState = initialLifecycle,
                    updatedTimestamp = System.currentTimeMillis()
                )
                executionStore.updateOrder(updatedRecord)
                postSubmitRecord = updatedRecord

                val updatedOrders = (listOf(response) + currentState.recentOrders).take(20)
                val fillStatus = when (initialLifecycle) {
                    OrderLifecycleState.FILLED -> "FILLED (${response.filledCount}/${response.count})"
                    OrderLifecycleState.PARTIALLY_FILLED -> "PARTIAL_FILL (${response.filledCount}/${response.count})"
                    else -> "RESTING (0/${response.count})"
                }

                // Associate trade details with prediction record for data integrity
                prediction.kalshiContractTicker = targetMarket.ticker
                prediction.kalshiOrderId = response.orderId
                prediction.kalshiOrderStatus = response.status
                prediction.kalshiFilledCount = response.filledCount
                prediction.kalshiOrderPrice = response.price

                // SUCCESSFUL SUBMISSION STATE (Requirement 8):
                // ONLY mark contract as traded AFTER Kalshi confirms successful order submission
                tradedContracts[targetMarket.ticker] = timestamp

                _state.value = _state.value.copy(
                    recentOrders = updatedOrders,
                    lastOrderSubmittedAt = timestamp,
                    lastOrderStatus = "${response.side.uppercase()} ${response.count}x @ ${response.price}¢ [$fillStatus]",
                    lastLifecycleState = initialLifecycle,
                    activeOrderRecords = executionStore.getActiveOrders(),
                    error = null
                )
                appendLog("Order result: ID=${response.orderId}, Status=$fillStatus")
            }.onFailure { err ->
                val failedRec = orderRecord.copy(
                    lifecycleState = OrderLifecycleState.FAILED,
                    failureReason = err.message,
                    updatedTimestamp = System.currentTimeMillis()
                )
                executionStore.updateOrder(failedRec)
                _state.value = _state.value.copy(
                    lastOrderStatus = "FAILED: ${err.message}",
                    lastLifecycleState = OrderLifecycleState.FAILED,
                    activeOrderRecords = executionStore.getActiveOrders(),
                    error = err.message
                )
                appendLog("Order submission error: ${err.message}. Failed requests do not consume trade slot. Failing closed.")
            }

            // Fill Verification and Fill Timeout Handling
            postSubmitRecord?.let { rec ->
                if (rec.lifecycleState == OrderLifecycleState.SUBMITTED || rec.lifecycleState == OrderLifecycleState.PARTIALLY_FILLED) {
                    handleFillTimeoutAndVerification(rec, currentExecutionGen)
                }
            }

            // Post-order reconciliation
            reconcileWithExchange()
        } catch (t: Throwable) {
            SafeLog.e(TAG, "Unexpected execution exception (failing closed): ${t.message}")
            appendLog("Unexpected execution exception: ${t.message}. Failing closed — no order submitted.")
            _state.value = _state.value.copy(
                error = "Execution error: ${t.message}",
                lastLifecycleState = OrderLifecycleState.FAILED
            )
        } finally {
            executionMutex.unlock()
        }
    }

    /**
     * FINAL FAIL-CLOSED EXECUTION GATE (Correction Pass 4/4 Mandate 3).
     *
     * Serves as the single, authoritative, consolidated LAST gate before order submission.
     * Evaluates all gate requirements in strict order:
     * 1. Automation != ON -> reject
     * 2. Contract invalid -> reject
     * 3. Order book invalid/stale/missing -> reject
     * 4. Executable price unavailable -> reject
     * 5. Model probability does not establish required edge -> reject
     * 6. Risk check fails -> reject
     * 7. Capital check fails -> reject
     * 8. Exposure limit fails -> reject
     * 9. Contract is already executed -> reject
     * 10. Duplicate client order exists -> reject
     * 11. Execution generation is stale -> reject
     * 12. Any required state cannot be verified -> reject
     * Otherwise -> submit
     */
    suspend fun evaluateFinalExecutionGate(
        prediction: PredictionRecord,
        currentBtcPrice: Double,
        timestamp: Long,
        currentExecutionGen: Long,
        clientOrderId: String
    ): ExecutionGateDecision {
        val currentState = _state.value

        // 1. if automation != ON: reject
        if (!currentState.isAutomationEnabled) {
            return ExecutionGateDecision.Reject("Automation is not ON")
        }

        // 2. if contract invalid: reject
        val activeMarket = currentState.activeContract
            ?: return ExecutionGateDecision.Reject("Contract missing: No active BTC 15m contract found on Kalshi")
        val contractValidation = validateContract(activeMarket, currentBtcPrice, timestamp)
        if (!contractValidation.isValid) {
            return ExecutionGateDecision.Reject("Contract invalid: ${contractValidation.reason}")
        }

        // 3. if order book invalid/stale/missing: reject
        val orderBook = currentState.latestOrderBook
            ?: return ExecutionGateDecision.Reject("Order book is missing")
        val verification = currentState.latestVerification ?: KalshiOrderBookVerifier.verify(
            market = activeMarket,
            orderBook = orderBook,
            prediction = prediction,
            nowMs = timestamp
        )
        if (verification.isStaleBook) {
            return ExecutionGateDecision.Reject("Order book is stale (> 30s old)")
        }
        if (verification.isCrossedBook) {
            return ExecutionGateDecision.Reject("Order book is crossed/inverted")
        }
        if (verification.marketBias == "UNAVAILABLE" || verification.marketBias == "NEUTRAL") {
            return ExecutionGateDecision.Reject("Market bias is ${verification.marketBias}")
        }
        if (verification.verificationSummary in listOf("UNCONFIRMED", "DIVERGENCE", "NEUTRAL")) {
            return ExecutionGateDecision.Reject("Order book verification rejected: ${verification.verificationSummary}")
        }
        val isVerifiedAgreement = (verification.verificationSummary == "FULL_AGREEMENT" ||
            (verification.verificationSummary == "PARTIAL_AGREEMENT" && verification.agreement30s == "AGREEMENT"))
        if (!isVerifiedAgreement) {
            return ExecutionGateDecision.Reject("Order book verification summary '${verification.verificationSummary}' not approved")
        }

        val targetSide = if (prediction.decision == "UP") "bid" else "ask"
        val displaySide = if (targetSide == "bid") "yes" else "no"

        val bookValidation = validateOrderBookForExecution(
            orderBook = orderBook,
            targetContractTicker = activeMarket.ticker,
            targetSide = targetSide,
            requiredCount = 1,
            nowMs = timestamp
        )
        if (!bookValidation.isValid) {
            return ExecutionGateDecision.Reject("Order book execution validation failed: ${bookValidation.reason}")
        }

        // 4. if executable price unavailable: reject
        val executablePriceCents = bookValidation.executablePriceCents
        if (executablePriceCents !in 1..99) {
            return ExecutionGateDecision.Reject("Executable price unavailable or invalid ($executablePriceCents¢)")
        }

        // 5. if model probability does not establish required edge: reject
        val (modelProb, marketProb, calculatedEdge) = if (prediction.decision == "UP") {
            val mProb = prediction.score
            val mktProb = executablePriceCents / 100.0
            Triple(mProb, mktProb, mProb - mktProb)
        } else {
            val mProb = 1.0 - prediction.score
            val mktProb = (100 - executablePriceCents) / 100.0
            Triple(mProb, mktProb, mProb - mktProb)
        }
        if (calculatedEdge < MIN_EXECUTABLE_EDGE) {
            return ExecutionGateDecision.Reject(
                "Model probability does not establish required edge: " +
                "Edge=${String.format(java.util.Locale.US, "%.2f", calculatedEdge * 100)}% < Min ${(MIN_EXECUTABLE_EDGE * 100)}%"
            )
        }

        // 6. if risk check fails: reject
        val openPositionsExposure = currentState.activePositions.sumOf { it.marketExposureCents / 100.0 }
        val restingOrdersExposure = currentState.recentOrders
            .filter { it.status.equals("resting", ignoreCase = true) }
            .sumOf { (it.count * it.price) / 100.0 }
        val currentExposureDollars = openPositionsExposure + restingOrdersExposure

        val volBps = (prediction.inputs.volatility / currentBtcPrice) * 10000.0
        val riskEval = riskEngine.evaluateOrderSizing(
            prediction = prediction,
            contractPriceCents = executablePriceCents,
            volatilityBps = volBps,
            currentExposureDollars = currentExposureDollars
        )
        if (!riskEval.isApproved || riskEval.actualOrderSize <= 0) {
            return ExecutionGateDecision.Reject("Risk check failed: ${riskEval.reason}")
        }
        val orderCount = minOf(riskEval.actualOrderSize, tradeSizeLimit.coerceIn(1, MAX_CONTRACTS_PER_ORDER))
        if (bookValidation.availableDepth < orderCount) {
            return ExecutionGateDecision.Reject("Insufficient book depth: depth=${bookValidation.availableDepth} < orderCount=$orderCount")
        }

        // 7. if capital check fails: reject
        if (riskEval.eligibleCapitalDollars <= 0.0) {
            return ExecutionGateDecision.Reject("Capital check failed: No eligible capital under profit-only capital rule")
        }
        val contractRiskCents = if (targetSide == "bid") {
            orderCount * executablePriceCents.toLong()
        } else {
            orderCount * (100L - executablePriceCents)
        }
        val estimatedFeesCents = orderCount * 2L
        val totalRequiredFundsCents = contractRiskCents + estimatedFeesCents
        if (currentState.balance.balanceCents < totalRequiredFundsCents) {
            return ExecutionGateDecision.Reject(
                "Capital check failed: Insufficient balance ($${currentState.balance.balanceDollars} < required $${totalRequiredFundsCents / 100.0})"
            )
        }

        // 8. if exposure limit fails: reject
        val totalProjectedExposureDollars = currentExposureDollars + (contractRiskCents / 100.0)
        if (totalProjectedExposureDollars > riskEngine.hardExposureLimitDollars) {
            return ExecutionGateDecision.Reject("Exposure limit exceeded: projected $${totalProjectedExposureDollars} > max $${riskEngine.hardExposureLimitDollars}")
        }

        // 9. if contract is already executed: reject
        val lastTraded = tradedContracts[activeMarket.ticker] ?: 0L
        if (lastTraded > 0L && (timestamp - lastTraded) < ORDER_COOLDOWN_MS) {
            return ExecutionGateDecision.Reject("Contract already executed: ${activeMarket.ticker} within cooldown")
        }
        val existingOrdersForContract = executionStore.getOrdersByContract(activeMarket.ticker)
        val hasRecentOrActiveContractOrder = existingOrdersForContract.any {
            (timestamp - it.placedTimestamp) < ORDER_COOLDOWN_MS ||
            it.lifecycleState in listOf(
                OrderLifecycleState.SUBMITTING,
                OrderLifecycleState.SUBMITTED,
                OrderLifecycleState.PARTIALLY_FILLED,
                OrderLifecycleState.CANCEL_PENDING
            )
        }
        if (hasRecentOrActiveContractOrder) {
            return ExecutionGateDecision.Reject("Contract already executed: active or recent order in store for ${activeMarket.ticker}")
        }
        val hasResting = currentState.recentOrders.any {
            it.ticker == activeMarket.ticker && it.status.equals("resting", ignoreCase = true)
        }
        if (hasResting) {
            return ExecutionGateDecision.Reject("Contract already executed: active resting order exists on ${activeMarket.ticker}")
        }

        // 10. if duplicate client order exists: reject
        if (submittedClientOrderIds.contains(clientOrderId) || executionStore.getOrderByClientOrderId(clientOrderId) != null) {
            return ExecutionGateDecision.Reject("Duplicate client order exists: $clientOrderId")
        }

        // 11. if execution generation is stale: reject
        if (executionGeneration.get() != currentExecutionGen) {
            return ExecutionGateDecision.Reject("Execution generation is stale (${executionGeneration.get()} != $currentExecutionGen)")
        }

        // 12. if any required state cannot be verified: reject
        if (!currentState.isAuthenticated || !apiClient.isAuthenticated()) {
            return ExecutionGateDecision.Reject("Required state unverifiable: Kalshi API is not authenticated")
        }
        if (prediction.decision != "UP" && prediction.decision != "DOWN") {
            return ExecutionGateDecision.Reject("Required state unverifiable: Prediction decision is ${prediction.decision}")
        }
        if (currentBtcPrice <= 0.0 || currentBtcPrice.isNaN()) {
            return ExecutionGateDecision.Reject("Required state unverifiable: BTC price is invalid ($currentBtcPrice)")
        }
        val latestPrice = priceHistory.getLatest()
        if (latestPrice != null && (timestamp - latestPrice.timestamp) > 15_000L) {
            return ExecutionGateDecision.Reject("Required state unverifiable: Price data is stale (${timestamp - latestPrice.timestamp}ms > 15000ms)")
        }
        val currentPos = currentState.activePositions.find { it.ticker == activeMarket.ticker }?.position ?: 0
        if (targetSide == "bid" && currentPos < 0) {
            return ExecutionGateDecision.Reject("Required state unverifiable: Account holds opposite NO position ($currentPos)")
        } else if (targetSide == "ask" && currentPos > 0) {
            return ExecutionGateDecision.Reject("Required state unverifiable: Account holds opposite YES position ($currentPos)")
        }

        // Otherwise: submit
        return ExecutionGateDecision.Submit(
            market = activeMarket,
            clientOrderId = clientOrderId,
            targetSide = targetSide,
            displaySide = displaySide,
            orderCount = orderCount,
            executablePriceCents = executablePriceCents,
            riskEvaluation = riskEval,
            calculatedEdge = calculatedEdge
        )
    }

    /**
     * Requirement 3 & 4: Fill Verification and Fill Timeout.
     * Evaluates resting orders against the exchange. If resting beyond fillTimeoutMs, executes cancellation.
     * Persists order lifecycle state at each transition.
     */
    suspend fun handleFillTimeoutAndVerification(
        orderRecord: KalshiOrderRecord,
        currentGen: Long
    ): KalshiOrderRecord {
        val orderId = orderRecord.orderId ?: return orderRecord
        if (orderRecord.lifecycleState == OrderLifecycleState.FILLED ||
            orderRecord.lifecycleState == OrderLifecycleState.FAILED ||
            orderRecord.lifecycleState == OrderLifecycleState.CANCELLED) {
            return orderRecord
        }

        // Check if automation was turned off or generation invalidated
        if (!_state.value.isAutomationEnabled || executionGeneration.get() != currentGen) {
            return orderRecord
        }

        // Fill verification via API
        val checkRes = apiClient.getOrder(orderId)
        if (checkRes.isSuccess) {
            val detail = checkRes.getOrThrow()
            if (detail.filledCount >= detail.count) {
                val filledRecord = orderRecord.copy(
                    lifecycleState = OrderLifecycleState.FILLED,
                    filledCount = detail.filledCount,
                    remainingCount = 0,
                    averageFillPriceCents = detail.averageFillPrice?.let { it * 100.0 },
                    feesCents = detail.feesCents,
                    updatedTimestamp = System.currentTimeMillis()
                )
                executionStore.updateOrder(filledRecord)
                _state.value = _state.value.copy(
                    lastLifecycleState = OrderLifecycleState.FILLED,
                    activeOrderRecords = executionStore.getActiveOrders()
                )
                return filledRecord
            } else if (detail.filledCount > 0 && detail.filledCount < detail.count) {
                val partialRecord = orderRecord.copy(
                    lifecycleState = OrderLifecycleState.PARTIALLY_FILLED,
                    filledCount = detail.filledCount,
                    remainingCount = detail.remainingCount,
                    averageFillPriceCents = detail.averageFillPrice?.let { it * 100.0 },
                    feesCents = detail.feesCents,
                    updatedTimestamp = System.currentTimeMillis()
                )
                executionStore.updateOrder(partialRecord)
                _state.value = _state.value.copy(
                    lastLifecycleState = OrderLifecycleState.PARTIALLY_FILLED,
                    activeOrderRecords = executionStore.getActiveOrders()
                )
            }
        }

        // Check fill timeout window
        val elapsed = System.currentTimeMillis() - orderRecord.placedTimestamp
        if (elapsed >= fillTimeoutMs) {
            appendLog("Fill timeout (${fillTimeoutMs}ms) elapsed for order $orderId. Attempting cancellation.")
            val cancelPendingRecord = orderRecord.copy(
                lifecycleState = OrderLifecycleState.CANCEL_PENDING,
                updatedTimestamp = System.currentTimeMillis()
            )
            executionStore.updateOrder(cancelPendingRecord)
            _state.value = _state.value.copy(
                lastLifecycleState = OrderLifecycleState.CANCEL_PENDING,
                activeOrderRecords = executionStore.getActiveOrders()
            )

            // Attempt cancellation
            val cancelRes = apiClient.cancelOrder(orderId)
            val postCancelDetail = apiClient.getOrder(orderId).getOrNull()
            val finalFilled = postCancelDetail?.filledCount ?: orderRecord.filledCount
            val finalRemaining = postCancelDetail?.remainingCount ?: (orderRecord.requestedCount - finalFilled)

            val finalState = when {
                finalFilled >= orderRecord.requestedCount -> OrderLifecycleState.FILLED
                finalFilled > 0 -> OrderLifecycleState.PARTIALLY_FILLED
                cancelRes.isSuccess -> OrderLifecycleState.CANCELLED
                else -> OrderLifecycleState.FAILED
            }

            val finalRecord = orderRecord.copy(
                lifecycleState = finalState,
                filledCount = finalFilled,
                remainingCount = finalRemaining,
                failureReason = if (cancelRes.isFailure) "Cancel failed: ${cancelRes.exceptionOrNull()?.message}" else null,
                updatedTimestamp = System.currentTimeMillis()
            )
            executionStore.updateOrder(finalRecord)
            _state.value = _state.value.copy(
                lastLifecycleState = finalState,
                activeOrderRecords = executionStore.getActiveOrders()
            )
            appendLog("Order $orderId finalized as $finalState (filled: $finalFilled, remaining: $finalRemaining)")
            return finalRecord
        }

        return orderRecord
    }

    /**
     * Requirement 5: Post-Order Reconciliation.
     * Synchronizes local state with exchange open orders and positions.
     * Prevents false assumptions and detects discrepancies.
     * If reconciliation fails: FAIL CLOSED.
     */
    suspend fun reconcileWithExchange(): Result<Boolean> {
        if (!apiClient.isAuthenticated()) {
            return Result.success(true)
        }

        try {
            val openOrdersRes = apiClient.getOpenOrders()
            val positionsRes = apiClient.getPositions()
            val balanceRes = apiClient.getPortfolioBalance()

            if (openOrdersRes.isFailure || positionsRes.isFailure || balanceRes.isFailure) {
                val err = openOrdersRes.exceptionOrNull()?.message
                    ?: positionsRes.exceptionOrNull()?.message
                    ?: balanceRes.exceptionOrNull()?.message
                    ?: "Unknown error fetching exchange state"
                appendLog("Post-order reconciliation failed: $err. Failing closed.")
                _state.value = _state.value.copy(
                    isAutomationEnabled = false,
                    isReconciliationFailed = true,
                    error = "Reconciliation failure: $err"
                )
                invalidateExecutionGeneration()
                return Result.failure(Exception("Reconciliation failure: $err"))
            }

            val exchangeOrders = openOrdersRes.getOrNull().orEmpty()
            val exchangePositions = positionsRes.getOrNull().orEmpty()
            val exchangeBalance = balanceRes.getOrNull() ?: KalshiBalance()

            // Verify local active orders against exchange
            val localActiveOrders = executionStore.getActiveOrders()
            for (localOrder in localActiveOrders) {
                val orderId = localOrder.orderId
                if (orderId != null) {
                    val matchingExchangeOrder = exchangeOrders.find { it.orderId == orderId }
                    if (matchingExchangeOrder == null) {
                        // Order is not resting in exchange open orders. Query its terminal state.
                        val orderStatusRes = apiClient.getOrder(orderId)
                        if (orderStatusRes.isSuccess) {
                            val exchangeDetail = orderStatusRes.getOrThrow()
                            val updatedRecord = when (exchangeDetail.status.lowercase()) {
                                "executed", "filled" -> localOrder.copy(
                                    lifecycleState = OrderLifecycleState.FILLED,
                                    filledCount = exchangeDetail.filledCount,
                                    remainingCount = 0,
                                    averageFillPriceCents = exchangeDetail.averageFillPrice?.let { it * 100.0 },
                                    feesCents = exchangeDetail.feesCents,
                                    updatedTimestamp = System.currentTimeMillis()
                                )
                                "canceled" -> localOrder.copy(
                                    lifecycleState = OrderLifecycleState.CANCELLED,
                                    filledCount = exchangeDetail.filledCount,
                                    remainingCount = exchangeDetail.remainingCount,
                                    updatedTimestamp = System.currentTimeMillis()
                                )
                                else -> localOrder.copy(
                                    filledCount = exchangeDetail.filledCount,
                                    remainingCount = exchangeDetail.remainingCount,
                                    updatedTimestamp = System.currentTimeMillis()
                                )
                            }
                            executionStore.updateOrder(updatedRecord)
                        } else {
                            // If order query fails or exchange denies knowledge of order: FAIL CLOSED
                            appendLog("Reconciliation mismatch: Local active order $orderId not recognized by exchange. Failing closed.")
                            _state.value = _state.value.copy(
                                isAutomationEnabled = false,
                                isReconciliationFailed = true,
                                error = "Reconciliation mismatch on order $orderId"
                            )
                            invalidateExecutionGeneration()
                            return Result.failure(Exception("Reconciliation mismatch on order $orderId"))
                        }
                    } else {
                        // Order is still resting on exchange
                        if (matchingExchangeOrder.filledCount != localOrder.filledCount) {
                            val updated = localOrder.copy(
                                filledCount = matchingExchangeOrder.filledCount,
                                remainingCount = matchingExchangeOrder.remainingCount,
                                lifecycleState = if (matchingExchangeOrder.filledCount > 0) OrderLifecycleState.PARTIALLY_FILLED else OrderLifecycleState.SUBMITTED,
                                updatedTimestamp = System.currentTimeMillis()
                            )
                            executionStore.updateOrder(updated)
                        }
                    }
                }
            }

            _state.value = _state.value.copy(
                balance = exchangeBalance,
                activePositions = exchangePositions,
                recentOrders = exchangeOrders,
                activeOrderRecords = executionStore.getActiveOrders(),
                isReconciliationFailed = false
            )
            return Result.success(true)
        } catch (e: Exception) {
            appendLog("Reconciliation exception: ${e.message}. Failing closed.")
            _state.value = _state.value.copy(
                isAutomationEnabled = false,
                isReconciliationFailed = true,
                error = "Reconciliation exception: ${e.message}"
            )
            invalidateExecutionGeneration()
            return Result.failure(e)
        }
    }

    /**
     * Validates that the active Kalshi contract is:
     * 1. Belongs to the verified 15-minute series (KXBTC15M) or event.
     * 2. Closes strictly within the current 15-minute window (> 30s remaining to avoid settlement lock).
     * 3. Has an active/open trading status.
     * 4. Aligns with QtY's rolling 15-minute reference methodology.
     * 5. Strike price is valid and not excessively divergent from spot.
     */
    fun validateContract(
        market: KalshiMarket,
        currentBtcPrice: Double,
        nowMs: Long
    ): ValidationResult {
        if (market.ticker.isBlank()) {
            return ValidationResult(false, "Contract ticker is empty")
        }

        val isBtc15mSeries = market.seriesTicker.equals(KalshiApiClient.BTC_15M_SERIES, ignoreCase = true) ||
            market.eventTicker.contains("KXBTC15M", ignoreCase = true) ||
            market.ticker.contains("KXBTC15M", ignoreCase = true)
        if (!isBtc15mSeries) {
            return ValidationResult(false, "Unrecognized series/event ticker: ${market.seriesTicker} / ${market.eventTicker}")
        }

        if (!market.status.equals("active", ignoreCase = true) && !market.status.equals("open", ignoreCase = true)) {
            return ValidationResult(false, "Market status is ${market.status}, not active/open")
        }

        if (market.closeTimeMs <= nowMs) {
            return ValidationResult(false, "Contract is expired (${market.closeTimeMs} <= $nowMs)")
        }

        if (market.openTimeMs > nowMs) {
            return ValidationResult(false, "Contract is not yet open (${market.openTimeMs} > $nowMs)")
        }

        val remainingMs = market.closeTimeMs - nowMs
        if (remainingMs <= 30_000L) {
            return ValidationResult(false, "Contract expiring too soon (${remainingMs / 1000}s remaining <= 30s buffer)")
        }

        // Settlement window validation: contract duration must be a 15m window (~10m to ~20m)
        if (market.openTimeMs > 0L) {
            val durationMs = market.closeTimeMs - market.openTimeMs
            if (durationMs !in 600_000L..1_200_000L) {
                return ValidationResult(false, "Invalid settlement window duration (${durationMs / 60_000}m): expected ~15m")
            }
        }

        if (market.strikePrice == null || market.strikePrice <= 0.0 || market.strikePrice.isNaN()) {
            return ValidationResult(false, "Missing or invalid strike price: ${market.strikePrice}")
        }

        if (currentBtcPrice > 0.0) {
            val diff = kotlin.math.abs(market.strikePrice - currentBtcPrice) / currentBtcPrice
            if (diff > 0.10) {
                return ValidationResult(false, "Strike price $${market.strikePrice} diverges by ${(diff * 100).toInt()}% from spot ($${currentBtcPrice})")
            }
        }

        return ValidationResult(true, "Contract verified: ${market.ticker}, closes in ${remainingMs / 1000}s")
    }

    /**
     * Hard Order-Book Execution Gate (Requirements 4, 5, 6):
     * Strictly verifies order book freshness, bid/ask validity, non-crossed state,
     * required side presence, and depth before allowing any order to be placed.
     */
    fun validateOrderBookForExecution(
        orderBook: KalshiOrderBookSnapshot?,
        targetContractTicker: String,
        targetSide: String, // "bid" or "ask"
        requiredCount: Int,
        nowMs: Long
    ): OrderBookExecutionValidation {
        if (orderBook == null) {
            return OrderBookExecutionValidation(false, reason = "Order book is missing")
        }
        if (orderBook.ticker != targetContractTicker) {
            return OrderBookExecutionValidation(
                false,
                reason = "Order book ticker '${orderBook.ticker}' does not match target '$targetContractTicker'"
            )
        }
        if (orderBook.timestampMs <= 0L) {
            return OrderBookExecutionValidation(false, reason = "Order book missing valid timestamp")
        }
        val ageMs = nowMs - orderBook.timestampMs
        if (ageMs > 15_000L || ageMs < -5_000L) {
            return OrderBookExecutionValidation(false, reason = "Order book is stale (${ageMs}ms old > 15000ms)")
        }
        val bestYesBid = orderBook.bestYesBidCents
        val impliedYesAsk = orderBook.impliedYesAskCents
        if (bestYesBid == null || impliedYesAsk == null || bestYesBid !in 1..99 || impliedYesAsk !in 1..99) {
            return OrderBookExecutionValidation(
                false,
                reason = "Order book is one-sided or missing valid two-sided quotes (bestYesBid=$bestYesBid, impliedYesAsk=$impliedYesAsk)"
            )
        }
        if (bestYesBid >= impliedYesAsk) {
            return OrderBookExecutionValidation(
                false,
                reason = "Order book is crossed/inverted (bestYesBid=$bestYesBid >= impliedYesAsk=$impliedYesAsk)"
            )
        }
        if (targetSide == "bid") {
            val executablePrice = impliedYesAsk
            if (executablePrice !in 1..99) {
                return OrderBookExecutionValidation(false, reason = "Executable YES ask price ($executablePrice) is out of 1..99 range")
            }
            val availableDepth = orderBook.noBids.firstOrNull()?.quantity ?: orderBook.totalNoDepth
            if (availableDepth <= 0.0) {
                return OrderBookExecutionValidation(false, reason = "Missing executable depth on ask side (depth=$availableDepth)")
            }
            if (availableDepth < requiredCount) {
                return OrderBookExecutionValidation(false, reason = "Available ask depth ($availableDepth) is less than requested count ($requiredCount)")
            }
            return OrderBookExecutionValidation(true, executablePriceCents = executablePrice, availableDepth = availableDepth)
        } else {
            val executablePrice = bestYesBid
            if (executablePrice !in 1..99) {
                return OrderBookExecutionValidation(false, reason = "Executable YES bid price ($executablePrice) is out of 1..99 range")
            }
            val availableDepth = orderBook.yesBids.firstOrNull()?.quantity ?: orderBook.totalYesDepth
            if (availableDepth <= 0.0) {
                return OrderBookExecutionValidation(false, reason = "Missing executable depth on bid side (depth=$availableDepth)")
            }
            if (availableDepth < requiredCount) {
                return OrderBookExecutionValidation(false, reason = "Available bid depth ($availableDepth) is less than requested count ($requiredCount)")
            }
            return OrderBookExecutionValidation(true, executablePriceCents = executablePrice, availableDepth = availableDepth)
        }
    }

    data class OrderBookExecutionValidation(
        val isValid: Boolean,
        val executablePriceCents: Int = 0,
        val availableDepth: Double = 0.0,
        val reason: String = ""
    )

    /**
     * Synchronizes active markets, balance, and open positions with Kalshi.
     * Strictly detects and rejects ambiguous contracts.
     */
    suspend fun syncMarketAndAccount() {
        val auth = apiClient.isAuthenticated()

        // Fetch active BTC 15m markets
        val marketsRes = apiClient.getActiveBtc15mContracts()
        var activeMarket: KalshiMarket? = null
        var validationMsg = "No active contract"

        marketsRes.onSuccess { markets ->
            val nowMs = System.currentTimeMillis()
            val candidateContracts = markets.filter {
                it.closeTimeMs > (nowMs + 30_000L) && it.status.equals("active", ignoreCase = true)
            }

            if (candidateContracts.isEmpty()) {
                activeMarket = null
                validationMsg = "No active BTC 15m contract"
            } else {
                val earliestCloseMs = candidateContracts.minOf { it.closeTimeMs }
                val windowCandidates = candidateContracts.filter { it.closeTimeMs == earliestCloseMs }

                if (windowCandidates.size > 1) {
                    activeMarket = null
                    validationMsg = "Ambiguous contract identity: ${windowCandidates.size} contracts found for window. Failing closed."
                    SafeLog.w(TAG, validationMsg)
                } else {
                    activeMarket = windowCandidates.first()
                    validationMsg = "Active: ${activeMarket?.ticker} (closes in ${(activeMarket!!.closeTimeMs - nowMs) / 1000}s)"
                }
            }
        }.onFailure { err ->
            validationMsg = "Error discovering contract: ${err.message}"
        }

        // Fetch order book for active contract if available (do not manufacture missing data)
        var orderBook: KalshiOrderBookSnapshot? = _state.value.latestOrderBook
        if (activeMarket != null) {
            val bookRes = apiClient.getOrderBook(activeMarket!!.ticker)
            bookRes.onSuccess { ob ->
                orderBook = ob
            }.onFailure { err ->
                SafeLog.w(TAG, "Order book sync note for ${activeMarket?.ticker}: ${err.message}")
            }
        }

        if (auth) {
            reconcileWithExchange()
        }

        _state.value = _state.value.copy(
            isAuthenticated = auth,
            activeContract = activeMarket,
            contractValidationMessage = if (_state.value.isAutomationEnabled) validationMsg else "Automation OFF",
            latestOrderBook = orderBook
        )
    }

    fun setStateForTesting(
        isAuthenticated: Boolean? = null,
        activeContract: KalshiMarket? = null,
        balance: KalshiBalance? = null,
        activePositions: List<KalshiPosition>? = null,
        latestOrderBook: KalshiOrderBookSnapshot? = null
    ) {
        _state.value = _state.value.copy(
            isAuthenticated = isAuthenticated ?: _state.value.isAuthenticated,
            activeContract = activeContract ?: _state.value.activeContract,
            balance = balance ?: _state.value.balance,
            activePositions = activePositions ?: _state.value.activePositions,
            latestOrderBook = latestOrderBook ?: _state.value.latestOrderBook
        )
    }

    private fun appendLog(msg: String) {
        SafeLog.i(TAG, msg)
        val updated = (listOf("[${System.currentTimeMillis() % 100000}] $msg") + _state.value.executionLog).take(30)
        _state.value = _state.value.copy(executionLog = updated)
    }

    data class ValidationResult(val isValid: Boolean, val reason: String)
}
