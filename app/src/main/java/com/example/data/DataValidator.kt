package com.example.data

sealed class ValidationResult {
    data class Valid(val pricePoint: PricePoint) : ValidationResult()
    data class Invalid(val reason: String, val rawPricePoint: PricePoint?) : ValidationResult()
}

class DataValidator(
    private val maxAgeMillis: Long = 5000L // Stale threshold: 5 seconds
) {
    private val TAG = "QtY_DataValidator"

    /**
     * Validates a PricePoint for non-zero positive price, valid timestamp, and staleness.
     */
    fun validate(pricePoint: PricePoint?, currentTimestamp: Long = System.currentTimeMillis()): ValidationResult {
        if (pricePoint == null) {
            val msg = "Rejected: PricePoint is null"
            SafeLog.w(TAG, msg)
            return ValidationResult.Invalid(msg, null)
        }

        if (pricePoint.price.isNaN() || pricePoint.price.isInfinite() || pricePoint.price <= 0.0) {
            val msg = "Rejected: Invalid price value ${pricePoint.price}"
            SafeLog.w(TAG, msg)
            return ValidationResult.Invalid(msg, pricePoint)
        }

        if (pricePoint.timestamp <= 0L) {
            val msg = "Rejected: Invalid timestamp ${pricePoint.timestamp}"
            SafeLog.w(TAG, msg)
            return ValidationResult.Invalid(msg, pricePoint)
        }

        val age = currentTimestamp - pricePoint.timestamp
        if (age < 0) {
            // Future timestamp by more than 1 second (clock drift)
            if (kotlin.math.abs(age) > 1000L) {
                val msg = "Rejected: Timestamp in future ($age ms ahead)"
                SafeLog.w(TAG, msg)
                return ValidationResult.Invalid(msg, pricePoint)
            }
        } else if (age > maxAgeMillis) {
            val msg = "Rejected: Stale price ($age ms old > max ${maxAgeMillis}ms)"
            SafeLog.w(TAG, msg)
            return ValidationResult.Invalid(msg, pricePoint)
        }

        return ValidationResult.Valid(pricePoint)
    }

    /**
     * Cross-validates prices from multiple exchanges to detect anomalies or divergence.
     */
    fun validateCrossExchange(
        binance: PricePoint?,
        kraken: PricePoint?,
        bitstamp: PricePoint? = null
    ): ExchangeComparison {
        val bPrice = binance?.price?.takeIf { it > 0.0 }
        val kPrice = kraken?.price?.takeIf { it > 0.0 }
        val sPrice = bitstamp?.price?.takeIf { it > 0.0 }

        if (bPrice != null && kPrice != null) {
            val diff = kotlin.math.abs(bPrice - kPrice)
            val divergencePct = (diff / bPrice) * 100.0

            val (status, adj) = when {
                divergencePct <= 0.10 -> Pair(ExchangeAgreementStatus.STRONG_AGREEMENT, 0.05)
                divergencePct > 0.50 -> {
                    SafeLog.w(TAG, "Exchange price divergence detected: Binance=$bPrice, Kraken=$kPrice (${String.format("%.3f", divergencePct)}%)")
                    Pair(ExchangeAgreementStatus.DISAGREEMENT, -0.10)
                }
                else -> Pair(ExchangeAgreementStatus.MODERATE_AGREEMENT, 0.0)
            }

            return ExchangeComparison(
                binancePrice = bPrice,
                krakenPrice = kPrice,
                bitstampPrice = sPrice,
                divergencePercent = divergencePct,
                agreementStatus = status,
                confidenceAdjustment = adj
            )
        }

        // Single exchange fallback
        return ExchangeComparison(
            binancePrice = bPrice,
            krakenPrice = kPrice,
            bitstampPrice = sPrice,
            divergencePercent = 0.0,
            agreementStatus = ExchangeAgreementStatus.SINGLE_EXCHANGE,
            confidenceAdjustment = 0.0
        )
    }
}
