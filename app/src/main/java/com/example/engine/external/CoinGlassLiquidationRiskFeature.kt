package com.example.engine.external

import com.example.BuildConfig
import com.example.data.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 4. COINGLASS → LIQUIDATION RISK
 *
 * Connects QtY to the CoinGlass API when valid credentials are available.
 *
 * Derivatives data focus:
 * - liquidation orders & volume
 * - long liquidations vs short liquidations
 * - liquidation history & maps
 * - open interest & funding rates
 *
 * CALCULATE:
 * - recent long liquidation pressure (USD volume)
 * - recent short liquidation pressure (USD volume)
 * - liquidation imbalance (Long vs Short ratio)
 * - liquidation intensity (total liquidations relative to typical baseline)
 * - abnormal liquidation activity (spike / cascade indicator)
 * - liquidation concentration relative to BTC price
 *
 * NORMALIZE:
 * liquidationRisk ∈ [0.0, 1.0]
 * 0.0 = low abnormal liquidation pressure
 * 0.5 = neutral / moderate
 * 1.0 = extreme liquidation environment / liquidation cascade
 *
 * PRESERVE DIRECTION SEPARATELY:
 * liquidationDirection ∈ [-1.0, +1.0]
 * -1.0 = heavy long liquidations / downward liquidation cascade
 *  0.0 = balanced / neutral
 * +1.0 = heavy short liquidations / short squeeze cascade
 *
 * EMPIRICAL PRINCIPLE:
 * Do NOT assume large long liquidations = automatically bearish or large short liquidations = automatically bullish.
 * The relationship must be empirically tested against forward 30s / 90s BTC movement.
 */
