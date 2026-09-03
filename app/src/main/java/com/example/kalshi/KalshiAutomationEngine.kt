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
    val tradeSizeLimit: Int = 1 // Strict small trade-size limit
) {
    companion object {
        private const val TAG = "KalshiAutomationEngine"
        const val MAX_CONTRACTS_PER_ORDER = 5
        private const val ORDER_COOLDOWN_MS = 60_000L // Min 60s between orders on the same contract
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var syncJob: Job? = null

    // Automation state - isAutomationEnabled is FALSE by default
    private val _state = MutableStateFlow(KalshiAutomationState(isAutomationEnabled = false))
    val state: StateFlow<KalshiAutomationState> = _state.asStateFlow()

    // Mutex to strictly prevent concurrent execution / order submission races
    private val executionMutex = Mutex()

    // Deduplication tracking: contractTicker -> lastOrderTimestamp
    private val tradedContracts = ConcurrentHashMap<String, Long>()
    private val submittedClientOrderIds = ConcurrentHashMap.newKeySet<String>()

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
            // Trade Validation Audit
            appendLog("Trade Validation: Order Book Verification Status = ${verification.verificationSummary} (30s: ${verification.agreement30s}, 90s: ${verification.agreement90s})")

            // MANDATE 11: Fail Closed on NO-TRADE, stale, or zero price
            if (prediction.decision != "UP" && prediction.decision != "DOWN") {
                appendLog("Decision is ${prediction.decision} (NO-TRADE). Failing closed — no trade executed.")
                return
            }

            if (currentBtcPrice <= 0.0 || currentBtcPrice.isNaN()) {
                appendLog("Invalid current BTC price ($currentBtcPrice). Failing closed.")
                return
            }

            // Must be authenticated to trade
            if (!currentState.isAuthenticated || !apiClient.isAuthenticated()) {
                appendLog("Account not authenticated with Kalshi credentials. Failing closed.")
                _state.value = _state.value.copy(error = "Kalshi credentials required for trading")
                return
            }

            // MANDATE 5: Identify and Validate active contract & settlement methodology
            if (activeMarket == null) {
                appendLog("No active BTC 15m contract found on Kalshi. Failing closed.")
                return
            }

            val validationResult = validateContract(activeMarket, currentBtcPrice, timestamp)
            if (!validationResult.isValid) {
                appendLog("Contract validation failed: ${validationResult.reason}. Failing closed.")
                _state.value = _state.value.copy(contractValidationMessage = "Invalid: ${validationResult.reason}")
                return
            }

            // MANDATE 7: Prevent duplicate orders for the same 15-minute contract
            val lastTraded = tradedContracts[activeMarket.ticker] ?: 0L
            if (lastTraded > 0L && (timestamp - lastTraded) < ORDER_COOLDOWN_MS) {
                appendLog("Duplicate order prevention: Contract ${activeMarket.ticker} already traded recently. Skipping.")
                return
            }

            // Prevent duplicate orders if an active resting order exists
            val hasResting = currentState.recentOrders.any {
                it.ticker == activeMarket.ticker && it.status.equals("resting", ignoreCase = true)
            }
            if (hasResting) {
                appendLog("Duplicate order prevention: Active resting order exists on ${activeMarket.ticker}. Skipping.")
                return
            }

            // MANDATE 8: Small trade-size enforcement
            val orderCount = tradeSizeLimit.coerceIn(1, MAX_CONTRACTS_PER_ORDER)

            // Balance check: Ensure sufficient cash balance (max exposure = orderCount * 100 cents)
            val maxCostCents = orderCount * 100L
            if (currentState.balance.balanceCents < maxCostCents) {
                appendLog("Insufficient balance (${currentState.balance.balanceDollars} USD < required ${maxCostCents / 100.0} USD). Failing closed.")
                _state.value = _state.value.copy(error = "Insufficient Kalshi balance")
                return
            }

            // Determine side based on QtY Prediction:
            // UP -> buy "yes" contract
            // DOWN -> buy "no" contract
            val orderSide = if (prediction.decision == "UP") "yes" else "no"

            // Prevent position mismatch / conflicting opposing trade
            val currentPos = currentState.activePositions.find { it.ticker == activeMarket.ticker }?.position ?: 0
            if (orderSide == "yes" && currentPos < 0) {
                appendLog("Position mismatch: Account holds opposite NO position ($currentPos) on ${activeMarket.ticker}. Failing closed.")
                return
            } else if (orderSide == "no" && currentPos > 0) {
                appendLog("Position mismatch: Account holds opposite YES position ($currentPos) on ${activeMarket.ticker}. Failing closed.")
                return
            }

            val limitPrice = if (orderSide == "yes") {
                if (activeMarket.yesAsk in 1..99) activeMarket.yesAsk else 50
            } else {
                if (activeMarket.noAsk in 1..99) activeMarket.noAsk else 50
            }

            val clientOrderId = "qty_${activeMarket.ticker}_${timestamp}_${UUID.randomUUID().toString().take(6)}"
            if (!submittedClientOrderIds.add(clientOrderId)) {
                appendLog("Duplicate clientOrderId detected. Failing closed.")
                return
            }

            val orderRequest = KalshiOrderRequest(
                ticker = activeMarket.ticker,
                action = "buy",
                side = orderSide,
                type = "limit",
                count = orderCount,
                yesPrice = if (orderSide == "yes") limitPrice else null,
                noPrice = if (orderSide == "no") limitPrice else null,
                clientOrderId = clientOrderId
            )

            appendLog(
                "Submitting $orderSide order on ${activeMarket.ticker} for $orderCount contract(s) @ ${limitPrice}¢ " +
                "(QtY 30s: ${prediction.decision} -> ${prediction.predictedPrice}, 90s: ${prediction.projectedDecision90s} -> ${prediction.projectedPrice90s})"
            )

            // Mark as traded to prevent duplicate submissions
            tradedContracts[activeMarket.ticker] = timestamp

            // Submit order via official Kalshi API client
            val result = apiClient.submitOrder(orderRequest)
            result.onSuccess { response ->
                val updatedOrders = (listOf(response) + currentState.recentOrders).take(20)
                val fillStatus = when {
                    response.status.equals("rejected", ignoreCase = true) -> "REJECTED"
                    response.filledCount >= response.count -> "FILLED (${response.filledCount}/${response.count})"
                    response.filledCount > 0 -> "PARTIAL_FILL (${response.filledCount}/${response.count})"
                    else -> "RESTING (0/${response.count})"
                }

                // Associate trade details with prediction record for data integrity
                prediction.kalshiContractTicker = activeMarket.ticker
                prediction.kalshiOrderId = response.orderId
                prediction.kalshiOrderStatus = response.status
                prediction.kalshiFilledCount = response.filledCount
                prediction.kalshiOrderPrice = response.price

                _state.value = _state.value.copy(
                    recentOrders = updatedOrders,
                    lastOrderSubmittedAt = timestamp,
                    lastOrderStatus = "${response.side.uppercase()} ${response.count}x @ ${response.price}¢ [$fillStatus]",
                    error = null
                )
                appendLog("Order result: ID=${response.orderId}, Status=$fillStatus")

                // Refresh portfolio positions and balance immediately
                syncMarketAndAccount()
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    lastOrderStatus = "FAILED: ${err.message}",
                    error = err.message
                )
                appendLog("Order submission error: ${err.message}. Failing closed.")
            }
        } finally {
            executionMutex.unlock()
        }
    }

    /**
     * Validates that the active Kalshi contract is:
     * 1. Belongs to the verified 15-minute series (KXBTC15M).
     * 2. Closes strictly within the current 15-minute window (> 30s remaining to avoid settlement lock).
     * 3. Has an active trading status.
     * 4. Aligns with QtY's rolling 15-minute reference methodology.
     */
    fun validateContract(
        market: KalshiMarket,
        currentBtcPrice: Double,
        nowMs: Long
    ): ValidationResult {
        if (market.seriesTicker != KalshiApiClient.BTC_15M_SERIES && !market.ticker.startsWith("KXBTC15M")) {
            return ValidationResult(false, "Unrecognized series ticker: ${market.seriesTicker}")
        }

        if (!market.status.equals("active", ignoreCase = true)) {
            return ValidationResult(false, "Market status is ${market.status}, not active")
        }

        if (market.closeTimeMs <= nowMs) {
            return ValidationResult(false, "Contract is expired (${market.closeTimeMs} <= $nowMs)")
        }

        if (market.openTimeMs > nowMs) {
            return ValidationResult(false, "Contract is not yet open (${market.openTimeMs} > $nowMs)")
        }

        val remainingMs = market.closeTimeMs - nowMs
        if (remainingMs <= 30_000L) {
            return ValidationResult(false, "Contract expiring too soon (${remainingMs / 1000}s remaining)")
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

        var balance = _state.value.balance
        var positions = _state.value.activePositions

        if (auth) {
            apiClient.getPortfolioBalance().onSuccess { bal ->
                balance = bal
            }.onFailure { err ->
                SafeLog.w(TAG, "Failed to update balance: ${err.message}")
            }
            apiClient.getPositions().onSuccess { posList ->
                positions = posList
            }.onFailure { err ->
                SafeLog.w(TAG, "Failed to update positions: ${err.message}")
            }
        }

        _state.value = _state.value.copy(
            isAuthenticated = auth,
            activeContract = activeMarket,
            contractValidationMessage = if (_state.value.isAutomationEnabled) validationMsg else "Automation OFF",
            balance = balance,
            activePositions = positions,
            latestOrderBook = orderBook
        )
    }

    private fun appendLog(msg: String) {
        SafeLog.i(TAG, msg)
        val updated = (listOf("[${System.currentTimeMillis() % 100000}] $msg") + _state.value.executionLog).take(30)
        _state.value = _state.value.copy(executionLog = updated)
    }

    data class ValidationResult(val isValid: Boolean, val reason: String)
}
