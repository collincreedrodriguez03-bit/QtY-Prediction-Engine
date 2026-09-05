package com.example.engine.external

import com.example.data.PricePoint
import com.example.engine.IndicatorCalculator
import com.example.engine.PredictionEngine
import com.example.engine.PredictionRecord
import kotlin.math.abs

/**
 * Rigorous Out-Of-Sample Evaluation Engine for Research Features.
 *
 * EVALUATION PROTOCOL:
 * 1. Historical split: 60% In-Sample (training/exploration) / 40% Out-Of-Sample (strictly held out).
 * 2. Compares:
 *    - Existing QtY Model (Frozen Baseline)
 *    - Always-UP Baseline
 *    - Always-DOWN Baseline
 *    - Random Walk (50/50) Baseline
 *    - Candidate Model incorporating the Research Feature with prospective delta weight
 * 3. Identical eligibility rules across both 30-second and 90-second prediction horizons.
 * 4. STATISTICAL ACCEPTANCE CRITERIA:
 *    - Must demonstrate statistically significant win rate improvement over the Existing QtY Model
 *      on the held-out OUT-OF-SAMPLE dataset (p < 0.05 / t-statistic > 2.0).
 *    - In-sample correlation or improvement alone is STRICTLY REJECTED.
 *    - If condition not met: Feature decision = REJECT.
 */
