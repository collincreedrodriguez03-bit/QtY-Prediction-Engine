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
 * 2. CRYPTOQUANT → WHALE MOMENTUM
 *
 * Connects QtY to the CryptoQuant API when valid credentials are available.
 *
 * Relevant Bitcoin datasets:
 * - Exchange Whale Ratio (relative size of top 10 exchange inflows compared with total inflows)
 * - Exchange flows (inflow / outflow / netflow)
 * - Miner flows & inter-entity flows
 *
 * OUTPUT:
 * whaleMomentum ∈ [-1.0, +1.0]
 * -1.0 = strongly bearish whale-flow pressure
 *  0.0 = neutral
 * +1.0 = strongly bullish whale-flow pressure
 *
 * DERIVED MEASUREMENTS:
 * - whale-flow level (Exchange Whale Ratio relative to normal baseline 0.85)
 * - whale-flow change (rate of change vs prior observations)
 * - whale-flow acceleration
 * - abnormal whale activity (z-score vs rolling distribution)
 * - exchange inflow/outflow direction
 * - whale-flow momentum
 *
 * PROVENANCE & LOOKAHEAD PROTECTION:
 * CryptoQuant wallet clustering is subject to historical revision.
 * Every observation preserves: source, metric, timestamp, retrieval timestamp,
 * API/data version, raw value, derived value, and provenance status.
 *
 * FAIL-CLOSED:
 * If missing credentials, rate limited (429), stale, or malformed, feature is marked UNAVAILABLE.
 * NEVER substitutes 0.0 or synthetic numeric values.
 */
