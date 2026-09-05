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
 * Order submission request to Kalshi V2 /portfolio/orders
 */
data class KalshiOrderRequest(
    val ticker: String,
    val action: String, // "buy" | "sell"
    val side: String, // "yes" | "no"
    val type: String = "limit", // "limit"
    val count: Int,
    val yesPrice: Int? = null,
    val noPrice: Int? = null,
    val clientOrderId: String,
    val timeInForce: String = "good_till_canceled"
)

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
    val placeTimeMs: Long
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
    val executionLog: List<String> = emptyList()
)
