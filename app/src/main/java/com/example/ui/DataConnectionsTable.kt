package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConnectionType
import com.example.data.DataSourceStatus
import com.example.data.FeedState
import com.example.data.SourceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact, High-Density Data Connectivity & Market Data Status Table.
 *
 * Exposes factual connection telemetry for:
 * 1. Binance (BTC Spot - WS bookTicker)
 * 2. Coinbase (BTC Spot - WS ticker)
 * 3. Kraken (BTC Spot - WS ticker)
 * 4. CoinGecko (Reference Metadata - REST)
 * 5. Kalshi (Prediction Market - REST Binary Probability)
 * 6. Cash App (Documented Status: UNAVAILABLE / NO PUBLIC API)
 */
@Composable
fun DataConnectionsTable(
    sourceStatuses: Map<String, DataSourceStatus>,
    modifier: Modifier = Modifier
) {
    // Fixed ordered list of sources to present consistently
    val orderedKeys = listOf("BINANCE", "COINBASE", "KRAKEN", "COINGECKO", "KALSHI", "CASH_APP")

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("data_connectivity_table_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Table Title Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cable,
                        contentDescription = "Connectivity",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MARKET DATA CONNECTIVITY & PROVENANCE",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }

                val activeSpotCount = sourceStatuses.values.count {
                    it.sourceType == SourceType.BTC_SPOT && (it.feedState == FeedState.STREAMING || it.feedState == FeedState.ACTIVE || it.feedState == FeedState.CONNECTED)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$activeSpotCount SPOT FEEDS ACTIVE",
                        color = Color(0xFF38BDF8),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Table Column Headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF070B13))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SOURCE", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                Text("STATUS", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                Text("TYPE", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
                Text("API", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                Text("AGE", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                Text("RATE LIMIT", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.6f))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Rows
            orderedKeys.forEachIndexed { index, key ->
                val status = sourceStatuses[key] ?: DataSourceStatus(
                    sourceId = key,
                    displayName = key.lowercase().replaceFirstChar { it.uppercase() },
                    sourceType = SourceType.UNAVAILABLE,
                    connectionType = ConnectionType.NONE,
                    feedState = FeedState.UNAVAILABLE,
                    rateLimitInfo = "N/A"
                )

                DataSourceRow(status = status)

                if (index < orderedKeys.size - 1) {
                    HorizontalDivider(color = Color(0xFF162032), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
fun DataSourceRow(status: DataSourceStatus) {
    val stateColor = when (status.feedState) {
        FeedState.STREAMING, FeedState.CONNECTED, FeedState.ACTIVE -> Color(0xFF00E676)
        FeedState.POLLING -> Color(0xFF38BDF8)
        FeedState.DISCONNECTED -> Color(0xFFFF9100)
        FeedState.ERROR -> Color(0xFFFF5252)
        FeedState.UNAVAILABLE -> Color(0xFF64748B)
    }

    val typeColor = when (status.sourceType) {
        SourceType.BTC_SPOT -> Color(0xFF00E5FF)
        SourceType.REFERENCE_METADATA -> Color(0xFFFFD600)
        SourceType.PREDICTION_MARKET -> Color(0xFFE040FB)
        SourceType.UNAVAILABLE -> Color(0xFF64748B)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Source Name with dot indicator
        Row(
            modifier = Modifier.weight(1.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(stateColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Column {
                Text(
                    text = status.displayName,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (status.latestPrice != null && status.latestPrice > 0.0) {
                    val priceStr = if (status.sourceType == SourceType.PREDICTION_MARKET) {
                        "${(status.latestPrice * 100).toInt()}% Prob"
                    } else {
                        "$${String.format(Locale.US, "%,.1f", status.latestPrice)}"
                    }
                    Text(
                        text = priceStr,
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Status Badge
        Box(
            modifier = Modifier
                .weight(1.3f)
                .clip(RoundedCornerShape(3.dp))
                .background(stateColor.copy(alpha = 0.12f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = status.feedState.name,
                color = stateColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Data Type Badge
        Text(
            text = when (status.sourceType) {
                SourceType.BTC_SPOT -> "SPOT"
                SourceType.REFERENCE_METADATA -> "REF/VOL"
                SourceType.PREDICTION_MARKET -> "PRED MKT"
                SourceType.UNAVAILABLE -> "N/A"
            },
            color = typeColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1.4f)
        )

        // Stream / API
        Text(
            text = status.connectionType.label,
            color = Color(0xFFCBD5E1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.8f)
        )

        // Age / Freshness
        Text(
            text = status.formattedAge,
            color = if (status.dataAgeSeconds in 0.0..3.0) Color(0xFF00E676) else Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.9f)
        )

        // Rate Limit Info / Error Note
        Column(modifier = Modifier.weight(1.6f)) {
            Text(
                text = status.rateLimitInfo,
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            if (!status.errorState.isNullOrBlank()) {
                Text(
                    text = status.errorState,
                    color = if (status.sourceType == SourceType.UNAVAILABLE) Color(0xFF64748B) else Color(0xFFFF5252),
                    fontSize = 8.sp,
                    maxLines = 1
                )
            }
        }
    }
}
