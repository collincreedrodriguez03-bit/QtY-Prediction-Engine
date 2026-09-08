package com.example.engine.horizon

import com.example.engine.IndicatorSnapshot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fits Horizon Model coefficients and normalization scales from historical training data.
 *
 * Implements Mandates 4 & 6:
 * - Data-learned parameter estimation.
 * - Sample standard deviations replace hardcoded normalization scales.
 * - Parameters are frozen prior to out-of-sample evaluation.
 */
class HorizonModelFitter {

    data class HistoricalTrainingSample(
        val timestamp: Long,
        val price: Double,
        val snapshot: IndicatorSnapshot,
        val futurePriceAtHorizon: Map<Int, Double> // horizonSeconds -> observed future price
    )

    /**
     * Fits parameters for a specific canonical horizon from training samples.
     */
    fun fitHorizon(
        horizonSeconds: Int,
        samples: List<HistoricalTrainingSample>,
        version: String = "v2.1-data-learned"
    ): HorizonModelParameters {
        require(samples.isNotEmpty()) { "Cannot fit model from empty training samples" }

        val n = samples.size

        // 1. Extract raw features and actual forward log returns r_h = ln(P[t+h] / P[t])
        val validPairs = mutableListOf<Pair<DoubleArray, Double>>()

        for (s in samples) {
            val futureP = s.futurePriceAtHorizon[horizonSeconds] ?: continue
            if (s.price <= 0.0 || futureP <= 0.0) continue

            val logReturn = ln(futureP / s.price)
            val p = s.price
            val rawEma = (s.snapshot.ema9 - s.snapshot.ema21) / p
            val rawRsi = (s.snapshot.rsi - 50.0) / 100.0
            val rawMom = s.snapshot.momentum / p
            val rawVel = s.snapshot.velocity / p
            val rawVol = s.snapshot.volatility / p
            val rawVolChange = (s.snapshot.volumeChange ?: 1.0) - 1.0
            val rawBuffer = (s.snapshot.buffer ?: 0.0) / p

            validPairs.add(
                doubleArrayOf(rawEma, rawRsi, rawMom, rawVel, rawVol, rawVolChange, rawBuffer) to logReturn
            )
        }

        if (validPairs.size < 20) {
            // Insufficient samples: return frozen baseline for this horizon
            return HorizonModelRegistry.shared.getParameters(horizonSeconds).copy(
                modelVersion = version,
                isFrozen = true
            )
        }

        val validN = validPairs.size.toDouble()

        // 2. Compute sample means and standard deviations
        val numFeatures = 7
        val means = DoubleArray(numFeatures)
        val stds = DoubleArray(numFeatures)

        for (f in 0 until numFeatures) {
            var sum = 0.0
            for ((x, _) in validPairs) sum += x[f]
            means[f] = sum / validN

            var sumSq = 0.0
            for ((x, _) in validPairs) {
                val diff = x[f] - means[f]
                sumSq += diff * diff
            }
            stds[f] = max(sqrt(sumSq / max(1.0, validN - 1.0)), 1e-6)
        }

        // 3. Standardize feature vectors
        val standardizedX = validPairs.map { (x, _) ->
            DoubleArray(numFeatures) { f -> (x[f] - means[f]) / stds[f] }
        }
        val y = validPairs.map { it.second }

        // 4. Multivariate linear regression with Ridge L2 regularization
        // betas = (X^T X + lambda*I)^(-1) X^T y
        val ridgeLambda = 0.1 * validN
        val xtx = Array(numFeatures) { DoubleArray(numFeatures) }
        val xty = DoubleArray(numFeatures)

        for (i in 0 until validPairs.size) {
            val xi = standardizedX[i]
            val yi = y[i]
            for (r in 0 until numFeatures) {
                xty[r] += xi[r] * yi
                for (c in 0 until numFeatures) {
                    xtx[r][c] += xi[r] * xi[c]
                }
            }
        }
        for (f in 0 until numFeatures) {
            xtx[f][f] += ridgeLambda
        }

        val betas = solveLinearSystem(xtx, xty)

        // 5. Estimate residual standard deviation
        var sumResidualSq = 0.0
        var maxObservedReturn = 0.0
        for (i in 0 until validPairs.size) {
            val xi = standardizedX[i]
            val yi = y[i]
            var pred = 0.0
            for (f in 0 until numFeatures) pred += betas[f] * xi[f]
            val res = yi - pred
            sumResidualSq += res * res
            val absReturn = kotlin.math.abs(yi)
            if (absReturn > maxObservedReturn) maxObservedReturn = absReturn
        }
        val residualStd = max(sqrt(sumResidualSq / max(1.0, validN - numFeatures)), 1e-6)
        val returnCap = max(maxObservedReturn * 1.1, 0.001)

        return HorizonModelParameters(
            horizonSeconds = horizonSeconds,
            modelVersion = version,
            isFrozen = true, // Parameters are frozen upon fitting completion
            sampleSize = validPairs.size,
            calibratedTimestampMs = System.currentTimeMillis(),
            meanEmaDiff = means[0],
            stdEmaDiff = stds[0],
            meanRsi = means[1],
            stdRsi = stds[1],
            meanMomentum = means[2],
            stdMomentum = stds[2],
            meanVelocity = means[3],
            stdVelocity = stds[3],
            meanVolatility = means[4],
            stdVolatility = stds[4],
            meanVolumeChange = means[5],
            stdVolumeChange = stds[5],
            meanBuffer = means[6],
            stdBuffer = stds[6],
            intercept = 0.0,
            betaEma = betas[0],
            betaRsi = betas[1],
            betaMomentum = betas[2],
            betaVelocity = betas[3],
            betaVolatility = betas[4],
            betaVolume = betas[5],
            betaBuffer = betas[6],
            residualStd = residualStd,
            decisionThreshold = 0.45,
            maxReturnCap = returnCap
        )
    }

    /**
     * Solves linear system A * x = b via Gauss-Jordan elimination with partial pivoting.
     */
    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }

        for (p in 0 until n) {
            var maxRow = p
            for (i in p + 1 until n) {
                if (kotlin.math.abs(m[i][p]) > kotlin.math.abs(m[maxRow][p])) maxRow = i
            }
            val temp = m[p]
            m[p] = m[maxRow]
            m[maxRow] = temp

            val pivot = m[p][p]
            if (kotlin.math.abs(pivot) < 1e-12) continue

            for (j in p until n + 1) m[p][j] /= pivot
            for (i in 0 until n) {
                if (i != p) {
                    val factor = m[i][p]
                    for (j in p until n + 1) m[i][j] -= factor * m[p][j]
                }
            }
        }

        return DoubleArray(n) { i -> m[i][n] }
    }
}
