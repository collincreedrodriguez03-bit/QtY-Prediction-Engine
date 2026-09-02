package com.example.data

/**
 * Represents a single BTC market price sample with millisecond-precision timestamp.
 */
data class PricePoint(
    val price: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val exchange: String = "BINANCE",
    val volume: Double = 0.0,
    val bidPrice: Double = price,
    val askPrice: Double = price
)

/**
 * Result of cross-exchange comparison.
 */
data class ExchangeComparison(
    val binancePrice: Double?,
    val krakenPrice: Double?,
    val bitstampPrice: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val divergencePercent: Double = 0.0,
    val agreementStatus: ExchangeAgreementStatus = ExchangeAgreementStatus.STRONG_AGREEMENT,
    val confidenceAdjustment: Double = 0.05
)

enum class ExchangeAgreementStatus {
    STRONG_AGREEMENT,
    MODERATE_AGREEMENT,
    DISAGREEMENT,
    SINGLE_EXCHANGE
}
