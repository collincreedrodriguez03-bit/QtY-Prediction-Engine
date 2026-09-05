package com.example.engine.external

/**
 * Provenance tracking and status of an external prediction feature.
 */
enum class ExternalFeatureProvenanceStatus {
    AUTHENTIC_POINT_IN_TIME, // Immutable, point-in-time snapshot with no revision leakage
    AUTHENTIC_REALTIME_STREAM, // Direct real-time streaming feed or instantaneous websocket/rest update
    AUTHENTIC_DERIVED, // Derived strictly using authentic mathematical operations on authenticated underlying data
    MISSING_CREDENTIALS, // API key / secrets absent or invalid
    CONNECTION_OUTAGE, // Network failure, timeout, or service unavailable
    RATE_LIMITED, // HTTP 429 Too Many Requests
    MALFORMED_PAYLOAD, // JSON parsing failure or corrupt fields
    STALE_DATA, // Observation exceeds maximum allowable freshness threshold
    FUTURE_DATED, // Timestamp exceeds local observation clock (lookahead attempt rejected)
    UNAVAILABLE // General fail-closed missing state
}

/**
 * Audit record capturing exact provenance metadata for any ingested external observation.
 */
data class ExternalObservationProvenance(
    val source: String,
    val metric: String,
    val sourceTimestampMs: Long,
    val retrievalTimestampMs: Long,
    val apiVersion: String = "v1",
    val rawValue: String? = null,
    val derivedValue: Double? = null,
    val provenanceStatus: ExternalFeatureProvenanceStatus = ExternalFeatureProvenanceStatus.UNAVAILABLE,
    val notes: String = ""
)

/**
 * Individual validated research feature.
 *
 * CRITICAL FAIL-CLOSED RULE:
 * If unavailable, stale, unauthenticated, rate-limited, or malformed:
 * - isAvailable = false
 * - normalizedValue = null (NEVER substitute 0.0 or synthetic numeric values)
 */
data class ResearchFeatureValue(
    val isAvailable: Boolean,
    val normalizedValue: Double?, // null when unavailable
    val rawObservation: Double? = null,
    val provenance: ExternalObservationProvenance
) {
    companion object {
        fun unavailable(source: String, metric: String, status: ExternalFeatureProvenanceStatus, reason: String): ResearchFeatureValue {
            val now = System.currentTimeMillis()
            return ResearchFeatureValue(
                isAvailable = false,
                normalizedValue = null,
                rawObservation = null,
                provenance = ExternalObservationProvenance(
                    source = source,
                    metric = metric,
                    sourceTimestampMs = 0L,
                    retrievalTimestampMs = now,
                    provenanceStatus = status,
                    notes = reason
                )
            )
        }
    }
}

/**
 * Unified external-feature container: ExternalPredictionFeatures
 *
 * Strictly for RESEARCH and EVALUATION input.
 * Does NOT alter production prediction formula weights unless proven out-of-sample.
 */
data class ExternalPredictionFeatures(
    // 1. TradingView Trend Score: trendScore in [0.0, 1.0] (~0.0 bearish, ~0.5 neutral, ~1.0 bullish)
    val tradingViewTrendScore: ResearchFeatureValue,

    // 2. CryptoQuant Whale Momentum: whaleMomentum in [-1.0, +1.0] (-1.0 bearish, 0.0 neutral, +1.0 bullish)
    val cryptoQuantWhaleMomentum: ResearchFeatureValue,

    // 3. Glassnode Entity Flow Direction: entityFlowDirection in [-1.0, +1.0] (-1.0 bearish, 0.0 neutral, +1.0 bullish)
    val glassnodeEntityFlowDirection: ResearchFeatureValue,

    // 4. CoinGlass Liquidation Risk: liquidationRisk in [0.0, 1.0] (0.0 low, 0.5 moderate, 1.0 extreme)
    val coinGlassLiquidationRisk: ResearchFeatureValue,

    // 4b. CoinGlass Liquidation Direction: liquidationDirection in [-1.0, +1.0]
    val coinGlassLiquidationDirection: ResearchFeatureValue,

    // System-level container metadata
    val timestamp: Long = System.currentTimeMillis(),
    val isAnyAvailable: Boolean = tradingViewTrendScore.isAvailable ||
            cryptoQuantWhaleMomentum.isAvailable ||
            glassnodeEntityFlowDirection.isAvailable ||
            coinGlassLiquidationRisk.isAvailable,
    val activeAvailableCount: Int = listOf(
        tradingViewTrendScore,
        cryptoQuantWhaleMomentum,
        glassnodeEntityFlowDirection,
        coinGlassLiquidationRisk
    ).count { it.isAvailable }
) {
    companion object {
        fun empty(nowMs: Long = System.currentTimeMillis()): ExternalPredictionFeatures {
            return ExternalPredictionFeatures(
                tradingViewTrendScore = ResearchFeatureValue.unavailable(
                    "TRADINGVIEW_CONCEPT", "TREND_SCORE", ExternalFeatureProvenanceStatus.UNAVAILABLE, "No data available"
                ),
                cryptoQuantWhaleMomentum = ResearchFeatureValue.unavailable(
                    "CRYPTOQUANT", "WHALE_MOMENTUM", ExternalFeatureProvenanceStatus.UNAVAILABLE, "No data available"
                ),
                glassnodeEntityFlowDirection = ResearchFeatureValue.unavailable(
                    "GLASSNODE", "ENTITY_FLOW_DIRECTION", ExternalFeatureProvenanceStatus.UNAVAILABLE, "No data available"
                ),
                coinGlassLiquidationRisk = ResearchFeatureValue.unavailable(
                    "COINGLASS", "LIQUIDATION_RISK", ExternalFeatureProvenanceStatus.UNAVAILABLE, "No data available"
                ),
                coinGlassLiquidationDirection = ResearchFeatureValue.unavailable(
                    "COINGLASS", "LIQUIDATION_DIRECTION", ExternalFeatureProvenanceStatus.UNAVAILABLE, "No data available"
                ),
                timestamp = nowMs
            )
        }
    }
}
