package com.example.engine

import com.example.engine.horizon.HorizonModelEngine
import com.example.engine.horizon.HorizonModelParameters
import com.example.engine.horizon.HorizonModelRegistry
import kotlin.math.tanh

/**
 * Validated Quantitative Prediction Engine for BTC Scalping.
 * Implements clean data-learned horizon-model architecture:
 * X_h(t) -> latent state -> expected return -> uncertainty -> decision.
 *
 * Mandates:
 * - Data-learned normalization scales (purges arbitrary 0.0008, 0.0003, 0.0005).
 * - Independent calculations per canonical horizon (5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s).
 * - Zero horizon scaling / 30s -> 90s multiplier projections.
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
    val predictionHorizonSeconds: Int = 30,
    val modelRegistry: HorizonModelRegistry = HorizonModelRegistry.shared,
    val horizonModelEngine: HorizonModelEngine = HorizonModelEngine(modelRegistry)
) {

    private val baselineParams: HorizonModelParameters
        get() = modelRegistry.getParameters(predictionHorizonSeconds)

    /**
     * Normalizes EMA relationship to [0.0, 1.0] signal.
     * Evaluates EMA9 vs EMA21 and Price vs EMA9.
     */
    fun normalizeEmaSignal(currentPrice: Double, ema9: Double, ema21: Double): Double {
        if (currentPrice <= 0.0 || ema9 <= 0.0 || ema21 <= 0.0) return 0.5
        val diff = ema9 - ema21
        val scale = currentPrice * baselineParams.stdEmaDiff
        val z = if (scale > 1e-8) diff / scale else 0.0
        return (0.5 + (tanh(z) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes RSI to [0.0, 1.0] signal.
     * 50 -> 0.5 (neutral), 70+ -> bullish momentum in scalping context, 30- -> bearish.
     */
    fun normalizeRsiSignal(rsi: Double): Double {
        return (rsi / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Momentum using data-learned scale (replaces arbitrary 0.0008).
     */
    fun normalizeMomentumSignal(momentum: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scale = currentPrice * baselineParams.stdMomentum
        val z = if (scale > 1e-8) momentum / scale else 0.0
        return (0.5 + (tanh(z) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Velocity using data-learned scale (replaces arbitrary 0.0003).
     */
    fun normalizeVelocitySignal(velocity: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scale = currentPrice * baselineParams.stdVelocity
        val z = if (scale > 1e-8) velocity / scale else 0.0
        return (0.5 + (tanh(z) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Normalizes Volatility adjustment using data-learned scale (replaces arbitrary 0.0005).
     */
    fun normalizeVolatilitySignal(volatility: Double, currentPrice: Double, trendDirection: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val charScale = currentPrice * baselineParams.stdVolatility
        val z = if (charScale > 1e-8) volatility / charScale else 0.0
        val volMultiplier = tanh(z) * 0.5
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
     * Normalizes Buffer using data-learned scale (replaces arbitrary 0.005).
     */
    fun normalizeBufferSignal(buffer: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scale = currentPrice * baselineParams.stdBuffer
        val z = if (scale > 1e-8) buffer / scale else 0.0
        return (0.5 + (tanh(z) * 0.5)).coerceIn(0.0, 1.0)
    }

    /**
     * Generates a prediction from the current IndicatorSnapshot and market price.
     * Uses frozen validated v1 baseline weights.
     * Records prospective calibrated factor offsets and v2 advisory score for parallel evaluation.
     */
    fun predict(
        currentPrice: Double,
        snapshot: IndicatorSnapshot,
        timestamp: Long = System.currentTimeMillis(),
        learningBias: Double = 0.0,
        factorOffsets: Map<String, Double> = emptyMap(),
        settlementReference: Double = currentPrice,
        researchFeatures: com.example.engine.external.ExternalPredictionFeatures? = null
    ): PredictionRecord {
        val emaSignal = normalizeEmaSignal(currentPrice, snapshot.ema9, snapshot.ema21)
        val rsiSignal = normalizeRsiSignal(snapshot.rsi)
        val momSignal = normalizeMomentumSignal(snapshot.momentum, currentPrice)
        val velSignal = normalizeVelocitySignal(snapshot.velocity, currentPrice)
        val volSignal = normalizeVolatilitySignal(snapshot.volatility, currentPrice, emaSignal)
        val volumeSignal = normalizeVolumeSignal(snapshot.volumeChange, snapshot.momentum)
        val bufSignal = normalizeBufferSignal(snapshot.buffer, currentPrice)

        // 1. Frozen Validated v1 Base Weights (MANDATORY: Never altered during prospective testing)
        val totalBaseW = weightEma + weightRsi + weightMomentum + weightVelocity + weightVol + weightVolume + weightBuffer
        val normEma = weightEma / totalBaseW
        val normRsi = weightRsi / totalBaseW
        val normMom = weightMomentum / totalBaseW
        val normVel = weightVelocity / totalBaseW
        val normVol = weightVol / totalBaseW
        val normBuf = weightBuffer / totalBaseW
        val normVolume = weightVolume / totalBaseW

        // Raw weighted combination for v1 (Frozen)
        val rawScore = (
            emaSignal * normEma +
            rsiSignal * normRsi +
            momSignal * normMom +
            velSignal * normVel +
            volSignal * normVol +
            volumeSignal * normVolume +
            bufSignal * normBuf
        )

        // 2. Calibrated v2 Weights (Recorded in parallel for side-by-side comparison)
        val effEma = (weightEma + (factorOffsets["EMA"] ?: 0.0)).coerceAtLeast(0.05)
        val effRsi = (weightRsi + (factorOffsets["RSI"] ?: 0.0)).coerceAtLeast(0.05)
        val effMom = (weightMomentum + (factorOffsets["MOMENTUM"] ?: 0.0)).coerceAtLeast(0.05)
        val effVel = (weightVelocity + (factorOffsets["VELOCITY"] ?: 0.0)).coerceAtLeast(0.05)
        val effVol = (weightVol + (factorOffsets["VOLATILITY"] ?: 0.0)).coerceAtLeast(0.02)
        val effBuf = (weightBuffer + (factorOffsets["BUFFER"] ?: 0.0)).coerceAtLeast(0.02)
        val totalCalW = effEma + effRsi + effMom + effVel + effVol + weightVolume + effBuf

        val calScore = (
            emaSignal * (effEma / totalCalW) +
            rsiSignal * (effRsi / totalCalW) +
            momSignal * (effMom / totalCalW) +
            velSignal * (effVel / totalCalW) +
            volSignal * (effVol / totalCalW) +
            volumeSignal * (weightVolume / totalCalW) +
            bufSignal * (effBuf / totalCalW)
        ) + learningBias

        // Exchange agreement confidence adjustment
        val agreementAdjustment = when (snapshot.exchangeAgreement) {
            "STRONG_AGREEMENT" -> 0.02
            "DISAGREEMENT" -> -0.05
            else -> 0.0
        }

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

        // Expected return calculation for primary 30s frozen baseline
        val priceOffset = (adjustedScore - 0.5) * 2.0 * maxOf(10.0, currentPrice * 0.001)
        val predictedPrice = currentPrice + priceOffset

        // Generate visual mathematics display
        val mathFormula = buildMathDisplay(
            emaSignal, rsiSignal, momSignal, velSignal, volSignal, volumeSignal, bufSignal,
            normEma, normRsi, normMom, normVel, normVol, normBuf,
            adjustedScore, decision
        )

        val updatedSnapshot = snapshot.copy(formulaDisplay = mathFormula)

        // Generate independent canonical multi-horizon forecasts: 5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s
        val allHorizons = calculateAllHorizons(
            currentPrice = currentPrice,
            snapshot = updatedSnapshot,
            timestamp = timestamp,
            settlementReference = settlementReference,
            researchFeatures = researchFeatures
        )

        return PredictionRecord(
            timestamp = timestamp,
            inputs = updatedSnapshot,
            decision = decision,
            score = Math.round(adjustedScore * 1000.0) / 1000.0,
            strength = strength,
            predictedPrice = Math.round(predictedPrice * 100.0) / 100.0,
            currentPrice = Math.round(currentPrice * 100.0) / 100.0,
            settlementReference = Math.round(settlementReference * 100.0) / 100.0,
            predictionHorizon = predictionHorizonSeconds,
            maturityTimestamp = timestamp + (predictionHorizonSeconds * 1000L),
            calibratedScore = Math.round(calScore.coerceIn(0.0, 1.0) * 1000.0) / 1000.0,
            researchExternalFeatures = researchFeatures,
            horizonForecasts = allHorizons
        )
    }

    /**
     * Calculates an independent econometric forecast for a given canonical time horizon.
     * Mandates 1, 2, 3, 4, 5, 6:
     * - Independent calculation for every canonical horizon (no multiplier/scaling from other horizons).
     * - Data-learned parameters per horizon.
     * - Real horizon targets: expectedReturn, predictedPrice = P_t * exp(expectedReturn), uncertainty, standardizedScore.
     */
    fun calculateHorizonForecast(
        horizonSeconds: Int,
        currentPrice: Double,
        snapshot: IndicatorSnapshot,
        timestamp: Long = System.currentTimeMillis(),
        settlementReference: Double = currentPrice,
        researchFeatures: com.example.engine.external.ExternalPredictionFeatures? = null,
        sourceExchange: String = "CONSOLIDATED_USD",
        marketTimestamp: Long = timestamp
    ): HorizonForecast {
        val modelVer = if (horizonSeconds == 30) "v1.0-frozen-30s" else "v2.0-canonical-horizon-${horizonSeconds}s"
        val params = modelRegistry.getParameters(horizonSeconds).copy(modelVersion = modelVer)
        val mathFormula = "L_${horizonSeconds}s = ${params.betaEma}·z_EMA + ${params.betaRsi}·z_RSI + ${params.betaMomentum}·z_MOM + ${params.betaVelocity}·z_VEL"

        val provenance = HorizonProvenance(
            sourceExchange = sourceExchange,
            marketTimestamp = marketTimestamp,
            localReceiptTimestamp = timestamp,
            isResearchAdvisory = (horizonSeconds != 30),
            formulaDisplay = mathFormula,
            researchExternalFeatures = researchFeatures
        )

        if (horizonSeconds == 30) {
            val emaSignal = normalizeEmaSignal(currentPrice, snapshot.ema9, snapshot.ema21)
            val rsiSignal = normalizeRsiSignal(snapshot.rsi)
            val momSignal = normalizeMomentumSignal(snapshot.momentum, currentPrice)
            val velSignal = normalizeVelocitySignal(snapshot.velocity, currentPrice)
            val volSignal = normalizeVolatilitySignal(snapshot.volatility, currentPrice, emaSignal)
            val volumeSignal = normalizeVolumeSignal(snapshot.volumeChange, snapshot.momentum)
            val bufSignal = normalizeBufferSignal(snapshot.buffer, currentPrice)

            val totalBaseW = weightEma + weightRsi + weightMomentum + weightVelocity + weightVol + weightVolume + weightBuffer
            val rawScore = (
                emaSignal * (weightEma / totalBaseW) +
                rsiSignal * (weightRsi / totalBaseW) +
                momSignal * (weightMomentum / totalBaseW) +
                velSignal * (weightVelocity / totalBaseW) +
                volSignal * (weightVol / totalBaseW) +
                volumeSignal * (weightVolume / totalBaseW) +
                bufSignal * (weightBuffer / totalBaseW)
            )

            val agreementAdjustment = when (snapshot.exchangeAgreement) {
                "STRONG_AGREEMENT" -> 0.02
                "DISAGREEMENT" -> -0.05
                else -> 0.0
            }

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

            val priceOffset = (adjustedScore - 0.5) * 2.0 * maxOf(10.0, currentPrice * 0.001)
            val predictedPrice = currentPrice + priceOffset

            return HorizonForecast(
                horizonSeconds = 30,
                inputTimestamp = timestamp,
                maturityTimestamp = timestamp + 30_000L,
                modelVersion = "v1.0-frozen-30s",
                predictedReturn = if (currentPrice > 0.0) kotlin.math.ln(predictedPrice / currentPrice) else 0.0,
                predictedPrice = Math.round(predictedPrice * 100.0) / 100.0,
                uncertainty = 0.00065,
                standardizedScore = (adjustedScore - 0.5) * 4.0,
                score = Math.round(adjustedScore * 1000.0) / 1000.0,
                decision = decision,
                direction = decision,
                eligibility = if (currentPrice > 0.0) "ELIGIBLE" else "INELIGIBLE_MISSING_PRICE",
                strength = strength,
                currentPrice = Math.round(currentPrice * 100.0) / 100.0,
                settlementReference = Math.round(settlementReference * 100.0) / 100.0,
                featureSnapshot = snapshot,
                provenance = provenance
            )
        }

        return horizonModelEngine.createForecast(
            horizonSeconds = horizonSeconds,
            inputTimestamp = timestamp,
            currentPrice = currentPrice,
            snapshot = snapshot,
            settlementReference = settlementReference,
            provenance = provenance,
            params = params
        )
    }

    /**
     * Calculates all 8 canonical independent multi-horizon forecasts:
     * 5s, 10s, 30s, 60s, 120s, 300s, 600s, 900s.
     * Obsolete horizons (90s, 180s, 240s, 1200s) purged.
     */
    fun calculateAllHorizons(
        currentPrice: Double,
        snapshot: IndicatorSnapshot,
        timestamp: Long = System.currentTimeMillis(),
        settlementReference: Double = currentPrice,
        researchFeatures: com.example.engine.external.ExternalPredictionFeatures? = null,
        sourceExchange: String = "CONSOLIDATED_USD",
        marketTimestamp: Long = timestamp
    ): List<HorizonForecast> {
        return PredictionHorizon.ALL_SECONDS.map { horizon ->
            calculateHorizonForecast(
                horizonSeconds = horizon,
                currentPrice = currentPrice,
                snapshot = snapshot,
                timestamp = timestamp,
                settlementReference = settlementReference,
                researchFeatures = researchFeatures,
                sourceExchange = sourceExchange,
                marketTimestamp = marketTimestamp
            )
        }
    }

    private fun buildMathDisplay(
        ema: Double, rsi: Double, mom: Double, vel: Double, vol: Double, volSurge: Double, buf: Double,
        wEma: Double, wRsi: Double, wMom: Double, wVel: Double, wVol: Double, wBuf: Double,
        finalScore: Double, decision: String
    ): String {
        val emaVal = String.format(java.util.Locale.US, "%.2f", ema)
        val rsiVal = String.format(java.util.Locale.US, "%.2f", rsi)
        val momVal = String.format(java.util.Locale.US, "%.2f", mom)
        val velVal = String.format(java.util.Locale.US, "%.2f", vel)
        val volVal = String.format(java.util.Locale.US, "%.2f", vol)
        val bufVal = String.format(java.util.Locale.US, "%.2f", buf)
        val scoreVal = String.format(java.util.Locale.US, "%.3f", finalScore)

        val wE = String.format(java.util.Locale.US, "%.2f", wEma)
        val wR = String.format(java.util.Locale.US, "%.2f", wRsi)
        val wM = String.format(java.util.Locale.US, "%.2f", wMom)
        val wV = String.format(java.util.Locale.US, "%.2f", wVel)
        val wVo = String.format(java.util.Locale.US, "%.2f", wVol)
        val wB = String.format(java.util.Locale.US, "%.2f", wBuf)

        return "S(t) = $wE·φ_EMA($emaVal) + $wR·φ_RSI($rsiVal) + $wM·φ_MOM($momVal) + $wV·φ_VEL($velVal) + $wVo·φ_VOL($volVal) + $wB·φ_BUF($bufVal) = $scoreVal ⇒ $decision"
    }
}
