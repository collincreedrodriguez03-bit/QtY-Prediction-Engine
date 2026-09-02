package com.example.engine

import com.example.data.PricePoint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Validated Mathematical Indicator Calculator for Real-Time BTC Scalping.
 * Implements exact formulas for EMA, RSI, Momentum, Velocity, Acceleration,
 * Volatility, Volume surge, Buffer, and Bid-Ask Spread.
 */
class IndicatorCalculator {

    /**
     * Exponential Moving Average (EMA).
     * Formula: EMA = (Price * alpha) + (Previous_EMA * (1 - alpha))
     * alpha = 2 / (N + 1)
     */
    fun calculateEMA(prices: List<Double>, period: Int, previousEMA: Double? = null): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size < period && previousEMA == null) {
            // Graceful fallback to Simple Moving Average if insufficient periods
            return prices.average()
        }

        val alpha = 2.0 / (period + 1.0)

        if (previousEMA != null) {
            val currentPrice = prices.last()
            return (currentPrice * alpha) + (previousEMA * (1.0 - alpha))
        }

        // Calculate series EMA from beginning
        var ema = prices.take(period).average()
        for (i in period until prices.size) {
            ema = (prices[i] * alpha) + (ema * (1.0 - alpha))
        }
        return ema
    }

    /**
     * Relative Strength Index (RSI) over 14 periods.
     * Wilder's Smoothing method.
     * Range: 0.0 to 100.0
     */
    fun calculateRSI(prices: List<Double>, period: Int = 14): Double {
        if (prices.size < 2) return 50.0 // Neutral default for insufficient data

        val changes = mutableListOf<Double>()
        for (i in 1 until prices.size) {
            changes.add(prices[i] - prices[i - 1])
        }

        if (changes.size < period) {
            // Simple RSI for early data
            var gainSum = 0.0
            var lossSum = 0.0
            for (change in changes) {
                if (change > 0) gainSum += change
                else lossSum += abs(change)
            }
            if (lossSum == 0.0 && gainSum == 0.0) return 50.0
            if (lossSum == 0.0) return 100.0
            val rs = gainSum / lossSum
            return 100.0 - (100.0 / (1.0 + rs))
        }

        // Wilder's RSI calculation
        var avgGain = 0.0
        var avgLoss = 0.0

        for (i in 0 until period) {
            val change = changes[i]
            if (change > 0) avgGain += change
            else avgLoss += abs(change)
        }
        avgGain /= period
        avgLoss /= period

        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = ((avgGain * (period - 1)) + gain) / period
            avgLoss = ((avgLoss * (period - 1)) + loss) / period
        }

        if (avgLoss == 0.0 && avgGain == 0.0) return 50.0
        if (avgLoss == 0.0) return 100.0
        if (avgGain == 0.0) return 0.0

        val rs = avgGain / avgLoss
        val rsi = 100.0 - (100.0 / (1.0 + rs))
        return rsi.coerceIn(0.0, 100.0)
    }

    /**
     * Momentum = Current_Price - Price(lookback periods ago)
     * Lookback: 5 periods
     */
    fun calculateMomentum(prices: List<Double>, lookback: Int = 5): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size <= lookback) {
            return prices.last() - prices.first()
        }
        val currentPrice = prices.last()
        val pastPrice = prices[prices.size - 1 - lookback]
        return currentPrice - pastPrice
    }

    /**
     * Velocity = (Current_Price - Previous_Price) / Time_Delta_Seconds
     */
    fun calculateVelocity(current: PricePoint, previous: PricePoint?): Double {
        if (previous == null) return 0.0
        val timeDeltaSeconds = (current.timestamp - previous.timestamp) / 1000.0
        if (timeDeltaSeconds <= 0.0) return 0.0
        return (current.price - previous.price) / timeDeltaSeconds
    }

    /**
     * Acceleration = (Current_Velocity - Previous_Velocity) / Time_Delta_Seconds
     */
    fun calculateAcceleration(currentVelocity: Double, previousVelocity: Double, timeDeltaSeconds: Double): Double {
        if (timeDeltaSeconds <= 0.0) return 0.0
        return (currentVelocity - previousVelocity) / timeDeltaSeconds
    }

    /**
     * Short-Term Volatility (10-period standard deviation of prices)
     */
    fun calculateVolatility(prices: List<Double>, period: Int = 10): Double {
        if (prices.size < 2) return 0.0
        val sample = if (prices.size <= period) prices else prices.takeLast(period)
        val mean = sample.average()
        val variance = sample.sumOf { (it - mean).pow(2) } / sample.size
        return sqrt(variance)
    }

    /**
     * Volume surge = Current_Volume / Average_Volume(last 5 periods)
     */
    fun calculateVolumeSurge(volumes: List<Double>, lookback: Int = 5): Double {
        if (volumes.isEmpty()) return 1.0
        val current = volumes.last()
        if (volumes.size <= 1) return 1.0
        val history = volumes.dropLast(1).takeLast(lookback)
        val avg = history.average()
        return if (avg > 0.0) current / avg else 1.0
    }

    /**
     * Strike / Price Buffer = Current_Price - Reference_Price (e.g. Anchor / Session Open)
     */
    fun calculateBuffer(currentPrice: Double, referencePrice: Double): Double {
        return currentPrice - referencePrice
    }

    /**
     * Bid-Ask Spread = Ask_Price - Bid_Price
     */
    fun calculateSpread(askPrice: Double, bidPrice: Double): Double {
        return (askPrice - bidPrice).coerceAtLeast(0.0)
    }

    /**
     * Computes the complete IndicatorSnapshot from the price history.
     */
    fun computeSnapshot(
        points: List<PricePoint>,
        referencePrice: Double? = null,
        previousVelocity: Double = 0.0,
        exchangeAgreement: String = "STRONG_AGREEMENT"
    ): IndicatorSnapshot {
        if (points.isEmpty()) {
            return IndicatorSnapshot(
                ema9 = 0.0,
                ema21 = 0.0,
                rsi = 50.0,
                momentum = 0.0,
                velocity = 0.0,
                acceleration = 0.0,
                volatility = 0.0,
                volume = 0.0,
                volumeChange = 1.0,
                buffer = 0.0,
                bidAskSpread = 0.0,
                exchangeAgreement = exchangeAgreement,
                formulaDisplay = "NO DATA"
            )
        }

        val prices = points.map { it.price }
        val volumes = points.map { it.volume }
        val currentPoint = points.last()
        val prevPoint = if (points.size >= 2) points[points.size - 2] else null

        val ema9 = calculateEMA(prices, 9)
        val ema21 = calculateEMA(prices, 21)
        val rsi = calculateRSI(prices, 14)
        val momentum = calculateMomentum(prices, 5)

        val timeDelta = if (prevPoint != null) {
            (currentPoint.timestamp - prevPoint.timestamp) / 1000.0
        } else 2.0
        val velocity = calculateVelocity(currentPoint, prevPoint)
        val acceleration = calculateAcceleration(velocity, previousVelocity, if (timeDelta > 0) timeDelta else 2.0)
        val volatility = calculateVolatility(prices, 10)
        val volume = currentPoint.volume
        val volumeChange = calculateVolumeSurge(volumes, 5)
        val refPrice = referencePrice ?: points.first().price
        val buffer = calculateBuffer(currentPoint.price, refPrice)
        val spread = calculateSpread(currentPoint.askPrice, currentPoint.bidPrice)

        return IndicatorSnapshot(
            ema9 = ema9,
            ema21 = ema21,
            rsi = rsi,
            momentum = momentum,
            velocity = velocity,
            acceleration = acceleration,
            volatility = volatility,
            volume = volume,
            volumeChange = volumeChange,
            buffer = buffer,
            bidAskSpread = spread,
            exchangeAgreement = exchangeAgreement
        )
    }
}
