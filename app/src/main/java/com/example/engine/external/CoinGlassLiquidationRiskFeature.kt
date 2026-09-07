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
     * Never fabricates long/short split. If authentic directional split is absent,
     * directional fields remain null and direction feature marks unavailable.
     */
    data class RawCoinGlassObservation(
        val timestampMs: Long,
        val longLiquidationUsd: Double? = null,
        val shortLiquidationUsd: Double? = null,
        val totalLiquidationUsd: Double = (longLiquidationUsd ?: 0.0) + (shortLiquidationUsd ?: 0.0),
        val openInterestUsd: Double = 0.0,
        val btcPrice: Double = 0.0,
        val isRealtimeStream: Boolean = false,
        val dataVersion: String = "v3",
        val hasAuthenticDirectionalSplit: Boolean = (longLiquidationUsd != null && shortLiquidationUsd != null)
    ) {
        constructor(
            timestampMs: Long,
            longLiquidationUsd: Double,
            shortLiquidationUsd: Double
        ) : this(
            timestampMs = timestampMs,
            longLiquidationUsd = longLiquidationUsd,
            shortLiquidationUsd = shortLiquidationUsd,
            totalLiquidationUsd = longLiquidationUsd + shortLiquidationUsd,
            openInterestUsd = 0.0,
            btcPrice = 0.0,
            isRealtimeStream = false,
            dataVersion = "v3",
            hasAuthenticDirectionalSplit = true
        )
    }

    data class LiquidationResult(
        val riskFeature: ResearchFeatureValue,
        val directionFeature: ResearchFeatureValue,
        val authenticObservations: List<RawCoinGlassObservation> = emptyList()
    )

    var latestFetchedObservations: List<RawCoinGlassObservation> = emptyList()
        private set

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
                reason = "Observation timestamp (${latest.timestampMs}) is future-dated relative to clock ($nowMs)",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
            val futureDir = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.FUTURE_DATED,
                reason = "Observation timestamp (${latest.timestampMs}) is future-dated relative to clock ($nowMs)",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
            return LiquidationResult(futureRisk, futureDir, observations)
        }

        // 2. Staleness validation
        val ageMs = nowMs - latest.timestampMs
        if (ageMs > MAX_ALLOWABLE_STALENESS_MS) {
            val staleRisk = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "CoinGlass observation is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
            val staleDir = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "CoinGlass observation is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
            return LiquidationResult(staleRisk, staleDir, observations)
        }

        // 3. Finite domain checks
        val longUsd = latest.longLiquidationUsd
        val shortUsd = latest.shortLiquidationUsd
        val totalUsd = latest.totalLiquidationUsd

        if (totalUsd < 0.0 || totalUsd.isNaN() || totalUsd.isInfinite() ||
            (longUsd != null && (longUsd < 0.0 || longUsd.isNaN() || longUsd.isInfinite())) ||
            (shortUsd != null && (shortUsd < 0.0 || shortUsd.isNaN() || shortUsd.isInfinite()))) {
            val malformedRisk = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = RISK_METRIC,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Negative or NaN liquidation values: long=$longUsd, short=$shortUsd, total=$totalUsd",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
            val malformedDir = ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Negative or NaN liquidation values: long=$longUsd, short=$shortUsd, total=$totalUsd",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
            return LiquidationResult(malformedRisk, malformedDir, observations)
        }

        val totalLiqUsd = if (totalUsd > 0.0) totalUsd else ((longUsd ?: 0.0) + (shortUsd ?: 0.0))

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
                rawValue = "longLiq=$longUsd, shortLiq=$shortUsd, total=$totalLiqUsd",
                derivedValue = derivedRiskScore,
                provenanceStatus = provenanceStatus,
                notes = "Liquidation risk intensity derived from authentic derivatives volume"
            )
        )

        // --- DERIVED METRIC 2: Liquidation Imbalance & Direction [-1.0, +1.0] ---
        // Authentic directional data required: NEVER substitute or fabricate 50/50 split.
        // If directional data is not available, direction feature remains UNAVAILABLE (null).
        val dirFeature = if (latest.hasAuthenticDirectionalSplit && longUsd != null && shortUsd != null) {
            val imbalance = if (totalLiqUsd > 1000.0) {
                ((shortUsd - longUsd) / totalLiqUsd).coerceIn(-1.0, 1.0)
            } else {
                0.0 // Insignificant liquidation volume = neutral direction
            }
            val derivedDirectionScore = Math.round(imbalance * 1000.0) / 1000.0
            ResearchFeatureValue(
                isAvailable = true,
                normalizedValue = derivedDirectionScore,
                rawObservation = imbalance,
                provenance = ExternalObservationProvenance(
                    source = SOURCE_NAME,
                    metric = DIRECTION_METRIC,
                    sourceTimestampMs = latest.timestampMs,
                    retrievalTimestampMs = nowMs,
                    apiVersion = latest.dataVersion,
                    rawValue = "longLiq=$longUsd, shortLiq=$shortUsd, imbalance=$imbalance",
                    derivedValue = derivedDirectionScore,
                    provenanceStatus = provenanceStatus,
                    notes = "Liquidation direction ratio preserved separately from risk intensity"
                )
            )
        } else {
            ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = DIRECTION_METRIC,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "Authentic directional long/short liquidation split not available",
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs
            )
        }

        return LiquidationResult(riskFeature, dirFeature, observations)
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

                    val parseItemToObservation = { obj: JSONObject ->
                        val t = obj.optLong("time", obj.optLong("createTime", nowMs))
                        val tMs = if (t < 10_000_000_000L) t * 1000L else t

                        val hasLong = obj.has("buyVolUsd") || obj.has("longLiquidation") || obj.has("longVolUsd")
                        val hasShort = obj.has("sellVolUsd") || obj.has("shortLiquidation") || obj.has("shortVolUsd")
                        val hasTotal = obj.has("volUsd") || obj.has("totalLiquidation") || obj.has("totalVolUsd")

                        val longVol = if (hasLong) obj.optDouble("buyVolUsd", obj.optDouble("longLiquidation", obj.optDouble("longVolUsd", Double.NaN))) else null
                        val shortVol = if (hasShort) obj.optDouble("sellVolUsd", obj.optDouble("shortLiquidation", obj.optDouble("shortVolUsd", Double.NaN))) else null

                        val validDirectional = longVol != null && shortVol != null && !longVol.isNaN() && !shortVol.isNaN() && longVol >= 0.0 && shortVol >= 0.0
                        val totalVol = when {
                            hasTotal -> obj.optDouble("volUsd", obj.optDouble("totalLiquidation", obj.optDouble("totalVolUsd", 0.0)))
                            validDirectional -> longVol!! + shortVol!!
                            else -> 0.0
                        }

                        if (tMs > 0L && (totalVol > 0.0 || validDirectional)) {
                            RawCoinGlassObservation(
                                timestampMs = tMs,
                                longLiquidationUsd = if (validDirectional) longVol else null,
                                shortLiquidationUsd = if (validDirectional) shortVol else null,
                                totalLiquidationUsd = totalVol,
                                hasAuthenticDirectionalSplit = validDirectional
                            )
                        } else null
                    }

                    if (list != null && list.length() > 0) {
                        for (i in 0 until list.length()) {
                            val item = list.getJSONObject(i)
                            val obs = parseItemToObservation(item)
                            if (obs != null) {
                                observations.add(obs)
                            }
                        }
                    } else {
                        val obs = parseItemToObservation(dataObj)
                        if (obs != null) {
                            observations.add(obs)
                        }
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

                    latestFetchedObservations = observations
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
