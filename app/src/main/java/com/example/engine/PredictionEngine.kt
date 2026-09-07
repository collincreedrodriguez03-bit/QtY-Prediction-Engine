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
    val predictionHorizonSeconds: Int = 30
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
     *
     * Mathematical Rationale:
     * Volatility (sigma) is the sample standard deviation of BTC spot prices over a rolling
     * 10-cycle (20-second) window, computed in USD.
     * The dimensionless relative volatility is:
     *   sigma_rel = sigma / P_current
     *
     * Empirical Bitcoin micro-volatility regimes across authentic 20-second windows:
     * 1. Low Volatility:     0.5 to 1.5 bps (0.005% - 0.015% of spot; ~$3 - $14 on $90k BTC)
     * 2. Normal Volatility:  2.0 to 5.0 bps (0.020% - 0.050% of spot; ~$18 - $45 on $90k BTC)
     *    -> Median empirical 20-second spot standard deviation is ~2.5 to 3.0 bps.
     * 3. High Volatility:    6.0 to 15.0 bps (0.060% - 0.150% of spot; ~$54 - $135 on $90k BTC)
     * 4. Extreme Volatility: >= 25.0 bps (>= 0.250% of spot; >= $225 on $90k BTC; e.g. breakout/cascade)
     *
     * Defensible Normalization:
     * We calibrate the characteristic micro-volatility scale parameter:
     *   sigma_char = P_current * 0.0005 (5.0 basis points, representing the boundary between
     *   normal continuous trading and high breakout regimes).
     * The normalized dimensionless deviation is:
     *   z = sigma / sigma_char = (sigma / P_current) / 0.0005
     *
     * The asymptotic scaling function is:
     *   volMultiplier = tanh(z) * 0.5
     * - At z -> 0 (low vol): volMultiplier -> 0, volatility dampens directional amplification.
     * - At z = 0.6 (normal vol, ~3 bps): volMultiplier ~ 0.27, providing balanced trend reinforcement.
     * - At z = 1.8 (high vol, ~9 bps): volMultiplier ~ 0.47, strongly confirming momentum breakout.
     * - At z >> 3 (extreme vol, >= 25 bps): tanh saturates cleanly at 1.0 (volMultiplier = 0.5),
     *   bounding the signal safely within [0.0, 1.0] without unit divergence or overflow.
     */
    fun normalizeVolatilitySignal(volatility: Double, currentPrice: Double, trendDirection: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val charScale = currentPrice * 0.0005 // 5 basis points (0.05% of spot)
        val z = volatility / charScale
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
     * Normalizes Buffer to [0.0, 1.0].
     */
    fun normalizeBufferSignal(buffer: Double, currentPrice: Double): Double {
        if (currentPrice <= 0.0) return 0.5
        val scaled = buffer / (currentPrice * 0.005)
        return (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
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

        // Calculate expected price movement over horizon (30s)
        val expectedMoveRatio = (adjustedScore - 0.5) * 0.0015 * (predictionHorizonSeconds / 60.0)
        val predictedPrice = currentPrice * (1.0 + expectedMoveRatio)

        // Calculate authorized 90-second projected price and direction
        // Uses strictly current-time indicator signals and continuous scaling over 90s
        val horizon90sSeconds = 90
        val expectedMoveRatio90s = (adjustedScore - 0.5) * 0.0015 * (horizon90sSeconds / 60.0)
        val projectedPrice90s = currentPrice * (1.0 + expectedMoveRatio90s)
        val projectedDecision90s = when {
            decision == "DOWN" -> "DOWN"
            decision == "UP" -> "UP"
            projectedPrice90s > settlementReference && projectedPrice90s >= currentPrice -> "UP"
            projectedPrice90s < settlementReference && projectedPrice90s <= currentPrice -> "DOWN"
            projectedPrice90s < currentPrice -> "DOWN"
            projectedPrice90s > currentPrice -> "UP"
            projectedPrice90s > settlementReference -> "UP"
            projectedPrice90s < settlementReference -> "DOWN"
            else -> decision
        }

        // Generate visual mathematics display
        val mathFormula = buildMathDisplay(
            emaSignal, rsiSignal, momSignal, velSignal, volSignal, volumeSignal, bufSignal,
            normEma, normRsi, normMom, normVel, normVol, normBuf,
            adjustedScore, decision
        )

        val updatedSnapshot = snapshot.copy(formulaDisplay = mathFormula)

        // Generate independent multi-horizon forecasts: 5s, 10s, 30s, 60s, 90s, 120s, 180s, 240s, 300s, 600s, 900s, 1200s
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
            projectedPrice90s = Math.round(projectedPrice90s * 100.0) / 100.0,
            projectedDecision90s = projectedDecision90s,
            researchExternalFeatures = researchFeatures,
            horizonForecasts = allHorizons
        )
    }

    /**
     * Calculates an independent econometric forecast for a given time horizon.
     * Incorporates timescale-appropriate factor weights, velocity friction decay,
     * and sublinear volatility diffusion.
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
        val emaSignal = normalizeEmaSignal(currentPrice, snapshot.ema9, snapshot.ema21)
        val rsiSignal = normalizeRsiSignal(snapshot.rsi)
        val momSignal = normalizeMomentumSignal(snapshot.momentum, currentPrice)
        val velSignal = normalizeVelocitySignal(snapshot.velocity, currentPrice)
        val volSignal = normalizeVolatilitySignal(snapshot.volatility, currentPrice, emaSignal)
        val volumeSignal = normalizeVolumeSignal(snapshot.volumeChange, snapshot.momentum)
        val bufSignal = normalizeBufferSignal(snapshot.buffer, currentPrice)

        // 1. Independent factor weights tailored to timescale physics
        val weights = when (horizonSeconds) {
            5 -> HorizonFactorWeights(0.10, 0.10, 0.25, 0.35, 0.05, 0.10, 0.05)
            10 -> HorizonFactorWeights(0.15, 0.14, 0.25, 0.25, 0.08, 0.08, 0.05)
            30 -> HorizonFactorWeights(weightEma, weightRsi, weightMomentum, weightVelocity, weightVol, weightVolume, weightBuffer) // Frozen v1 baseline
            60 -> HorizonFactorWeights(0.28, 0.22, 0.16, 0.10, 0.10, 0.06, 0.08)
            90 -> HorizonFactorWeights(0.30, 0.23, 0.13, 0.07, 0.10, 0.06, 0.11)
            120 -> HorizonFactorWeights(0.32, 0.24, 0.10, 0.05, 0.10, 0.05, 0.14)
            180 -> HorizonFactorWeights(0.34, 0.24, 0.08, 0.03, 0.11, 0.05, 0.15)
            240 -> HorizonFactorWeights(0.35, 0.24, 0.06, 0.01, 0.12, 0.05, 0.17)
            300 -> HorizonFactorWeights(0.36, 0.24, 0.05, 0.00, 0.12, 0.05, 0.18)
            600 -> HorizonFactorWeights(0.38, 0.20, 0.03, 0.00, 0.12, 0.05, 0.22)
            900 -> HorizonFactorWeights(0.40, 0.20, 0.00, 0.00, 0.10, 0.05, 0.25)
            1200 -> HorizonFactorWeights(0.40, 0.19, 0.00, 0.00, 0.10, 0.05, 0.26)
            else -> HorizonFactorWeights(weightEma, weightRsi, weightMomentum, weightVelocity, weightVol, weightVolume, weightBuffer)
        }
        val (wEma, wRsi, wMom, wVel, wVol, wVolume, wBuf) = weights

        val totalW = wEma + wRsi + wMom + wVel + wVol + wVolume + wBuf
        val normEma = wEma / totalW
        val normRsi = wRsi / totalW
        val normMom = wMom / totalW
        val normVel = wVel / totalW
        val normVol = wVol / totalW
        val normVolume = wVolume / totalW
        val normBuf = wBuf / totalW

        val rawH = (
            emaSignal * normEma +
            rsiSignal * normRsi +
            momSignal * normMom +
            velSignal * normVel +
            volSignal * normVol +
            volumeSignal * normVolume +
            bufSignal * normBuf
        )

        val agreementAdj = when (snapshot.exchangeAgreement) {
            "STRONG_AGREEMENT" -> 0.02
            "DISAGREEMENT" -> -0.05
            else -> 0.0
        }

        val adjustedH = if (rawH >= 0.5) {
            (rawH + agreementAdj).coerceIn(0.0, 1.0)
        } else {
            (rawH - agreementAdj).coerceIn(0.0, 1.0)
        }

        val decisionH = when {
            adjustedH >= thresholdUp -> "UP"
            adjustedH <= thresholdDown -> "DOWN"
            else -> "NO-TRADE"
        }

        val strengthH = when {
            adjustedH >= 0.80 || adjustedH <= 0.20 -> "STRONG"
            adjustedH >= 0.70 || adjustedH <= 0.30 -> "MEDIUM"
            else -> "WEAK"
        }

        // 2. Independent timescale price projection (Not merely stretching 30s)
        val predPriceH = when {
            horizonSeconds == 30 -> {
                // Exact primary 30s formula
                val expectedMoveRatio = (adjustedH - 0.5) * 0.0015 * (30.0 / 60.0)
                currentPrice * (1.0 + expectedMoveRatio)
            }
            horizonSeconds <= 10 -> {
                // Micro-horizons: direct velocity impulse + micro drift
                val velocityImpulse = snapshot.velocity * horizonSeconds * 0.5
                val driftRatio = (adjustedH - 0.5) * 0.0015 * (horizonSeconds / 60.0)
                (currentPrice * (1.0 + driftRatio)) + velocityImpulse
            }
            horizonSeconds <= 180 -> {
                // Scalping horizons: exponential velocity decay + sublinear diffusion
                val decayFactor = 1.0 - Math.exp(-horizonSeconds / 45.0)
                val velocityImpulse = snapshot.velocity * 45.0 * decayFactor * 0.15
                val diffusionScale = Math.sqrt(horizonSeconds / 30.0)
                val trendDamping = 1.0 / (1.0 + (horizonSeconds - 30.0) * 0.0006)
                val driftRatio = (adjustedH - 0.5) * 0.0015 * (30.0 / 60.0) * diffusionScale * trendDamping
                (currentPrice * (1.0 + driftRatio)) + velocityImpulse
            }
            else -> {
                // Macro horizons (>= 240s): zero velocity persistence + square-root diffusion + buffer pull
                val diffusionScale = Math.sqrt(horizonSeconds / 30.0)
                val trendDamping = 1.0 / (1.0 + horizonSeconds * 0.0008)
                val driftRatio = (adjustedH - 0.5) * 0.0015 * (30.0 / 60.0) * diffusionScale * trendDamping
                val bufferPullRatio = if (currentPrice > 0.0) {
                    ((settlementReference - currentPrice) / currentPrice) * 0.03 * (horizonSeconds / 1200.0)
                } else 0.0
                currentPrice * (1.0 + driftRatio + bufferPullRatio)
            }
        }

        // For 90s, maintain direction resolution relative to settlement reference
        val finalDecisionH = if (horizonSeconds == 90) {
            when {
                decisionH == "DOWN" -> "DOWN"
                decisionH == "UP" -> "UP"
                predPriceH > settlementReference && predPriceH >= currentPrice -> "UP"
                predPriceH < settlementReference && predPriceH <= currentPrice -> "DOWN"
                predPriceH < currentPrice -> "DOWN"
                predPriceH > currentPrice -> "UP"
                predPriceH > settlementReference -> "UP"
                predPriceH < settlementReference -> "DOWN"
                else -> decisionH
            }
        } else {
            decisionH
        }

        val formulaDisplayH = buildMathDisplay(
            emaSignal, rsiSignal, momSignal, velSignal, volSignal, volumeSignal, bufSignal,
            normEma, normRsi, normMom, normVel, normVol, normBuf,
            adjustedH, finalDecisionH
        )

        val provenance = HorizonProvenance(
            sourceExchange = sourceExchange,
            marketTimestamp = marketTimestamp,
            localReceiptTimestamp = timestamp,
            isResearchAdvisory = (horizonSeconds != 30),
            formulaDisplay = formulaDisplayH,
            researchExternalFeatures = researchFeatures
        )

        return HorizonForecast(
            horizonSeconds = horizonSeconds,
            inputTimestamp = timestamp,
            maturityTimestamp = timestamp + (horizonSeconds * 1000L),
            modelVersion = if (horizonSeconds == 30) "v1.0-frozen-30s" else "v1.0-research-horizon-${horizonSeconds}s",
            score = Math.round(adjustedH * 1000.0) / 1000.0,
            decision = finalDecisionH,
            strength = strengthH,
            predictedPrice = Math.round(predPriceH * 100.0) / 100.0,
            currentPrice = Math.round(currentPrice * 100.0) / 100.0,
            settlementReference = Math.round(settlementReference * 100.0) / 100.0,
            featureSnapshot = snapshot,
            provenance = provenance
        )
    }

    /**
     * Calculates all 12 independent multi-horizon forecasts:
     * 5s, 10s, 30s, 60s, 90s, 120s, 180s, 240s, 300s, 600s, 900s, 1200s.
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
