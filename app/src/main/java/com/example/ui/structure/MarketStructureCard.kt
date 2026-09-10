package com.example.ui.structure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.example.engine.EngineState
import com.example.engine.structure.CompressionState
import com.example.engine.structure.LevelType
import com.example.engine.structure.MarketStructureSnapshot
import com.example.engine.structure.PatternStatus
import com.example.engine.structure.TrendDirection
import java.util.Locale

/**
 * Data-Derived Trader Market Structure Card.
 *
 * Visually communicates:
 * - ENTRY -> TARGET -> INVALIDATION levels with dynamic Risk/Reward (R:R)
 * - Structural NO-TRADE alert when ambiguous or compressed
 * - Swing progression breakdown (HH, HL, LH, LL)
 * - Horizontal Support & Resistance density clusters
 * - Mathematically derived Trendlines & Local Channel
 * - Strictly verified classical chart formations (Zero false claims).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketStructureCard(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    val structure = engineState.marketStructure
        ?: MarketStructureSnapshot.empty(
            price = if (engineState.latestPrice > 0.0) engineState.latestPrice else 0.0
        )

    val currentPrice = if (engineState.latestPrice > 0.0) engineState.latestPrice else structure.currentPrice

    val trendColor = when (structure.trendDirection) {
        TrendDirection.BULLISH -> Color(0xFF00E676)
        TrendDirection.BEARISH -> Color(0xFFFF334B)
        TrendDirection.NEUTRAL_RANGE -> Color(0xFFF59E0B)
        TrendDirection.AMBIGUOUS -> Color(0xFF94A3B8)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF080E1B)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1B283E), RoundedCornerShape(14.dp))
            .testTag("market_structure_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 1. Header: Title, Trend Direction & Compression Regime
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "Market Structure",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DATA-DERIVED MARKET STRUCTURE",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }

                // Trend Direction Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(trendColor.copy(alpha = 0.16f))
                        .border(1.dp, trendColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (structure.trendDirection) {
                                TrendDirection.BULLISH -> Icons.AutoMirrored.Filled.TrendingUp
                                TrendDirection.BEARISH -> Icons.AutoMirrored.Filled.TrendingDown
                                else -> Icons.Default.Timeline
                            },
                            contentDescription = structure.trendDirection.name,
                            tint = trendColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = structure.trendDirection.name,
                            color = trendColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Actionable Trader Reference Levels: ENTRY -> TARGET -> INVALIDATION
            TraderLevelsBanner(
                structure = structure,
                currentPrice = currentPrice
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Swing Sequence Counters (HH, HL, LH, LL) & Volatility Regime
            SwingProgressionStrip(structure = structure)

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Two-Column Structural Details: Key Levels (Left) & Trendlines/Channels (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT: Key Horizontal Support & Resistance
                KeyLevelsColumn(
                    structure = structure,
                    modifier = Modifier.weight(1f)
                )

                // RIGHT: Mathematical Trendlines & Local Channel
                TrendlinesAndChannelColumn(
                    structure = structure,
                    modifier = Modifier.weight(1f)
                )
            }

            // 5. Strictly Validated Classical Formations (Zero False Claims)
            if (structure.validatedFormation.status != PatternStatus.NONE) {
                Spacer(modifier = Modifier.height(8.dp))
                ValidatedFormationBanner(formation = structure.validatedFormation)
            }
        }
    }
}

/**
 * Top Trader Reference Levels Banner:
 * Displays ENTRY -> TARGET -> INVALIDATION with R:R
 * OR prominent NO-TRADE banner when structure is ambiguous.
 */
@Composable
fun TraderLevelsBanner(
    structure: MarketStructureSnapshot,
    currentPrice: Double
) {
    val traderLevels = structure.traderLevels

    if (structure.isAmbiguous || traderLevels == null || !traderLevels.isViable || traderLevels.tradeDirection == "NO-TRADE") {
        // AMBIGUOUS / NO-TRADE BANNER
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1B2333))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(10.dp)
                .testTag("trader_levels_no_trade_banner")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = "No-Trade",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "STRUCTURAL RECOMMENDATION: NO-TRADE",
                            color = Color(0xFFFDE68A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = traderLevels?.reason
                            ?: if (structure.isAmbiguous) "Market structure is ambiguous or conflicted; awaiting definitive swing resolution"
                            else "Price compressed inside range without confirmed boundary breakout",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    } else {
        // VIABLE DIRECTIONAL TRADE PATH: ENTRY -> TARGET -> INVALIDATION
        val isBullish = traderLevels.tradeDirection == "UP"
        val pathColor = if (isBullish) Color(0xFF00E676) else Color(0xFFFF334B)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1426)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, pathColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .testTag("trader_levels_viable_banner")
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Header: Trade Direction & R:R Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(pathColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBullish) "BULLISH SUPPORT PATH" else "BEARISH RESISTANCE PATH",
                            color = pathColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // R:R Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(pathColor.copy(alpha = 0.15f))
                            .border(0.8.dp, pathColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "R:R ${traderLevels.formattedRr}",
                            color = pathColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Triple Flow: ENTRY -> TARGET -> INVALIDATION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. ENTRY / REFERENCE
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ENTRY REF",
                            color = Color(0xFF64748B),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = traderLevels.formattedEntry,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Target",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(13.dp)
                    )

                    // 2. TARGET
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "TARGET LEVEL",
                            color = Color(0xFF00E676),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = traderLevels.formattedTarget,
                            color = Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "+$${String.format(Locale.US, "%.1f", traderLevels.rewardDollars)}",
                            color = Color(0xFF4ADE80),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Invalidation",
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(13.dp)
                    )

                    // 3. INVALIDATION
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "INVALIDATION",
                            color = Color(0xFFFF5252),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = traderLevels.formattedInvalidation,
                            color = Color(0xFFFF5252),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "-$${String.format(Locale.US, "%.1f", traderLevels.riskDollars)}",
                            color = Color(0xFFF87171),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Thesis: ${traderLevels.reason}",
                    color = Color(0xFF94A3B8),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * Visualizes Swing Progression: Higher Highs (HH), Higher Lows (HL), Lower Highs (LH), Lower Lows (LL)
 * and the compression regime.
 */
