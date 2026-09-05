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
    val askPrice: Double = price,
    val quoteCurrency: String = "USD",
    val baseVolumeBtc: Double = volume,
    val quoteVolumeUsd: Double = 0.0,
    val exchangeTimestamp: Long = timestamp,
    val instrument: String = if (quoteCurrency.equals("USDT", ignoreCase = true)) "BTC-USDT" else "BTC-USD",
    val isRestSnapshot: Boolean = false,
    val localReceiptTimestamp: Long = timestamp
) {
    val sourceKey: String get() = "${exchange}:${instrument}"
}

/**
 * Result of cross-exchange comparison.
 */
data class ExchangeComparison(
    val binancePrice: Double?,
    val krakenPrice: Double?,
    val coinbasePrice: Double? = null,
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
