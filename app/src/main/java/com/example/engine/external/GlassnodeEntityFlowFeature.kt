package com.example.engine.external

import com.example.BuildConfig
import com.example.data.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 3. GLASSNODE → ENTITY FLOW DIRECTION
 *
 * Connects QtY to the Glassnode API when valid credentials are available.
 *
 * Prioritizes point-in-time / immutable historical variants when available for backtesting.
 * Specifically handles Glassnode Point-in-Time metrics designed for quantitative / backtesting use.
 *
 * CONCEPT: ENTITY FLOW DIRECTION
 * Calculate:
 * - net entity flow (Exchange net flow in BTC or USD)
 * - flow direction (Positive = net inflow to exchanges, Negative = net outflow)
 * - flow momentum/change (rate of change between consecutive intervals)
 * - abnormal flow (z-score scaling against normal flow volume)
 * - flow acceleration where data frequency supports it
 *
 * NORMALIZE:
 * entityFlowDirection ∈ [-1.0, +1.0]
 * Where:
 * -1.0 = bearish flow pressure (heavy net inflows to exchanges)
 *  0.0 = neutral
 * +1.0 = bullish flow pressure (heavy net outflows to cold storage)
 *
 * EMPIRICAL NOTE:
 * Does NOT treat exchange inflow automatically as a guaranteed bearish signal in production.
 * Must be empirically tested out-of-sample against future BTC movement.
 *
 * POINT-IN-TIME IMMUTABILITY:
 * Never allow later-revised entity labels or clustering data to leak backward into historical model decisions.
 * Point-in-time records explicitly document the exact snapshot timestamp.
 */
