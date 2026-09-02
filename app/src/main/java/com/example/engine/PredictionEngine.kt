package com.example.engine

import kotlin.math.tanh

/**
 * Validated Quantitative Prediction Engine for BTC Scalping.
 * Combines EMA, RSI, Momentum, Velocity, Volatility, Volume, and Buffer factors.
 */
class PredictionEngine(
    val weightEma: Double = 0.25,
    val weightRsi: Double = 0.20,
    val weightMomentum: Double = 0.20,
    val weightVelocity: Double = 0.15,
    val weightVol: Double = 0.10,
    val weightVolume: Double = 0.05,
    val weightBuffer: Double = 0.05,
    val thresholdUp: Double = 0.65,
    val thresholdDown: Double = 0.35,
    val predictionHorizonSeconds: Int = 60
) {

    /**
     * Normalizes EMA relationship to [0.0, 1.0] signal.
     * Evaluates EMA9 vs EMA21 and Price vs EMA9.
     */
    fun normalizeEmaSignal(currentPrice: Double, ema9: Double, ema21: Double): Double {
        if (currentPrice <= 0.0 || ema9 <= 0.0 || ema21 <= 0.0) return 0.5
        val diff = ema9 - ema21
        val pctDiff = (diff / currentPrice) * 1000.0 // Scaled percentage
        return (0.5 + (tanh(pctDiff) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes RSI to [0.0, 1.0] signal.
     * 50 -> 0.5 (neutral), 70+ -> bullish momentum in scalping context, 30- -> bearish.
     */
    fun normalizeRsiSignal(rsi: Double): Double {
        return (rsi / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Momentum to [0.0, 1.0] signal using hyperbolic tangent.
     */
    fun normalizeMomentumSignal(momentum: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scaled = momentum / (currentPrice * 0.0008) // e.g. $80 move on $100k BTC = 1.0
        return (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Velocity to [0.0, 1.0] signal.
     */
    fun normalizeVelocitySignal(velocity: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scaled = velocity / (currentPrice * 0.0003) // e.g. $30/sec velocity
        return (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Volatility adjustment to [0.0, 1.0].
     * Moderate volatility is normal (0.5), very high volatility dampens confidence toward 0.5,
     * low volatility enables clean directional execution.
     */
    fun normalizeVolatilitySignal(volatility: Double, currentPrice: Double, trendDirection: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val volPct = (volatility / currentPrice) * 100.0
        // If high volatility, trend continuation is strong, align with trend direction
        val volMultiplier = (tanh(volPct * 5.0) * 0.5)
        return (0.5 + (trendDirection - 0.5) * volMultiplier * 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Volume Surge to [0.0, 1.0] aligned with price movement.
     */
    fun normalizeVolumeSignal(volumeSurge: Double, momentum: Double): Double {
        val surgeFactor = (volumeSurge - 1.0).coerceIn(-1.0, 2.0)
        val dir = if (momentum >= 0) 1.0 else -1.0
        return (0.5 + (surgeFactor * 0.2 * dir)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Buffer to [0.0, 1.0].
     */
    fun normalizeBufferSignal(buffer: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scaled = buffer / (currentPrice * 0.005)
        return (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Generates a prediction from the current IndicatorSnapshot and market price.
     */
    fun predict(
        currentPrice: Double,
        snapshot: IndicatorSnapshot,
        timestamp: Long = System.currentTimeMillis()
    ): PredictionRecord {
        val emaSignal = normalizeEmaSignal(currentPrice, snapshot.ema9, snapshot.ema21)
        val rsiSignal = normalizeRsiSignal(snapshot.rsi)
        val momSignal = normalizeMomentumSignal(snapshot.momentum, currentPrice)
        val velSignal = normalizeVelocitySignal(snapshot.velocity, currentPrice)
        val volSignal = normalizeVolatilitySignal(snapshot.volatility, currentPrice, emaSignal)
        val volumeSignal = normalizeVolumeSignal(snapshot.volumeChange, snapshot.momentum)
        val bufSignal = normalizeBufferSignal(snapshot.buffer, currentPrice)

        // Raw weighted combination
        val rawScore = (
            emaSignal * weightEma +
            rsiSignal * weightRsi +
            momSignal * weightMomentum +
            velSignal * weightVelocity +
            volSignal * weightVol +
            volumeSignal * weightVolume +
            bufSignal * weightBuffer
        )

        // Exchange agreement confidence adjustment
        val agreementAdjustment = when (snapshot.exchangeAgreement) {
            "STRONG_AGREEMENT" -> 0.02
            "DISAGREEMENT" -> -0.05
            else -> 0.0
        }

        // Adjust toward 0.5 if disagreement, or amplify away from 0.5 if strong agreement
        val adjustedScore = if (rawScore >= 0.5) {
            (rawScore + agreementAdjustment).coerceIn(0.0, 1.0)
        } else {
            (rawScore - agreementAdjustment).coerceIn(0.0, 1.0)
        }

        val decision = when {
            adjustedScore >= thresholdUp -> "UP"
            adjustedScore <= thresholdDown -> "DOWN"
            else -> "NO-TRADE"
        }

        val strength = when {
            adjustedScore >= 0.80 || adjustedScore <= 0.20 -> "STRONG"
            adjustedScore >= 0.70 || adjustedScore <= 0.30 -> "MEDIUM"
            else -> "WEAK"
        }

        // Calculate expected price movement over horizon (60s)
        val expectedMoveRatio = (adjustedScore - 0.5) * 0.0015 * (predictionHorizonSeconds / 60.0)
        val predictedPrice = currentPrice * (1.0 + expectedMoveRatio)

        // Generate visual mathematics display
        val mathFormula = buildMathDisplay(
            emaSignal, rsiSignal, momSignal, velSignal, volSignal, volumeSignal, bufSignal,
            adjustedScore, decision
        )

        val updatedSnapshot = snapshot.copy(formulaDisplay = mathFormula)

        return PredictionRecord(
            timestamp = timestamp,
            inputs = updatedSnapshot,
            decision = decision,
            score = Math.round(adjustedScore * 1000.0) / 1000.0,
            strength = strength,
            predictedPrice = Math.round(predictedPrice * 100.0) / 100.0,
            currentPrice = Math.round(currentPrice * 100.0) / 100.0,
            predictionHorizon = predictionHorizonSeconds,
            maturityTimestamp = timestamp + (predictionHorizonSeconds * 1000L)
        )
    }

    private fun buildMathDisplay(
        ema: Double, rsi: Double, mom: Double, vel: Double, vol: Double, volSurge: Double, buf: Double,
        finalScore: Double, decision: String
    ): String {
        val emaInt = (ema * 100).toInt()
        val rsiInt = (rsi * 100).toInt()
        val momInt = (mom * 100).toInt()
        val velInt = (vel * 100).toInt()
        val volInt = (vol * 100).toInt()
        val bufInt = (buf * 100).toInt()
        val scorePct = (finalScore * 100).toInt()

        return "${emaInt}EMA + ${rsiInt}RSI + ${momInt}MOM + ${velInt}VEL + ${volInt}VOL + ${bufInt}BUF = ${scorePct}% -> $decision"
    }
}
