package com.example.engine.horizon

import com.example.engine.HorizonForecast
import com.example.engine.HorizonProvenance
import com.example.engine.IndicatorSnapshot
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Executes the clean horizon-model architecture:
 * X_h(t) -> latent state -> expected return -> uncertainty -> decision.
 *
 * Mandates:
 * - Independent calculation for every canonical horizon (no multiplying 30s result).
 * - Target math:
 *     actualReturn_h = ln(P[t+h] / P[t])
 *     predictedReturn_h = model(X_t, h)
 *     predictedPrice_h = P_t * exp(predictedReturn_h)
 * - Uses data-learned normalization parameters and regression coefficients.
 * - Stores predicted return, predicted price, uncertainty, standardized score, direction, eligibility, maturity.
 */
class HorizonModelEngine(
    private val registry: HorizonModelRegistry = HorizonModelRegistry.shared
) {

    data class ComputationResult(
        val predictedReturn: Double,
        val predictedPrice: Double,
        val uncertainty: Double,
        val standardizedScore: Double,
        val score: Double, // mapped to [0.0, 1.0]
        val decision: String, // "UP", "DOWN", "NO-TRADE"
        val strength: String, // "WEAK", "MEDIUM", "STRONG"
        val latentState: Double
    )

    /**
     * Evaluates X_h(t) -> latent state -> expected return -> uncertainty -> decision.
     */
    fun compute(
        horizonSeconds: Int,
        currentPrice: Double,
        snapshot: IndicatorSnapshot,
        params: HorizonModelParameters = registry.getParameters(horizonSeconds)
    ): ComputationResult {
        if (currentPrice <= 0.0) {
            return ComputationResult(
                predictedReturn = 0.0,
                predictedPrice = currentPrice,
                uncertainty = 1.0,
                standardizedScore = 0.0,
                score = 0.5,
                decision = "NO-TRADE",
                strength = "WEAK",
                latentState = 0.0
            )
        }

        // 1. Raw Feature Vector X_t normalized by current price where dimensional
        val rawEmaDiff = (snapshot.ema9 - snapshot.ema21) / currentPrice
        val rawRsi = (snapshot.rsi - 50.0) / 100.0
        val rawMom = snapshot.momentum / currentPrice
        val rawVel = snapshot.velocity / currentPrice
        val rawVol = snapshot.volatility / currentPrice
        val rawVolChange = (snapshot.volumeChange ?: 1.0) - 1.0
        val rawBuffer = (snapshot.buffer ?: 0.0) / currentPrice

        // 2. Data-Learned Standardization z_k = (x_k - mu_k) / sigma_k
        // Guarantees zero arbitrary hardcoded constants (0.0008, 0.0003, 0.0005 are purged)
        val zEma = (rawEmaDiff - params.meanEmaDiff) / safeStd(params.stdEmaDiff)
        val zRsi = (rawRsi - params.meanRsi) / safeStd(params.stdRsi)
        val zMom = (rawMom - params.meanMomentum) / safeStd(params.stdMomentum)
        val zVel = (rawVel - params.meanVelocity) / safeStd(params.stdVelocity)
        val zVol = (rawVol - params.meanVolatility) / safeStd(params.stdVolatility)
        val zVolChange = (rawVolChange - params.meanVolumeChange) / safeStd(params.stdVolumeChange)
        val zBuffer = (rawBuffer - params.meanBuffer) / safeStd(params.stdBuffer)

        // 3. Latent State: L_h = beta0 + sum(beta_k * z_k)
        val latentState = params.intercept +
                (params.betaEma * zEma) +
                (params.betaRsi * zRsi) +
                (params.betaMomentum * zMom) +
                (params.betaVelocity * zVel) +
                (params.betaVolatility * zVol) +
                (params.betaVolume * zVolChange) +
                (params.betaBuffer * zBuffer)

        // 4. Expected Return: predictedReturn_h = tanh(L_h) * maxReturnCap_h
        val predictedReturn = tanh(latentState) * params.maxReturnCap

        // 5. Predicted Price: predictedPrice_h = P_t * exp(predictedReturn_h)
        val predictedPrice = currentPrice * exp(predictedReturn)

        // 6. Uncertainty: residual standard error adjusted for market volatility dispersion
        val volDispersion = if (zVol > 0.0) zVol * params.volLambda else 0.0
        val uncertainty = sqrt((params.residualStd * params.residualStd) + (volDispersion * volDispersion))

        // 7. Standardized Score: Z_h = predictedReturn_h / uncertainty_h
        val standardizedScore = if (uncertainty > 1e-9) predictedReturn / uncertainty else 0.0

        // Continuous score mapped to [0.0, 1.0] via sigmoid
        val score = 1.0 / (1.0 + exp(-standardizedScore))

        // 8. Decision: Determined by standardized score against data-calibrated critical value
        val decision = when {
            standardizedScore >= params.decisionThreshold -> "UP"
            standardizedScore <= -params.decisionThreshold -> "DOWN"
            else -> "NO-TRADE"
        }

        // 9. Strength category
        val absZ = abs(standardizedScore)
        val strength = when {
            absZ >= 2.0 -> "STRONG"
            absZ >= params.decisionThreshold -> "MEDIUM"
            else -> "WEAK"
        }

        return ComputationResult(
            predictedReturn = predictedReturn,
            predictedPrice = Math.round(predictedPrice * 100.0) / 100.0,
            uncertainty = uncertainty,
            standardizedScore = standardizedScore,
            score = Math.round(score * 10000.0) / 10000.0,
            decision = decision,
            strength = strength,
            latentState = latentState
        )
    }

    /**
     * Builds a full HorizonForecast record for a given canonical horizon.
     */
    fun createForecast(
        horizonSeconds: Int,
        inputTimestamp: Long,
        currentPrice: Double,
        snapshot: IndicatorSnapshot,
        settlementReference: Double = currentPrice,
        provenance: HorizonProvenance,
        params: HorizonModelParameters = registry.getParameters(horizonSeconds)
    ): HorizonForecast {
        val maturityTimestamp = inputTimestamp + (horizonSeconds * 1000L)
        val result = compute(horizonSeconds, currentPrice, snapshot, params)

        return HorizonForecast(
            horizonSeconds = horizonSeconds,
            inputTimestamp = inputTimestamp,
            maturityTimestamp = maturityTimestamp,
            modelVersion = params.modelVersion,
            predictedReturn = result.predictedReturn,
            predictedPrice = result.predictedPrice,
            uncertainty = result.uncertainty,
            standardizedScore = result.standardizedScore,
            score = result.score,
            decision = result.decision,
            direction = result.decision,
            eligibility = if (currentPrice > 0.0) "ELIGIBLE" else "INELIGIBLE_MISSING_PRICE",
            strength = result.strength,
            currentPrice = currentPrice,
            settlementReference = settlementReference,
            featureSnapshot = snapshot,
            provenance = provenance
        )
    }

    /**
     * Calculates authentic log return: actualReturn_h = ln(P[t+h] / P[t])
     */
    fun calculateActualReturn(currentPrice: Double, actualPriceAtMaturity: Double): Double? {
        if (currentPrice <= 0.0 || actualPriceAtMaturity <= 0.0) return null
        return ln(actualPriceAtMaturity / currentPrice)
    }

    private fun safeStd(std: Double): Double = if (std > 1e-8) std else 1e-4

    companion object {
        val shared = HorizonModelEngine()
    }
}
