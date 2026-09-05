package com.example.kalshi

/**
 * Real Kalshi Market model for 15-minute Bitcoin contracts (series: KXBTC15M).
 */
data class KalshiMarket(
    val ticker: String,
    val eventTicker: String,
    val seriesTicker: String,
    val title: String,
    val subtitle: String,
    val openTimeMs: Long,
    val closeTimeMs: Long,
    val expirationTimeMs: Long,
    val status: String, // "active", "closed", "determined"
    val yesBid: Int,
    val yesAsk: Int,
    val noBid: Int,
    val noAsk: Int,
    val lastPrice: Int,
    val strikePrice: Double?, // Reference / strike price for the contract
    val strikeType: String? = "greater" // Settles YES if btc_price > strikePrice
)

/**
 * Current V2 Event Order submission request to Kalshi:
 * POST /portfolio/events/orders
 *
 * For event markets:
 * side = "bid" (buy YES)
 * side = "ask" (sell YES)
 */
data class KalshiOrderRequest(
    val ticker: String,
    val clientOrderId: String,
    val side: String, // "bid" = buy YES | "ask" = sell YES
    val count: Int,
    val price: String, // fixed-point dollar string e.g. "0.6500"
    val priceCents: Int = 0, // integer cents 1..99
    val timeInForce: String = "good_till_canceled",
    val selfTradePreventionType: String = "taker_at_cross",
    // Compatibility properties for internal models & older tests
    val action: String = if (side == "bid" || side.equals("yes", ignoreCase = true)) "buy" else "sell",
    val type: String = "limit",
    val orderSide: String = if (side == "bid" || side.equals("yes", ignoreCase = true)) "yes" else "no",
    val yesPrice: Int? = if (side == "bid" || side.equals("yes", ignoreCase = true)) priceCents.takeIf { it > 0 } else null,
    val noPrice: Int? = if (side == "ask" || side.equals("no", ignoreCase = true)) (100 - priceCents).takeIf { it in 1..99 } else null
) {
    // Secondary constructor to seamlessly support legacy test invocation patterns
    constructor(
        ticker: String,
        action: String = "buy",
        side: String,
        type: String = "limit",
        count: Int,
        yesPrice: Int? = null,
        noPrice: Int? = null,
        clientOrderId: String,
        timeInForce: String = "good_till_canceled",
        selfTradePreventionType: String = "taker_at_cross"
    ) : this(
        ticker = ticker,
        clientOrderId = clientOrderId,
        side = when (side.lowercase()) {
            "yes", "bid" -> "bid"
            "no", "ask" -> "ask"
            else -> side
        },
        count = count,
        priceCents = when {
            yesPrice != null -> yesPrice
            noPrice != null -> (100 - noPrice).coerceIn(1, 99)
            else -> 0
        },
        price = String.format(
            java.util.Locale.US,
            "%.4f",
            (when {
                yesPrice != null -> yesPrice
                noPrice != null -> (100 - noPrice).coerceIn(1, 99)
                else -> 0
            }) / 100.0
        ),
        timeInForce = timeInForce,
        selfTradePreventionType = selfTradePreventionType
    )
}

/**
 * Order response from Kalshi
 */
data class KalshiOrderResponse(
    val orderId: String,
    val clientOrderId: String,
    val ticker: String,
    val status: String, // "resting" | "executed" | "canceled" | "pending"
    val action: String,
    val side: String,
    val count: Int,
    val filledCount: Int,
    val price: Int,
    val placeTimeMs: Long,
    val remainingCount: Int = count - filledCount,
    val averageFillPrice: Double? = null,
    val feesCents: Double = 0.0
)

/**
 * Explicit execution state machine for order lifecycle:
 * ELIGIBLE -> VALIDATING -> SUBMITTING -> SUBMITTED -> PARTIALLY_FILLED -> FILLED -> CANCEL_PENDING -> CANCELLED -> FAILED
 */
enum class OrderLifecycleState {
    ELIGIBLE,
    VALIDATING,
    SUBMITTING,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_PENDING,
    CANCELLED,
    FAILED
}

/**
 * Durable order execution record tracking all required lifecycle and fill verification fields.
 */
data class KalshiOrderRecord(
    val clientOrderId: String,
    val orderId: String? = null,
    val ticker: String,
    val side: String, // "bid" or "ask"
    val action: String = "buy",
    val requestedCount: Int,
    val filledCount: Int = 0,
    val remainingCount: Int = requestedCount,
    val limitPriceCents: Int,
    val averageFillPriceCents: Double? = null,
    val feesCents: Double = 0.0,
    val lifecycleState: OrderLifecycleState = OrderLifecycleState.ELIGIBLE,
    val placedTimestamp: Long = System.currentTimeMillis(),
    val updatedTimestamp: Long = System.currentTimeMillis(),
    val failureReason: String? = null
)

/**
 * Realized profit and loss ledger entry for durable capital tracking.
 */