open class CoinGlassLiquidationRiskFeature(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .build(),
    private val baseUrl: String = "https://open-api-v3.coinglass.com/api"
) {
    companion object {
        const val SOURCE_NAME = "COINGLASS"
        const val RISK_METRIC = "BTC_LIQUIDATION_RISK"
        const val DIRECTION_METRIC = "BTC_LIQUIDATION_DIRECTION"
        const val MAX_ALLOWABLE_STALENESS_MS = 180_000L // 3 minutes (CoinGlass 1m/3m liquidation candles)
    }

    private var apiKey: String? = null

    init {
        try {
            val keyField = BuildConfig::class.java.fields.find {
                it.name == "COINGLASS_API_KEY" || it.name == "COIN_GLASS_API_KEY"
            }
            val key = keyField?.get(null) as? String
            if (!key.isNullOrBlank() && key != "MY_COINGLASS_API_KEY") {
                setApiKey(key)
            }
        } catch (_: Throwable) {}
    }

    fun setApiKey(key: String?) {
        val trimmed = key?.trim()
        if (trimmed.isNullOrEmpty() || trimmed.startsWith("MY_")) {
            this.apiKey = null
        } else {
            this.apiKey = trimmed
        }
    }

    fun hasValidCredentials(): Boolean = !apiKey.isNullOrEmpty()

    /**
     * Point-in-time observation of liquidation events and aggregate derivatives state.
     */
    data class RawCoinGlassObservation(
        val timestampMs: Long,
        val longLiquidationUsd: Double,
        val shortLiquidationUsd: Double,
        val openInterestUsd: Double = 0.0,
        val btcPrice: Double = 0.0,
        val isRealtimeStream: Boolean = false,
        val dataVersion: String = "v3"
    )

    data class LiquidationResult(
        val riskFeature: ResearchFeatureValue,
        val directionFeature: ResearchFeatureValue
    )

    /**
     * Calculates derived Liquidation Risk [0.0, 1.0] and Liquidation Direction [-1.0, +1.0]
     */
    fun calculateFromObservations(
        observations: List<RawCoinGlassObservation>,
        nowMs: Long = System.currentTimeMillis()
    ): LiquidationResult {
        if (observations.isEmpty()) {
            val riskUnavail = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "No CoinGlass observations available"
            )
            val dirUnavail = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "No CoinGlass observations available"
            )
            return LiquidationResult(riskUnavail, dirUnavail)
        }

        val sorted = observations.sortedBy { it.timestampMs }
        val latest = sorted.last()

        // 1. Future timestamp validation
        if (latest.timestampMs > nowMs + 1000L) {
            val futureRisk = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.FUTURE_DATED,
                reason = "Observation timestamp (${latest.timestampMs}) is future-dated relative to clock ($nowMs)"
            )
            val futureDir = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.FUTURE_DATED,
                reason = "Observation timestamp (${latest.timestampMs}) is future-dated relative to clock ($nowMs)"
            )
            return LiquidationResult(futureRisk, futureDir)
        }

        // 2. Staleness validation
        val ageMs = nowMs - latest.timestampMs
        if (ageMs > MAX_ALLOWABLE_STALENESS_MS) {
            val staleRisk = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "CoinGlass observation is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)"
            )
            val staleDir = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "CoinGlass observation is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)"
            )
            return LiquidationResult(staleRisk, staleDir)
        }

        // 3. Finite domain checks
        if (latest.longLiquidationUsd < 0.0 || latest.shortLiquidationUsd < 0.0 ||
            latest.longLiquidationUsd.isNaN() || latest.shortLiquidationUsd.isNaN()) {
            val malformedRisk = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Negative or NaN liquidation values: long=${latest.longLiquidationUsd}, short=${latest.shortLiquidationUsd}"
            )
            val malformedDir = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Negative or NaN liquidation values: long=${latest.longLiquidationUsd}, short=${latest.shortLiquidationUsd}"
            )
            return LiquidationResult(malformedRisk, malformedDir)
        }

        val totalLiqUsd = latest.longLiquidationUsd + latest.shortLiquidationUsd

        // --- DERIVED METRIC 1: Liquidation Intensity & Risk [0.0, 1.0] ---
        // Normal 1-minute liquidation volume for BTC is ~$50k - $200k.
        // Abnormal cascade environment is $1M - $10M+.
        // Baseline scale: $1,000,000 USD
        val intensityScale = 1_000_000.0
        val zIntensity = totalLiqUsd / intensityScale
        // Hyperbolic tangent maps [0, inf) to [0.0, 1.0]
        val abnormalSpikeFactor = tanh(zIntensity).coerceIn(0.0, 1.0)

        // Baseline risk score: 0.0 to 1.0
        val derivedRiskScore = Math.round(abnormalSpikeFactor * 1000.0) / 1000.0

        // --- DERIVED METRIC 2: Liquidation Imbalance & Direction [-1.0, +1.0] ---
        // Imbalance = (ShortLiq - LongLiq) / (TotalLiq)
        // If ShortLiq >> LongLiq: positive imbalance (short squeeze pressure / upward liquidation cascade)
        // If LongLiq >> ShortLiq: negative imbalance (long liquidation cascade / downward liquidation cascade)
        val imbalance = if (totalLiqUsd > 1000.0) {
            ((latest.shortLiquidationUsd - latest.longLiquidationUsd) / totalLiqUsd).coerceIn(-1.0, 1.0)
        } else {
            0.0 // Insignificant liquidation volume = neutral direction
        }
        val derivedDirectionScore = Math.round(imbalance * 1000.0) / 1000.0

        val provenanceStatus = if (latest.isRealtimeStream) {
            ExternalFeatureProvenanceStatus.AUTHENTIC_REALTIME_STREAM
        } else {
            ExternalFeatureProvenanceStatus.AUTHENTIC_POINT_IN_TIME
        }

        val riskFeature = ResearchFeatureValue(
            isAvailable = true,
            normalizedValue = derivedRiskScore,
            rawObservation = totalLiqUsd,
            provenance = ExternalObservationProvenance(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs,
                apiVersion = latest.dataVersion,
                rawValue = "longLiq=${latest.longLiquidationUsd}, shortLiq=${latest.shortLiquidationUsd}, total=$totalLiqUsd",
                derivedValue = derivedRiskScore,
                provenanceStatus = provenanceStatus,
                notes = "Liquidation risk intensity derived from authentic derivatives volume"
            )
        )

        val dirFeature = ResearchFeatureValue(
            isAvailable = true,
            normalizedValue = derivedDirectionScore,
            rawObservation = imbalance,
            provenance = ExternalObservationProvenance(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs,
                apiVersion = latest.dataVersion,
                rawValue = "imbalance=$imbalance",
                derivedValue = derivedDirectionScore,
                provenanceStatus = provenanceStatus,
                notes = "Liquidation direction ratio preserved separately from risk intensity"
            )
        )

        return LiquidationResult(riskFeature, dirFeature)
    }

    /**
     * Live fetch from CoinGlass open API when valid credentials are present.
     */
    open suspend fun fetchLiveObservation(nowMs: Long = System.currentTimeMillis()): LiquidationResult {
        if (!hasValidCredentials()) {
            val riskUnavail = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                reason = "CoinGlass API key is not configured"
            )
            val dirUnavail = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                reason = "CoinGlass API key is not configured"
            )
            return LiquidationResult(riskUnavail, dirUnavail)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Endpoint: CoinGlass Liquidation Vol 1m/5m for BTC
                val request = Request.Builder()
                    .url("$baseUrl/liquidation/history?symbol=BTC&time_type=1m")
                    .header("CG-API-KEY", apiKey ?: "")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        val r = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = RISK_METRIC,
                            status = ExternalFeatureProvenanceStatus.RATE_LIMITED,
                            reason = "CoinGlass HTTP 429: Rate limited"
                        )
                        val d = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = DIRECTION_METRIC,
                            status = ExternalFeatureProvenanceStatus.RATE_LIMITED,
                            reason = "CoinGlass HTTP 429: Rate limited"
                        )
                        return@withContext LiquidationResult(r, d)
                    }
                    if (response.code == 401 || response.code == 403) {
                        val r = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = RISK_METRIC,
                            status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                            reason = "CoinGlass HTTP ${response.code}: Authentication invalid"
                        )
                        val d = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = DIRECTION_METRIC,
                            status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                            reason = "CoinGlass HTTP ${response.code}: Authentication invalid"
                        )
                        return@withContext LiquidationResult(r, d)
                    }
                    if (!response.isSuccessful) {
                        val r = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = RISK_METRIC,
                            status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                            reason = "CoinGlass HTTP ${response.code}: Service error"
                        )
                        val d = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = DIRECTION_METRIC,
                            status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                            reason = "CoinGlass HTTP ${response.code}: Service error"
                        )
                        return@withContext LiquidationResult(r, d)
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        val r = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = RISK_METRIC,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Empty response body from CoinGlass"
                        )
                        val d = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = DIRECTION_METRIC,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Empty response body from CoinGlass"
                        )
                        return@withContext LiquidationResult(r, d)
                    }

                    val json = JSONObject(body)
                    val dataObj = json.optJSONObject("data") ?: json
                    val list = dataObj.optJSONArray("list")
                    val observations = mutableListOf<RawCoinGlassObservation>()

                    if (list != null && list.length() > 0) {
                        for (i in 0 until list.length()) {
                            val item = list.getJSONObject(i)
                            val t = item.optLong("time", item.optLong("createTime", 0L))
                            val longVol = item.optDouble("buyVolUsd", item.optDouble("longLiquidation", 0.0))
                            val shortVol = item.optDouble("sellVolUsd", item.optDouble("shortLiquidation", 0.0))
                            val tMs = if (t < 10_000_000_000L) t * 1000L else t
                            if (tMs > 0L) {
                                observations.add(
                                    RawCoinGlassObservation(
                                        timestampMs = tMs,
                                        longLiquidationUsd = longVol,
                                        shortLiquidationUsd = shortVol
                                    )
                                )
                            }
                        }
                    } else {
                        // Single summary object fallback
                        val longVol = dataObj.optDouble("buyVolUsd", 0.0)
                        val shortVol = dataObj.optDouble("sellVolUsd", 0.0)
                        val t = dataObj.optLong("time", nowMs)
                        observations.add(
                            RawCoinGlassObservation(
                                timestampMs = t,
                                longLiquidationUsd = longVol,
                                shortLiquidationUsd = shortVol
                            )
                        )
                    }

                    if (observations.isEmpty()) {
                        val r = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = RISK_METRIC,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "No valid observations parsed from CoinGlass"
                        )
                        val d = ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME, metric = DIRECTION_METRIC,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "No valid observations parsed from CoinGlass"
                        )
                        return@withContext LiquidationResult(r, d)
                    }

                    calculateFromObservations(observations, nowMs)
                }
            } catch (e: Exception) {
                SafeLog.w(SOURCE_NAME, "CoinGlass retrieval failed: ${e.message}")
                val r = ResearchFeatureValue.unavailable(
                    source = SOURCE_NAME, metric = RISK_METRIC,
                    status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                    reason = "Network failure: ${e.message}"
                )
                val d = ResearchFeatureValue.unavailable(
                    source = SOURCE_NAME, metric = DIRECTION_METRIC,
                    status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                    reason = "Network failure: ${e.message}"
                )
                LiquidationResult(r, d)
            }
        }
    }
}
