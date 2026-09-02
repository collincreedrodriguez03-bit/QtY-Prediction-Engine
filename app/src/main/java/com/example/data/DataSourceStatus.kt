package com.example.data

/**
 * Semantic classification of market data sources.
 */
enum class SourceType(val label: String) {
    BTC_SPOT("BTC SPOT"),
    REFERENCE_METADATA("REFERENCE METADATA"),
    PREDICTION_MARKET("PREDICTION MARKET (PROBABILITY)"),
    UNAVAILABLE("N/A")
}

/**
 * Protocol/transport type for the data source.
 */
enum class ConnectionType(val label: String) {
    WEBSOCKET("WS"),
    REST("REST"),
    NONE("N/A")
}

/**
 * Connection and operational lifecycle states.
 */
enum class FeedState(val label: String) {
    CONNECTED("CONNECTED"),
    STREAMING("STREAMING"),
    ACTIVE("ACTIVE"),
    POLLING("POLLING"),
    DISCONNECTED("DISCONNECTED"),
    UNAVAILABLE("UNAVAILABLE"),
    ERROR("ERROR")
}

/**
 * Factual telemetry record for a data source connection.
 */
data class DataSourceStatus(
    val sourceId: String,
    val displayName: String,
    val sourceType: SourceType,
    val connectionType: ConnectionType,
    val feedState: FeedState,
    val lastUpdateTimestamp: Long = 0L,
    val latestPrice: Double? = null,
    val latestMetadata: String? = null,
    val rateLimitInfo: String = "N/A",
    val errorState: String? = null,
    val messageCount: Long = 0L
) {
    /**
     * Calculates data age / freshness in seconds.
     * Returns negative if no update has been received yet.
     */
    val dataAgeSeconds: Double
        get() {
            if (lastUpdateTimestamp <= 0L) return -1.0
            return (System.currentTimeMillis() - lastUpdateTimestamp) / 1000.0
        }

    /**
     * Formatted freshness string (e.g. "0.3s", "1.2s", or "—").
     */
    val formattedAge: String
        get() {
            val age = dataAgeSeconds
            return if (age < 0.0) "—" else String.format(java.util.Locale.US, "%.1fs", age)
        }
}