data class RealizedProfitLedgerEntry(
    val tradeId: String,
    val contractTicker: String,
    val orderId: String,
    val clientOrderId: String,
    val entryCostDollars: Double,
    val settlementPriceDollars: Double,
    val feesDollars: Double,
    val realizedPnlDollars: Double,
    val timestamp: Long,
    val capitalSource: String, // "STARTING_CAPITAL" or "REALIZED_PROFIT"
    val eligibleNextTradeCapitalDollars: Double,
    val isWin: Boolean
)

/**
 * User balance info from /portfolio/balance
 */
data class KalshiBalance(
    val balanceCents: Long = 0L,
    val portfolioValueCents: Long = 0L,
    val updatedTimestampMs: Long = 0L
) {
    val balanceDollars: Double get() = balanceCents / 100.0
    val portfolioValueDollars: Double get() = portfolioValueCents / 100.0
}

/**
 * User position on a specific Kalshi market from /portfolio/positions
 */
data class KalshiPosition(
    val ticker: String,
    val position: Int, // positive = YES, negative = NO
    val marketExposureCents: Long = 0L,
    val feesPaidCents: Long = 0L,
    val realizedPnlCents: Long = 0L,
    val restingOrdersCount: Int = 0
)

/**
 * Individual price level in the Kalshi order book.
 */
data class KalshiOrderBookLevel(
    val priceCents: Int,
    val priceDollars: Double,
    val quantity: Double
)

/**
 * Snapshot of the Kalshi order book for an active 15m contract.
 */
data class KalshiOrderBookSnapshot(
    val ticker: String,
    val timestampMs: Long,
    val yesBids: List<KalshiOrderBookLevel> = emptyList(),
    val noBids: List<KalshiOrderBookLevel> = emptyList(),
    val bestYesBidCents: Int? = null,
    val bestNoBidCents: Int? = null,
    val impliedYesAskCents: Int? = null,
    val impliedNoAskCents: Int? = null,
    val totalYesDepth: Double = 0.0,
    val totalNoDepth: Double = 0.0,
    val status: String = "EMPTY_BOOK" // "LIVE", "EMPTY_BOOK", "DATA_UNAVAILABLE"
)

/**
 * Result of independent order-book verification against QtY predictions.
 */
data class KalshiVerificationResult(
    val ticker: String,
    val timestampMs: Long,
    val marketPriceCents: Int? = null,
    val yesMidPriceCents: Double? = null,
    val noMidPriceCents: Double? = null,
    val marketImpliedProbability: Double? = null,
    val marketBias: String = "UNAVAILABLE", // "UP", "DOWN", "NEUTRAL", "UNAVAILABLE"
    val bookImbalanceRatio: Double? = null,
    val totalYesDepth: Double = 0.0,
    val totalNoDepth: Double = 0.0,
    val agreement30s: String = "UNCONFIRMED", // "AGREEMENT", "DISAGREEMENT", "NEUTRAL", "UNCONFIRMED"
    val agreement90s: String = "UNCONFIRMED", // "AGREEMENT", "DISAGREEMENT", "NEUTRAL", "UNCONFIRMED"
    val verificationSummary: String = "UNCONFIRMED", // "FULL_AGREEMENT", "PARTIAL_AGREEMENT", "DIVERGENCE", "NEUTRAL", "UNCONFIRMED"
    val isStaleBook: Boolean = false,
    val isCrossedBook: Boolean = false,
    val detailExplanation: String = "Awaiting Kalshi verification data"
)

/**
 * Complete real-time automation state.
 */
data class KalshiAutomationState(
    val isAutomationEnabled: Boolean = false, // MUST BE FALSE BY DEFAULT
    val isAuthenticated: Boolean = false,
    val balance: KalshiBalance = KalshiBalance(),
    val activeContract: KalshiMarket? = null,
    val contractValidationMessage: String = "Automation OFF",
    val activePositions: List<KalshiPosition> = emptyList(),
    val recentOrders: List<KalshiOrderResponse> = emptyList(),
    val latestOrderBook: KalshiOrderBookSnapshot? = null,
    val latestVerification: KalshiVerificationResult? = null,
    val lastOrderSubmittedAt: Long = 0L,
    val lastOrderStatus: String? = null,
    val error: String? = null,
    val executionLog: List<String> = emptyList(),
    val activeOrderRecords: List<KalshiOrderRecord> = emptyList(),
    val lastLifecycleState: OrderLifecycleState? = null,
    val isReconciliationFailed: Boolean = false
)

/**
 * Result of the final fail-closed execution gate evaluation (Correction Pass 4/4 Mandate 3).
 * Must be the LAST authority before order submission.
 */
sealed class ExecutionGateDecision {
    data class Submit(
        val market: KalshiMarket,
        val clientOrderId: String,
        val targetSide: String,
        val displaySide: String,
        val orderCount: Int,
        val executablePriceCents: Int,
        val riskEvaluation: RiskEvaluation,
        val calculatedEdge: Double
    ) : ExecutionGateDecision()

    data class Reject(val reason: String) : ExecutionGateDecision()
}