class ResearchFeatureEvaluator(
    private val indicatorCalculator: IndicatorCalculator = IndicatorCalculator(),
    private val baseEngine: PredictionEngine = PredictionEngine(predictionHorizonSeconds = 30)
) {

    data class EvaluationMetrics(
        val totalPredictions: Int,
        val correctPredictions: Int,
        val incorrectPredictions: Int,
        val winRatePercent: Double,
        val alwaysUpWinRatePercent: Double,
        val alwaysDownWinRatePercent: Double,
        val meanDirectionalAccuracy: Double
    )

    data class HorizonEvaluationResult(
        val horizonSeconds: Int,
        val existingModelMetrics: EvaluationMetrics,
        val candidateModelMetrics: EvaluationMetrics,
        val deltaWinRatePercent: Double,
        val isStatisticallyDefensible: Boolean,
        val outOfSampleSampleSize: Int,
        val pValueEstimate: Double,
        val recommendation: String // "KEEP" or "REJECT"
    )

    data class FeatureEvaluationReport(
        val featureName: String,
        val inSampleTotalTicks: Int,
        val outOfSampleTotalTicks: Int,
        val result30s: HorizonEvaluationResult,
        val result90s: HorizonEvaluationResult,
        val finalStatus: String, // "REJECTED (INSUFFICIENT OOS EDGE)" or "APPROVED FOR WEIGHT ASSIGNMENT"
        val currentProductionWeight: Double = 0.0 // MUST REMAIN 0.0 until rigorous empirical verification
    )

    /**
     * Evaluates a research feature across 30-second and 90-second horizons with strict 60/40 train/test split.
     *
     * @param featureAccessor function returning normalized research feature for each point (or null if unavailable)
     */
    fun evaluateFeature(
        featureName: String,
        priceSeries: List<PricePoint>,
        featureValues: List<ResearchFeatureValue>,
        candidateProspectiveWeight: Double = 0.10
    ): FeatureEvaluationReport {
        require(priceSeries.size == featureValues.size) {
            "Price series and feature values must be aligned in length (${priceSeries.size} vs ${featureValues.size})"
        }

        val totalPoints = priceSeries.size
        if (totalPoints < 100) {
            // Insufficient data for statistical split
            val dummyMetrics = EvaluationMetrics(0, 0, 0, 0.0, 0.0, 0.0, 0.0)
            val dummyHorizon = HorizonEvaluationResult(
                30, dummyMetrics, dummyMetrics, 0.0, false, 0, 1.0, "REJECT (SAMPLE TOO SMALL)"
            )
            return FeatureEvaluationReport(
                featureName = featureName,
                inSampleTotalTicks = 0,
                outOfSampleTotalTicks = 0,
                result30s = dummyHorizon,
                result90s = dummyHorizon.copy(horizonSeconds = 90),
                finalStatus = "REJECTED (INSUFFICIENT DATA)",
                currentProductionWeight = 0.0
            )
        }

        // Strict 60/40 chronological split: First 60% In-Sample, Last 40% Out-Of-Sample
        val splitIndex = (totalPoints * 0.60).toInt()
        val oosPrices = priceSeries.subList(splitIndex, totalPoints)
        val oosFeatures = featureValues.subList(splitIndex, totalPoints)

        // Evaluate 30-second horizon on Out-Of-Sample
        val result30s = evaluateHorizon(
            horizonSeconds = 30,
            horizonSteps = 15, // 15 steps @ 2s = 30s
            allPrices = priceSeries,
            evalStartIndex = splitIndex,
            featureValues = featureValues,
            candidateWeight = candidateProspectiveWeight
        )

        // Evaluate 90-second horizon on Out-Of-Sample
        val result90s = evaluateHorizon(
            horizonSeconds = 90,
            horizonSteps = 45, // 45 steps @ 2s = 90s
            allPrices = priceSeries,
            evalStartIndex = splitIndex,
            featureValues = featureValues,
            candidateWeight = candidateProspectiveWeight
        )

        val bothDefensible = result30s.isStatisticallyDefensible && result90s.isStatisticallyDefensible
        val finalStatus = if (bothDefensible) {
            "APPROVED FOR WEIGHT ASSIGNMENT"
        } else {
            "REJECTED (INSUFFICIENT OOS EDGE - PRESERVING 0.0 WEIGHT)"
        }

        return FeatureEvaluationReport(
            featureName = featureName,
            inSampleTotalTicks = splitIndex,
            outOfSampleTotalTicks = totalPoints - splitIndex,
            result30s = result30s,
            result90s = result90s,
            finalStatus = finalStatus,
            currentProductionWeight = 0.0 // CURRENT PRODUCTION WEIGHT STRICTLY REMAINS 0.0
        )
    }

    private fun evaluateHorizon(
        horizonSeconds: Int,
        horizonSteps: Int,
        allPrices: List<PricePoint>,
        evalStartIndex: Int,
        featureValues: List<ResearchFeatureValue>,
        candidateWeight: Double
    ): HorizonEvaluationResult {
        var baseCorrect = 0
        var baseIncorrect = 0
        var candCorrect = 0
        var candIncorrect = 0
        var alwaysUpWins = 0
        var alwaysDownWins = 0
        var totalEligibleTrades = 0

        val maxEvalIndex = allPrices.size - horizonSteps

        for (i in evalStartIndex until maxEvalIndex step horizonSteps) {
            val historySlice = allPrices.subList(0, i + 1)
            val currentPrice = allPrices[i].price
            val futurePrice = allPrices[i + horizonSteps].price
            val actualOutcome = when {
                futurePrice > currentPrice -> "UP"
                futurePrice < currentPrice -> "DOWN"
                else -> "TIE"
            }
            if (actualOutcome == "TIE") continue

            val rollingRef = historySlice.takeLast(45.coerceAtMost(historySlice.size)).map { it.price }.average()
            val snapshot = indicatorCalculator.computeSnapshot(
                points = historySlice,
                referencePrice = rollingRef,
                previousVelocity = 0.0,
                exchangeAgreement = "STRONG_AGREEMENT"
            )

            // 1. Existing QtY Frozen Prediction
            val baseRecord = baseEngine.predict(
                currentPrice = currentPrice,
                snapshot = snapshot,
                timestamp = allPrices[i].timestamp,
                settlementReference = currentPrice
            )

            val baseDecision = if (horizonSeconds == 90) baseRecord.projectedDecision90s else baseRecord.decision
            if (baseDecision == "NO-TRADE") continue

            totalEligibleTrades++

            if (baseDecision == actualOutcome) baseCorrect++ else baseIncorrect++
            if (actualOutcome == "UP") alwaysUpWins++
            if (actualOutcome == "DOWN") alwaysDownWins++

            // 2. Candidate Model incorporating Research Feature
            val feat = featureValues[i]
            val candDecision = if (feat.isAvailable && feat.normalizedValue != null) {
                // Map normalized feature:
                // If [0.0, 1.0] (TradingView / CoinGlass Risk): map to [-1.0, 1.0] via (val - 0.5) * 2.0
                // If [-1.0, 1.0] (CryptoQuant / Glassnode / CoinGlass Dir): use directly
                val dirOffset = if (feat.provenance.metric.contains("RISK")) {
                    // Risk magnitude dampens direction or acts as contrarian
                    (feat.normalizedValue - 0.5) * 2.0
                } else if (feat.normalizedValue in 0.0..1.0 && feat.provenance.source.contains("TRADINGVIEW")) {
                    (feat.normalizedValue - 0.5) * 2.0
                } else {
                    feat.normalizedValue
                }

                val prospectiveScore = (baseRecord.score + (dirOffset * candidateWeight)).coerceIn(0.0, 1.0)
                when {
                    prospectiveScore >= 0.65 -> "UP"
                    prospectiveScore <= 0.35 -> "DOWN"
                    else -> "NO-TRADE"
                }
            } else {
                baseDecision // Fail-closed fallback: when feature is unavailable, candidate defaults strictly to base
            }

            if (candDecision == actualOutcome) candCorrect++ else if (candDecision != "NO-TRADE") candIncorrect++
        }

        val baseTotal = baseCorrect + baseIncorrect
        val candTotal = candCorrect + candIncorrect

        val baseWinRate = if (baseTotal > 0) (baseCorrect.toDouble() / baseTotal) * 100.0 else 50.0
        val candWinRate = if (candTotal > 0) (candCorrect.toDouble() / candTotal) * 100.0 else 50.0
        val upWinRate = if (totalEligibleTrades > 0) (alwaysUpWins.toDouble() / totalEligibleTrades) * 100.0 else 50.0
        val downWinRate = if (totalEligibleTrades > 0) (alwaysDownWins.toDouble() / totalEligibleTrades) * 100.0 else 50.0

        val delta = Math.round((candWinRate - baseWinRate) * 10.0) / 10.0

        // Two-proportion Z-test for statistical significance
        val pVal = estimatePValue(baseCorrect, baseTotal, candCorrect, candTotal)
        val isDefensible = delta >= 2.0 && pVal < 0.05 && candTotal >= 20

        val recommendation = if (isDefensible) "KEEP" else "REJECT"

        return HorizonEvaluationResult(
            horizonSeconds = horizonSeconds,
            existingModelMetrics = EvaluationMetrics(
                totalPredictions = baseTotal,
                correctPredictions = baseCorrect,
                incorrectPredictions = baseIncorrect,
                winRatePercent = Math.round(baseWinRate * 10.0) / 10.0,
                alwaysUpWinRatePercent = Math.round(upWinRate * 10.0) / 10.0,
                alwaysDownWinRatePercent = Math.round(downWinRate * 10.0) / 10.0,
                meanDirectionalAccuracy = baseWinRate / 100.0
            ),
            candidateModelMetrics = EvaluationMetrics(
                totalPredictions = candTotal,
                correctPredictions = candCorrect,
                incorrectPredictions = candIncorrect,
                winRatePercent = Math.round(candWinRate * 10.0) / 10.0,
                alwaysUpWinRatePercent = Math.round(upWinRate * 10.0) / 10.0,
                alwaysDownWinRatePercent = Math.round(downWinRate * 10.0) / 10.0,
                meanDirectionalAccuracy = candWinRate / 100.0
            ),
            deltaWinRatePercent = delta,
            isStatisticallyDefensible = isDefensible,
            outOfSampleSampleSize = totalEligibleTrades,
            pValueEstimate = Math.round(pVal * 1000.0) / 1000.0,
            recommendation = recommendation
        )
    }

    private fun estimatePValue(s1: Int, n1: Int, s2: Int, n2: Int): Double {
        if (n1 == 0 || n2 == 0) return 1.0
        val p1 = s1.toDouble() / n1
        val p2 = s2.toDouble() / n2
        val pPool = (s1 + s2).toDouble() / (n1 + n2)
        val se = Math.sqrt(pPool * (1.0 - pPool) * (1.0 / n1 + 1.0 / n2))
        if (se < 1e-6) return 1.0
        val z = abs(p1 - p2) / se
        // Standard normal two-tailed p-value approximation
        return 2.0 * (1.0 - normalCdf(z))
    }

    private fun normalCdf(z: Double): Double {
        // Abramowitz and Stegun approximation
        val t = 1.0 / (1.0 + 0.2316419 * z)
        val poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        val pdf = (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * z * z)
        return 1.0 - (pdf * poly)
    }
}