@Composable
fun SwingProgressionStrip(structure: MarketStructureSnapshot) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // HH
        SwingCountChip(
            label = "HH",
            count = structure.higherHighsCount,
            activeColor = Color(0xFF00E676),
            modifier = Modifier.weight(1f)
        )
        // HL
        SwingCountChip(
            label = "HL",
            count = structure.higherLowsCount,
            activeColor = Color(0xFF00E676),
            modifier = Modifier.weight(1f)
        )
        // LH
        SwingCountChip(
            label = "LH",
            count = structure.lowerHighsCount,
            activeColor = Color(0xFFFF334B),
            modifier = Modifier.weight(1f)
        )
        // LL
        SwingCountChip(
            label = "LL",
            count = structure.lowerLowsCount,
            activeColor = Color(0xFFFF334B),
            modifier = Modifier.weight(1f)
        )

        // Compression Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0F172A))
                .border(0.8.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                text = when (structure.compressionState) {
                    CompressionState.VOLATILITY_COMPRESSION -> "ATR SQUEEZE"
                    CompressionState.RANGE_BOUND -> "RANGE BOX"
                    CompressionState.EXPANDING_VOLATILITY -> "EXPANDING"
                    CompressionState.UNCOMPRESSED -> "TREND REGIME"
                },
                color = when (structure.compressionState) {
                    CompressionState.VOLATILITY_COMPRESSION -> Color(0xFFF59E0B)
                    CompressionState.RANGE_BOUND -> Color(0xFF38BDF8)
                    CompressionState.EXPANDING_VOLATILITY -> Color(0xFFA855F7)
                    CompressionState.UNCOMPRESSED -> Color(0xFF94A3B8)
                },
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SwingCountChip(
    label: String,
    count: Int,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val isPositive = count > 0
    val color = if (isPositive) activeColor else Color(0xFF475569)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0A101E))
            .border(0.8.dp, if (isPositive) color.copy(alpha = 0.5f) else Color(0xFF1E293B), RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$label: ",
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$count",
                color = color,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black
            )
        }
    }
}

/**
 * Key Horizontal Support & Resistance Clusters Column.
 */
@Composable
fun KeyLevelsColumn(
    structure: MarketStructureSnapshot,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1120)),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.border(0.8.dp, Color(0xFF1A263C), RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "KEY S/R CLUSTERS",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Resistance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RES:",
                    color = Color(0xFFFF5252),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = structure.primaryResistance?.formattedPrice ?: "None",
                    color = Color(0xFFFF5252),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Support
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUP:",
                    color = Color(0xFF00E676),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = structure.primarySupport?.formattedPrice ?: "None",
                    color = Color(0xFF00E676),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Breakout trigger
            structure.breakoutLevel?.let { bo ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BREAKOUT:",
                        color = Color(0xFFF59E0B),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = bo.formattedPrice,
                        color = Color(0xFFFDE68A),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Rejection zone
            structure.rejectionLevel?.let { rj ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REJECTION:",
                        color = Color(0xFFA855F7),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = rj.formattedPrice,
                        color = Color(0xFFD8B4FE),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Data-Derived Mathematical Trendlines & Local Channel Column.
 */
@Composable
fun TrendlinesAndChannelColumn(
    structure: MarketStructureSnapshot,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1120)),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.border(0.8.dp, Color(0xFF1A263C), RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "DATA TRENDLINES",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Support Trendline Slope
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUP SLOPE:",
                    color = Color(0xFF64748B),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = structure.supportTrendline?.formattedSlope ?: "N/A",
                    color = if ((structure.supportTrendline?.slopePerMinute ?: 0.0) >= 0) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Resistance Trendline Slope
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RES SLOPE:",
                    color = Color(0xFF64748B),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = structure.resistanceTrendline?.formattedSlope ?: "N/A",
                    color = if ((structure.resistanceTrendline?.slopePerMinute ?: 0.0) <= 0) Color(0xFFFF5252) else Color(0xFF00E676),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Channel Width & Classification
            structure.localChannel?.let { ch ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CHANNEL:",
                        color = Color(0xFF38BDF8),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "$${String.format(Locale.US, "%.1f", ch.channelWidthDollars)} (${String.format(Locale.US, "%.2f", ch.channelWidthPercent)}%)",
                        color = Color(0xFFBAE6FD),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } ?: run {
                Text(
                    text = "Channel: Forming",
                    color = Color(0xFF475569),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Banner for explicitly validated classical chart formations.
 * (Zero false claims guaranteed).
 */
@Composable
fun ValidatedFormationBanner(formation: com.example.engine.structure.ValidatedPatternFormation) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0F1A2E))
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Validated Formation",
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formation.patternName} [${formation.status}]",
                        color = Color(0xFFE0F2FE),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    formation.targetProjectionPrice?.let { tgt ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Target: $${String.format(Locale.US, "%,.2f", tgt)}",
                            color = Color(0xFF00E676),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    text = formation.explanation,
                    color = Color(0xFF94A3B8),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