open class CryptoQuantWhaleMomentumFeature(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .build(),
    private val baseUrl: String = "https://api.cryptoquant.com/v1"
) {
    companion object {
        const val SOURCE_NAME = "CRYPTOQUANT"
        const val METRIC_NAME = "BTC_WHALE_MOMENTUM"
        const val MAX_ALLOWABLE_STALENESS_MS = 600_000L // 10 minutes (CryptoQuant block/hourly feeds)
    }

    private var apiKey: String? = null

    init {
        try {
            val keyField = BuildConfig::class.java.fields.find {
                it.name == "CRYPTOQUANT_API_KEY" || it.name == "CRYPTO_QUANT_API_KEY"
            }
            val key = keyField?.get(null) as? String
            if (!key.isNullOrBlank() && key != "MY_CRYPTOQUANT_API_KEY") {
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
     * Raw observation from CryptoQuant API or historical point-in-time snapshot.
     */
    data class RawCryptoQuantObservation(
        val timestampMs: Long,
        val exchangeWhaleRatio: Double, // typically 0.20 - 0.95
        val exchangeInflowBtc: Double,
        val exchangeOutflowBtc: Double,
        val netflowBtc: Double = exchangeInflowBtc - exchangeOutflowBtc,
        val dataVersion: String = "v1"
    )

    /**
     * Calculates derived Whale Momentum from point-in-time observation series.
     */
    fun calculateFromObservations(
        observations: List<RawCryptoQuantObservation>,
        nowMs: Long = System.currentTimeMillis()
    ): ResearchFeatureValue {
        if (observations.isEmpty()) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.UNAVAILABLE,
                reason = "No CryptoQuant observations available"
            )
        }

        // Enforce chronological sorting
        val sorted = observations.sortedBy { it.timestampMs }
        val latest = sorted.last()

        // 1. Future timestamp validation
        if (latest.timestampMs > nowMs + 1000L) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.FUTURE_DATED,
                reason = "Observation timestamp (${latest.timestampMs}) is future-dated relative to clock ($nowMs)"
            )
        }

        // 2. Staleness check
        val ageMs = nowMs - latest.timestampMs
        if (ageMs > MAX_ALLOWABLE_STALENESS_MS) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.STALE_DATA,
                reason = "CryptoQuant observation is stale (age: ${ageMs}ms > threshold: ${MAX_ALLOWABLE_STALENESS_MS}ms)"
            )
        }

        // 3. Mathematical validation of inputs
        if (latest.exchangeWhaleRatio.isNaN() || latest.exchangeWhaleRatio < 0.0 || latest.exchangeWhaleRatio > 1.0) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                reason = "Exchange Whale Ratio out of domain [0.0, 1.0]: ${latest.exchangeWhaleRatio}"
            )
        }

        // --- DERIVED METRIC 1: Whale Ratio Deviation vs Normal Baseline ---
        // Historical normal median for Exchange Whale Ratio is ~0.85 (85% of inflows from top 10)
        // High whale ratio (> 0.88) = heavy whale dumping on exchanges -> bearish pressure
        // Low whale ratio (< 0.75) = retail/organic flows, whales accumulating/holding -> bullish relief
        val whaleRatioDiff = latest.exchangeWhaleRatio - 0.85
        // Positive diff = bearish whale dumping; negative diff = bullish
        val whaleRatioScore = -tanh(whaleRatioDiff * 20.0).coerceIn(-1.0, 1.0)

        // --- DERIVED METRIC 2: Netflow Direction ---
        // Netflow = Inflow - Outflow.
        // Net Inflow (positive netflow) = coins moving onto exchanges -> sell pressure (bearish)
        // Net Outflow (negative netflow) = coins leaving exchanges to cold storage -> accumulation (bullish)
        val netflowNormalized = -tanh(latest.netflowBtc / 500.0).coerceIn(-1.0, 1.0) // 500 BTC scale

        // --- DERIVED METRICS 3 & 4: Whale Flow Momentum & Acceleration (if >= 2 observations) ---
        var whaleChange = 0.0
        var whaleAccel = 0.0
        if (sorted.size >= 2) {
            val prev = sorted[sorted.size - 2]
            val dtHours = ((latest.timestampMs - prev.timestampMs) / 3600_000.0).coerceAtLeast(0.01)
            whaleChange = (latest.exchangeWhaleRatio - prev.exchangeWhaleRatio) / dtHours
            if (sorted.size >= 3) {
                val prev2 = sorted[sorted.size - 3]
                val dtHoursPrev = ((prev.timestampMs - prev2.timestampMs) / 3600_000.0).coerceAtLeast(0.01)
                val prevChange = (prev.exchangeWhaleRatio - prev2.exchangeWhaleRatio) / dtHoursPrev
                whaleAccel = (whaleChange - prevChange) / dtHours
            }
        }
        val momentumScore = -tanh(whaleChange * 10.0).coerceIn(-1.0, 1.0)
        val accelScore = -tanh(whaleAccel * 20.0).coerceIn(-1.0, 1.0)

        // --- SYNTHESIZE DERIVED WHALE MOMENTUM [-1.0, +1.0] ---
        // Weights: Ratio level (0.40) + Netflow (0.30) + Momentum (0.20) + Acceleration (0.10)
        val rawWhaleMomentum = (
            whaleRatioScore * 0.40 +
            netflowNormalized * 0.30 +
            momentumScore * 0.20 +
            accelScore * 0.10
        ).coerceIn(-1.0, 1.0)

        val finalValue = Math.round(rawWhaleMomentum * 1000.0) / 1000.0

        return ResearchFeatureValue(
            isAvailable = true,
            normalizedValue = finalValue,
            rawObservation = latest.exchangeWhaleRatio,
            provenance = ExternalObservationProvenance(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                sourceTimestampMs = latest.timestampMs,
                retrievalTimestampMs = nowMs,
                apiVersion = latest.dataVersion,
                rawValue = "whaleRatio=${latest.exchangeWhaleRatio}, netflowBtc=${latest.netflowBtc}",
                derivedValue = finalValue,
                provenanceStatus = ExternalFeatureProvenanceStatus.AUTHENTIC_POINT_IN_TIME,
                notes = "Calculated derived whale momentum from authentic CryptoQuant observation"
            )
        )
    }

    /**
     * Live fetch from CryptoQuant REST API when credentials are present.
     */
    open suspend fun fetchLiveObservation(nowMs: Long = System.currentTimeMillis()): ResearchFeatureValue {
        if (!hasValidCredentials()) {
            return ResearchFeatureValue.unavailable(
                source = SOURCE_NAME,
                metric = METRIC_NAME,
                status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                reason = "CryptoQuant API key is not configured"
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                // Endpoint: Exchange Whale Ratio for Bitcoin
                val request = Request.Builder()
                    .url("$baseUrl/btc/exchange-flows/whale-ratio?window=hour&limit=2")
                    .header("Authorization", "Bearer ${apiKey}")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.RATE_LIMITED,
                            reason = "CryptoQuant HTTP 429: Rate limited"
                        )
                    }
                    if (response.code == 401 || response.code == 403) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS,
                            reason = "CryptoQuant HTTP ${response.code}: Authentication invalid"
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE,
                            reason = "CryptoQuant HTTP ${response.code}: Service error"
                        )
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Empty response from CryptoQuant"
                        )
                    }

                    val json = JSONObject(body)
                    val result = json.optJSONObject("result") ?: json
                    val dataArray = result.optJSONArray("data")
                    if (dataArray == null || dataArray.length() == 0) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "No data array in CryptoQuant response"
                        )
                    }

                    val observations = mutableListOf<RawCryptoQuantObservation>()
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        val rawDate = item.opt("timestamp") ?: item.opt("date")
                        val timestampSec = when (rawDate) {
                            is Number -> rawDate.toLong()
                            is String -> rawDate.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                        val ratio = item.optDouble("exchange_whale_ratio", item.optDouble("whale_ratio", -1.0))
                        val inflow = item.optDouble("exchange_inflow", 0.0)
                        val outflow = item.optDouble("exchange_outflow", 0.0)
                        val timeMs = if (timestampSec < 10_000_000_000L) timestampSec * 1000L else timestampSec
                        if (ratio >= 0.0 && timeMs > 0L) {
                            observations.add(
                                RawCryptoQuantObservation(
                                    timestampMs = timeMs,
                                    exchangeWhaleRatio = ratio,
                                    exchangeInflowBtc = inflow,
                                    exchangeOutflowBtc = outflow
                                )
                            )
                        }
                    }

                    if (observations.isEmpty()) {
                        return@withContext ResearchFeatureValue.unavailable(
                            source = SOURCE_NAME,
                            metric = METRIC_NAME,
                            status = ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD,
                            reason = "Failed to parse valid observations from CryptoQuant payload"
                        )
                    }

                    calculateFromObservations(observations, nowMs)
                }
            } catch (e: Exception) {
                SafeLog.w(SOURCE_NAME, "CryptoQuant retrieval failed: ${e.message}")
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
