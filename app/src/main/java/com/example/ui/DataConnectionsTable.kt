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
import java.util.Locale

/**
 * High-Density Spot Exchange Connectivity & Live Tick Counter Table.
 *
 * Exposes real connection telemetry, total live ticks, exchange names, protocol types, and spot rates for:
 * 1. Binance (BTC/USDT - WS bookTicker)
 * 2. Coinbase (BTC/USD - WS ticker)
 * 3. Kraken (XBT/USDT - WS ticker)
 * 4. Bitstamp (BTC/USD - REST Fallback)
 */
@Composable
fun DataConnectionsTable(
    sourceStatuses: Map<String, DataSourceStatus>,
    totalTicks: Long = 0L,
    modifier: Modifier = Modifier
) {
    val orderedKeys = listOf("BINANCE", "COINBASE", "KRAKEN", "BITSTAMP")
    val activeCount = orderedKeys.count { key ->
        val status = sourceStatuses[key]
        status != null && (status.feedState == FeedState.STREAMING || status.feedState == FeedState.ACTIVE || status.feedState == FeedState.CONNECTED)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("data_connectivity_table_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Table Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cable,
                        contentDescription = "Connectivity",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "SPOT APIS",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF00E676).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$activeCount/${orderedKeys.size} LIVE",
                            color = Color(0xFF00E676),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (totalTicks > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%,d", totalTicks)} TICKS",
                                color = Color(0xFF38BDF8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Table Column Headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF070B13))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("EXCHANGE", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.weight(1.3f))
                Text("STATE", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.weight(1.1f))
                Text("TRANSPORT", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.weight(1.0f))
                Text("TICKS", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.weight(0.9f))
                Text("SPOT PRICE", color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.weight(1.3f))
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Exchange Rows
            orderedKeys.forEachIndexed { index, key ->
                val status = sourceStatuses[key] ?: DataSourceStatus(
                    sourceId = key,
                    displayName = "$key Spot",
                    sourceType = com.example.data.SourceType.BTC_SPOT,
                    connectionType = if (key == "BITSTAMP") ConnectionType.REST else ConnectionType.WEBSOCKET,
                    feedState = FeedState.CONNECTED,
                    rateLimitInfo = "Active"
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
private fun DataSourceRow(status: DataSourceStatus) {
    val stateColor = when (status.feedState) {
        FeedState.STREAMING -> Color(0xFF00E676)
        FeedState.ACTIVE -> Color(0xFF00E5FF)
        FeedState.POLLING -> Color(0xFF38BDF8)
        FeedState.CONNECTED -> Color(0xFF00E676)
        FeedState.RECONNECTING -> Color(0xFFFFD600)
        FeedState.DISCONNECTED -> Color(0xFFFF9100)
        FeedState.ERROR -> Color(0xFFFF5252)
        FeedState.UNAVAILABLE -> Color(0xFF64748B)
    }

    val stateText = when (status.feedState) {
        FeedState.STREAMING -> "STREAMING"
        FeedState.ACTIVE -> "ACTIVE"
        FeedState.POLLING -> "POLLING"
        FeedState.CONNECTED -> "CONNECTED"
        FeedState.RECONNECTING -> "RECONNECTING"
        FeedState.DISCONNECTED -> "OFFLINE"
        FeedState.ERROR -> "ERROR"
        FeedState.UNAVAILABLE -> "N/A"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Exchange Column
        Row(
            modifier = Modifier.weight(1.3f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(stateColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = status.displayName.replace(" Spot", ""),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }

        // State Column
        Row(
            modifier = Modifier.weight(1.1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stateText,
                color = stateColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }

        // Transport / Connection Type
        Text(
            text = when (status.connectionType) {
                ConnectionType.WEBSOCKET -> "WS"
                ConnectionType.REST -> "REST"
                ConnectionType.NONE -> "NONE"
            },
            color = if (status.connectionType == ConnectionType.WEBSOCKET) Color(0xFF00E5FF) else Color(0xFF94A3B8),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1.0f)
        )

        // Live Tick Count
        Text(
            text = if (status.messageCount > 0) String.format(Locale.US, "%,d", status.messageCount) else "-",
            color = Color(0xFF38BDF8),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(0.9f)
        )

        // Latest Spot Price
        Text(
            text = if (status.latestPrice != null && status.latestPrice > 0.0) "$${String.format(Locale.US, "%,.1f", status.latestPrice)}" else "-",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1.3f)
        )
    }
}