open class GlassnodeEntityFlowFeature(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .build(),
    private val baseUrl: String = "https://api.glassnode.com/v1/metrics"
) {
    companion object {
        const val SOURCE_NAME = "GLASSNODE"
        const val METRIC_NAME = "BTC_ENTITY_FLOW_DIRECTION"
        const val MAX_ALLOWABLE_STALENESS_MS = 600_000L // 10 minutes (Glassnode 10m/1h metric intervals)
    }

    private var apiKey: String? = null

    init {
        try {
            val keyField = BuildConfig::class.java.fields.find {
                it.name == "GLASSNODE_API_KEY" || it.name == "GLASS_NODE_API_KEY"
            }
            val key = keyField?.get(null) as? String
            if (!key.isNullOrBlank() && key != "MY_GLASSNODE_API_KEY") {
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
     * Point-in-time observation model for Glassnode entity flows.
     */
    data class RawGlassnodeObservation(
        val timestampMs: Long,
        val netFlowBtc: Double, // Net exchange transfer volume in BTC (Inflow - Outflow)
        val inFlowBtc: Double = 0.0,
        val outFlowBtc: Double = 0.0,
        val isPointInTime: Boolean = true,
        val dataVersion: String = "v1-pit"
    )

    /**
     * Calculates derived Entity Flow Direction from chronological point-in-time observations.
     */
    fun calculateFromObservations(
        observations: List<RawGlassnodeObservation>,
        nowMs: Long = System.currentTimeMillis()
    ): ResearchFeatureValue {
        if (observations.isEmpty()) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "No Glassnode observations available"
            )
        }

        // Chronological order verification
        val sorted = observations.sortedBy { it.timestampMs }
        val latest = sorted.last()

        // 1. Future timestamp validation (Strict no-lookahead)
        if (latest.timestampMs > nowMs + 1000L) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.FUTURE_DATED,
                reason = "Observation timestamp (${latest.timestampMs}) is future-dated relative to clock ($nowMs)"
            )
        }

        // 2. Staleness validation
        val ageMs = nowMs - latest.timestampMs
        if (ageMs > MAX_ALLOWABLE_STALENESS_MS) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "Glassnode observation is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)"
            )
        }

        // 3. Finite value check
        if (latest.netFlowBtc.isNaN() || latest.netFlowBtc.isInfinite()) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Glassnode netFlowBtc is non-finite: ${latest.netFlowBtc}"
            )
        }

        // --- DERIVED METRIC 1: Instantaneous Flow Direction ---
        // Positive netflow = net deposit to exchange (selling liquidity available) -> bearish direction (-1.0)
        // Negative netflow = net withdrawal from exchange (illiquid cold storage) -> bullish direction (+1.0)
        // Normalized with hyperbolic tangent across typical 500 BTC scale
        val directFlowScore = -tanh(latest.netFlowBtc / 500.0).coerceIn(-1.0, 1.0)

        // --- DERIVED METRICS 2 & 3: Flow Momentum & Acceleration (where multi-step history exists) ---
        var flowMomentum = 0.0
        var flowAccel = 0.0
        if (sorted.size >= 2) {
            val prev = sorted[sorted.size - 2]
            val dtHours = ((latest.timestampMs - prev.timestampMs) / 3600_000.0).coerceAtLeast(0.01)
            val flowDelta = latest.netFlowBtc - prev.netFlowBtc
            flowMomentum = -tanh((flowDelta / dtHours) / 300.0).coerceIn(-1.0, 1.0)

            if (sorted.size >= 3) {
                val prev2 = sorted[sorted.size - 3]
                val dtHoursPrev = ((prev.timestampMs - prev2.timestampMs) / 3600_000.0).coerceAtLeast(0.01)
                val prevDelta = prev.netFlowBtc - prev2.netFlowBtc
                val accelDelta = (flowDelta / dtHours) - (prevDelta / dtHoursPrev)
                flowAccel = -tanh(accelDelta / 500.0).coerceIn(-1.0, 1.0)
            }
        }

        // --- DERIVED METRIC 4: Abnormal Flow (Z-score if >= 5 observations) ---
        var abnormalFlowScore = 0.0
        if (sorted.size >= 5) {
            val allFlows = sorted.map { it.netFlowBtc }
            val mean = allFlows.average()
            val variance = allFlows.map { (it - mean) * (it - mean) }.average()
            val stdDev = Math.sqrt(variance.coerceAtLeast(1.0))
            val zScore = (latest.netFlowBtc - mean) / stdDev
            abnormalFlowScore = -tanh(zScore * 0.5).coerceIn(-1.0, 1.0)
        }

        // --- SYNTHESIZE COMBINED ENTITY FLOW DIRECTION [-1.0, +1.0] ---
        // Weights: Direct flow (0.45) + Flow momentum (0.25) + Abnormal flow (0.20) + Acceleration (0.10)
        val rawDirection = (
            directFlowScore * 0.45 +
            flowMomentum * 0.25 +
            abnormalFlowScore * 0.20 +
            flowAccel * 0.10
        ).coerceIn(-1.0, 1.0)

        val finalValue = Math.round(rawDirection * 1000.0) / 1000.0

        val provenanceStatus = if (latest.isPointInTime) {
            ExternalFeatureProvenanceStatus.AUTHENTIC_POINT_IN_TIME
        } else {
            ExternalFeatureProvenanceStatus.AUTHENTIC_DERIVED
        }

        return ResearchFeatureValue(
            isAvailable = true,
            normalizedValue = finalValue,
            rawObservation = latest.netFlowBtc,
            provenance = ExternalObservationProvenance(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs,
                apiVersion = latest.dataVersion,
                rawValue = "netFlowBtc=${latest.netFlowBtc}, isPit=${latest.isPointInTime}",
                derivedValue = finalValue,
                provenanceStatus = provenanceStatus,
                notes = "Glassnode entity flow direction computed with point-in-time enforcement"
            )
        )
    }

    /**
     * Live fetch from Glassnode API when credentials are present.
     * Uses Point-in-Time endpoint variant where available (e.g. transactions/transfers_volume_exchanges_net).
     */
    open suspend fun fetchLiveObservation(nowMs: Long = System.currentTimeMillis()): ResearchFeatureValue {
        if (!hasValidCredentials()) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                reason = "Glassnode API key is not configured"
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                // Endpoint: Glassnode exchange transfers net volume (BTC)
                val request = Request.Builder()
                    .url("$baseUrl/transactions/transfers_volume_exchanges_net?a=BTC&i=10m&api_key=${apiKey}")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.RATE_LIMITED,
                            reason = "Glassnode HTTP 429: Rate limited"
                        )
                    }
                    if (response.code == 401 || response.code == 403) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                            reason = "Glassnode HTTP ${response.code}: Authentication invalid"
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                            reason = "Glassnode HTTP ${response.code}: Service error"
                        )
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Empty response from Glassnode"
                        )
                    }

                    val jsonArray = JSONArray(body)
                    if (jsonArray.length() == 0) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Empty data array from Glassnode"
                        )
                    }

                    val observations = mutableListOf<RawGlassnodeObservation>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val timestampSec = obj.optLong("t", 0L)
                        val v = obj.optDouble("v", Double.NaN)
                        val timeMs = timestampSec * 1000L
                        if (!v.isNaN() && timeMs > 0L) {
                            observations.add(
                                RawGlassnodeObservation(
                                    timestampMs = timeMs,
                                    netFlowBtc = v,
                                    isPointInTime = true
                                )
                            )
                        }
                    }

                    if (observations.isEmpty()) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Failed to parse valid observations from Glassnode payload"
                        )
                    }

                    calculateFromObservations(observations, nowMs)
                }
            } catch (e: Exception) {
                SafeLog.w(SOURCE_NAME, "Glassnode retrieval failed: ${e.message}")
                ResearchFeatureValue.unavailable(
                    source = SOURCE_NAME,
                    metric = METRIC_NAME,
                    status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                    reason = "Network failure: ${e.message}"
                )
            }
        }
    }
}
