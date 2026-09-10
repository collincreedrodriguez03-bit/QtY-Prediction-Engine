package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PricePoint
import com.example.engine.EngineState
import com.example.engine.HorizonForecast
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionEngine
import com.example.ui.marketactivity.MarketActivityBar
import com.example.ui.structure.MarketStructureCard
import kotlin.math.abs
import com.example.ui.marketactivity.MarketActivityProcessor
import com.example.ui.marketactivity.OrderFlowSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Visual styling descriptor for a specific prediction horizon.
 * Uses restrained, distinguishable colors with calibrated transparency
 * so multiple paths do not become a visual mess.
 */
data class HorizonStyleConfig(
    val seconds: Int,
    val label: String,
    val color: Color,
    val isPrimaryBaseline: Boolean = false,
    val isContractTarget: Boolean = false
)

/**
 * TOP GRAPH — IMMEDIATE SCALP FORECAST HORIZONS (CANONICAL ONLY):
 * 5s, 10s, 30s, 60s, 90s, 120s, 180s, 240s, 300s.
 */
val IMMEDIATE_SCALP_HORIZONS = listOf(
    HorizonStyleConfig(5, "5s", Color(0xFF38BDF8)),                                       // Sky Blue
    HorizonStyleConfig(10, "10s", Color(0xFF06B6D4)),                                     // Cyan
    HorizonStyleConfig(30, "30s", Color(0xFF00E676), isPrimaryBaseline = true),           // Emerald (Primary Baseline)
    HorizonStyleConfig(60, "60s", Color(0xFF10B981)),                                     // Mint
    HorizonStyleConfig(90, "90s", Color(0xFF34D399)),                                     // Teal
    HorizonStyleConfig(120, "120s", Color(0xFFF97316)),                                   // Orange
    HorizonStyleConfig(180, "180s", Color(0xFFFB923C)),                                   // Light Orange
    HorizonStyleConfig(240, "240s", Color(0xFFA78BFA)),                                   // Violet
    HorizonStyleConfig(300, "300s", Color(0xFF818CF8))                                    // Indigo (5m boundary)
)

/**
 * BOTTOM GRAPH — EXTENDED FORECAST HORIZONS (CANONICAL ONLY):
 * 30s, 60s, 180s, 300s, 600s, 900s, 1200s.
 */
val EXTENDED_FORECAST_HORIZONS = listOf(
    HorizonStyleConfig(30, "30s", Color(0xFF00E676), isPrimaryBaseline = true),           // Emerald (Baseline anchor)
    HorizonStyleConfig(60, "60s", Color(0xFF10B981)),                                     // Mint (1m)
    HorizonStyleConfig(180, "180s", Color(0xFF06B6D4)),                                   // Cyan (3m)
    HorizonStyleConfig(300, "300s", Color(0xFF818CF8)),                                   // Indigo (5m)
    HorizonStyleConfig(600, "600s", Color(0xFFEAB308)),                                   // Amber (10m)
    HorizonStyleConfig(900, "900s", Color(0xFFF97316), isContractTarget = true),          // Orange (15m Settlement Contract Target)
    HorizonStyleConfig(1200, "1200s", Color(0xFFA855F7))                                  // Purple (20m)
)

/**
 * Resolves truthful engine forecast from PredictionEngine state.
 * Mandate 9 & 10: Fail closed, single source of truth.
 * No UI or helper may instantiate PredictionEngine or independently calculate missing forecasts.
 */
fun resolveTruthfulForecast(
    engineState: EngineState,
    horizonSec: Int
): HorizonForecast? {
    return engineState.latestPrediction?.getForecast(horizonSec)
}

/**
 * Multi-Horizon Forecast Workspace:
 * Presents the complete two-level forecasting visualization:
 * 1. Top Spot Telemetry & Market Structure Context
 * 2. TOP GRAPH — IMMEDIATE SCALP FORECAST (5s, 10s, 30s, 60s, 120s, 300s)
 * 3. BOTTOM GRAPH — EXTENDED FORECAST (30s, 60s, 120s, 300s, 600s, 900s)
 * 4. Outcome & Measured Operational Accuracy Strip
 */
@Composable
fun MultiHorizonForecastWorkspace(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf("DUAL") } // "DUAL", "SCALP_ONLY", "EXTENDED_ONLY"

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Unified Telemetry Context Bar
        PrimarySpotContextCard(engineState = engineState)

        // 1b. Data-Derived Trader Market Structure & Actionable Reference Levels
        MarketStructureCard(engineState = engineState)

        // 2. Multi-Horizon Graph View Mode Selector
        GraphViewSelectorRow(
            currentMode = viewMode,
            onModeSelected = { viewMode = it }
        )

        // 3. TOP GRAPH — IMMEDIATE SCALP FORECAST (5s to 300s)
        if (viewMode == "DUAL" || viewMode == "SCALP_ONLY") {
            ImmediateScalpForecastCard(engineState = engineState)
        }

        // 4. BOTTOM GRAPH — EXTENDED FORECAST (30s to 900s)
        if (viewMode == "DUAL" || viewMode == "EXTENDED_ONLY") {
            ExtendedForecastCard(engineState = engineState)
        }

        // 5. Outcome & Operational Resolution Strip
        OperationalOutcomeStrip(engineState = engineState)
    }
}

/**
 * Top Telemetry Context Card:
 * Displays authentic real-time BTC spot price, UTC timestamp, strike reference, and primary conviction.
 */
@Composable
fun PrimarySpotContextCard(engineState: EngineState) {
    val currentPrice = if (engineState.latestPrice > 0.0) engineState.latestPrice else 0.0
    val prediction = engineState.latestPrediction
    val decision = prediction?.decision ?: "NO-TRADE"
    val score = prediction?.score ?: 0.50
    val settlementRef = prediction?.settlementReference
        ?: engineState.contractSettlementReference
        ?: engineState.rollingReferencePrice
        ?: currentPrice
    val strikeDelta = if (currentPrice > 0.0 && settlementRef > 0.0) currentPrice - settlementRef else 0.0

    val decisionColor = when (decision) {
        "UP" -> Color(0xFF00E676)
        "DOWN" -> Color(0xFFFF334B)
        else -> Color(0xFF38BDF8)
    }

    val fullDateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val nowMs = if (engineState.latestTimestamp > 0) engineState.latestTimestamp else System.currentTimeMillis()
    val exactTimestampStr = fullDateFormat.format(Date(nowMs))

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF080D1A)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1B283D), RoundedCornerShape(14.dp))
            .testTag("primary_spot_context_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT: Real BTC Spot & Time
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (engineState.isRunning) Color(0xFF00E676) else Color(0xFFFF5252))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REAL BTC SPOT",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• ${engineState.latestExchange}",
                            color = Color(0xFF475569),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$${String.format(Locale.US, "%,.2f", currentPrice)}",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "TIME (t): $exactTimestampStr",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // RIGHT: Conviction Badge & Strike Delta
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(decisionColor.copy(alpha = 0.16f))
                            .border(1.dp, decisionColor.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (decision) {
                                    "UP" -> Icons.Default.ArrowUpward
                                    "DOWN" -> Icons.Default.ArrowDownward
                                    else -> Icons.Default.RemoveCircle
                                },
                                contentDescription = decision,
                                tint = decisionColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$decision ${if (score > 0.0) String.format(Locale.US, "%.2f", score) else ""}".trim(),
                                color = decisionColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "STRIKE: ",
                            color = Color(0xFFF59E0B),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "$${String.format(Locale.US, "%,.1f", settlementRef)}",
                            color = Color(0xFFFDE68A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val deltaColor = if (strikeDelta >= 0) Color(0xFF00E676) else Color(0xFFFF334B)
                        Text(
                            text = "(${if (strikeDelta >= 0) "+" else ""}${String.format(Locale.US, "%.1f", strikeDelta)})",
                            color = deltaColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * Filter chip toggle row allowing focus on Dual View, Immediate Scalp only, or Extended only.
 */
@Composable
fun GraphViewSelectorRow(
    currentMode: String,
    onModeSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val modes = listOf(
            "DUAL" to "DUAL FORECAST (ALL)",
            "SCALP_ONLY" to "IMMEDIATE SCALP (5s-300s)",
            "EXTENDED_ONLY" to "EXTENDED (30s-900s)"
        )

        modes.forEach { (modeKey, label) ->
            val isSelected = currentMode == modeKey
            FilterChip(
                selected = isSelected,
                onClick = { onModeSelected(modeKey) },
                label = {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF1E293B),
                    selectedLabelColor = Color(0xFF00E5FF),
                    containerColor = Color(0xFF0C1322),
                    labelColor = Color(0xFF64748B)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                    selectedBorderColor = Color(0xFF00E5FF),
                    borderWidth = 1.dp
                ),
                modifier = Modifier.testTag("filter_chip_$modeKey")
            )
        }
    }
}

/**
 * TOP GRAPH — IMMEDIATE SCALP FORECAST
 * Explicitly visualizes:
 * - Horizons: 5s, 10s, 30s, 60s, 90s, 120s, 180s, 240s, 300s (up to 5 minutes)
 * - Solid Primary Price Line = authentic real-time BTC price history (-90s to NOW)
 * - Forecast Lines = model projections extending from (NOW, currentPrice) into future time
 * - Restrained distinguishable colors & transparency
 * - No fabricated confidence bands
 * - Transparent attribution to single multi-scale econometric engine
 */
@Composable
fun ImmediateScalpForecastCard(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    val currentPrice = engineState.latestPrice
    val nowMs = if (engineState.latestTimestamp > 0) engineState.latestTimestamp else System.currentTimeMillis()
    val settlementRef = engineState.latestPrediction?.settlementReference
        ?: engineState.contractSettlementReference
        ?: engineState.rollingReferencePrice
        ?: currentPrice

    // Resolve canonical immediate scalp forecasts truthfully
    val scalpForecasts = remember(engineState.latestPrediction, currentPrice, nowMs) {
        IMMEDIATE_SCALP_HORIZONS.mapNotNull { config ->
            val forecast = resolveTruthfulForecast(engineState, config.seconds)
            if (forecast != null) config to forecast else null
        }
    }

    // Historical prices (up to ~90s back)
    val historicalPrices = remember(engineState.recentPrices, currentPrice) {
        if (engineState.recentPrices.isNotEmpty()) {
            engineState.recentPrices.takeLast(45)
        } else if (currentPrice > 0.0) {
            listOf(currentPrice)
        } else {
            emptyList()
        }
    }

    // Authentic observed historical points for contextual volume/order-flow bars
    val rawPoints = remember(engineState.recentPoints, historicalPrices, currentPrice) {
        if (engineState.recentPoints.isNotEmpty()) {
            engineState.recentPoints.takeLast(45)
        } else {
            // When points are not yet populated, set volume = 0.0 (flags missing state, NO dummy values)
            historicalPrices.map { p ->
                PricePoint(
                    price = p,
                    timestamp = nowMs,
                    volume = 0.0,
                    exchange = engineState.latestExchange
                )
            }
        }
    }

    val activityBars = remember(rawPoints) {
        MarketActivityProcessor.process(rawPoints, lookbackCount = 45)
    }

    val orderFlowSummary = remember(activityBars) {
        MarketActivityProcessor.computeOrderFlowSummary(activityBars)
    }

    var showActivityBars by remember { mutableStateOf(true) }

    // Pulse animation for live spot node
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_scalp")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha_scalp"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF080D1A)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1B283D), RoundedCornerShape(14.dp))
            .testTag("immediate_scalp_forecast_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Title, Horizon List & Model Attribution
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = "Scalp Graph",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TOP GRAPH — IMMEDIATE SCALP FORECAST",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Horizons: 5s, 10s, 30s, 60s, 120s, 300s (5m)",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Attribution badge: single multi-scale model
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0F172A))
                        .border(0.8.dp, Color(0xFF334155), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "QtY Canonical Model • 6 Scalp Scales",
                        color = Color(0xFF38BDF8),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Canvas Chart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF050811))
                    .border(1.dp, Color(0xFF152238), RoundedCornerShape(10.dp))
                    .testTag("scalp_forecast_canvas")
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    val leftPad = 8.dp.toPx()
                    val rightPad = 68.dp.toPx()
                    val topPad = 18.dp.toPx()
                    val bottomPad = 26.dp.toPx()

                    val graphW = w - leftPad - rightPad
                    val graphH = h - topPad - bottomPad

                    // Time axis partitioning:
                    // 35% history (-90s to NOW), 65% future (0 to 300s)
                    val nowX = leftPad + (graphW * 0.35f)
                    val futureW = graphW * 0.65f

                    // Calculate Y price bounds encompassing history, current, strike, and all 9 scalp predictions
                    var minP = historicalPrices.minOrNull() ?: currentPrice
                    var maxP = historicalPrices.maxOrNull() ?: currentPrice
                    minP = min(minP, currentPrice)
                    maxP = max(maxP, currentPrice)
                    minP = min(minP, settlementRef)
                    maxP = max(maxP, settlementRef)

                    for ((_, forecast) in scalpForecasts) {
                        minP = min(minP, forecast.predictedPrice)
                        maxP = max(maxP, forecast.predictedPrice)
                    }

                    // Incorporate localized data-derived market structure key levels
                    val marketStructure = engineState.marketStructure
                    marketStructure?.primarySupport?.price?.let {
                        if (abs(it - currentPrice) / currentPrice < 0.008) minP = min(minP, it)
                    }
                    marketStructure?.primaryResistance?.price?.let {
                        if (abs(it - currentPrice) / currentPrice < 0.008) maxP = max(maxP, it)
                    }
                    marketStructure?.traderLevels?.let { tl ->
                        if (tl.isViable && tl.tradeDirection != "NO-TRADE") {
                            if (abs(tl.targetLevel - currentPrice) / currentPrice < 0.008) {
                                minP = min(minP, tl.targetLevel)
                                maxP = max(maxP, tl.targetLevel)
                            }
                            if (abs(tl.invalidationLevel - currentPrice) / currentPrice < 0.008) {
                                minP = min(minP, tl.invalidationLevel)
                                maxP = max(maxP, tl.invalidationLevel)
                            }
                        }
                    }

                    val spread = max(10.0, maxP - minP)
                    val yMin = minP - (spread * 0.16)
                    val yMax = maxP + (spread * 0.16)
                    val yRange = max(1.0, yMax - yMin)

                    fun priceToY(price: Double): Float {
                        val norm = (price - yMin) / yRange
                        return (topPad + graphH * (1.0f - norm.toFloat())).coerceIn(topPad, topPad + graphH)
                    }

                    // 0. Transparent Background Market-Activity Bars (Observed Volume & Order Flow, strictly t <= NOW)
                    // Visual hierarchy: transparent background context. Never drawn in future forecast zone (t > NOW).
                    if (showActivityBars && activityBars.isNotEmpty()) {
                        val barStep = (nowX - leftPad) / (activityBars.size - 1).coerceAtLeast(1)
                        val maxBarHeight = graphH * 0.28f
                        val barWidth = max(2.5f, barStep - 2.5f)

                        for (i in activityBars.indices) {
                            val bar = activityBars[i]
                            if (bar.isMissingOrZero || bar.normalizedHeight <= 0.001f) continue

                            val x = leftPad + (i * barStep)
                            val barH = bar.normalizedHeight * maxBarHeight
                            val barTop = (topPad + graphH) - barH
                            val barLeft = x - (barWidth / 2f)

                            val barColor = when {
                                bar.isBuyerDominant -> Color(0xFF10B981) // Buyer-aggressor tick
                                else -> Color(0xFFF43F5E) // Seller-aggressor tick
                            }

                            // Subdued transparent background bar body
                            drawRect(
                                color = barColor.copy(alpha = if (bar.isSurge) 0.32f else 0.20f),
                                topLeft = Offset(barLeft, barTop),
                                size = Size(barWidth, barH)
                            )

                            // Institutional volume surge highlight cap
                            if (bar.isSurge) {
                                drawLine(
                                    color = barColor.copy(alpha = 0.65f),
                                    start = Offset(barLeft, barTop),
                                    end = Offset(barLeft + barWidth, barTop),
                                    strokeWidth = 1.5.dp.toPx()
                                )
                            }
                        }
                    }

                    // 1. Grid & Price Labels
                    val gridSteps = 4
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#475569")
                        textSize = 20f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }

                    for (i in 0..gridSteps) {
                        val gPrice = yMin + (yRange * (i.toDouble() / gridSteps))
                        val gY = priceToY(gPrice)
                        drawLine(
                            color = Color(0xFF131E30),
                            start = Offset(leftPad, gY),
                            end = Offset(leftPad + graphW, gY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.US, "%,.0f", gPrice),
                            leftPad + graphW + 6f,
                            gY + 6f,
                            textPaint
                        )
                    }

                    // 2. Strike Reference Horizontal Line
                    val strikeY = priceToY(settlementRef)
                    drawLine(
                        color = Color(0xFFF59E0B).copy(alpha = 0.80f),
                        start = Offset(leftPad, strikeY),
                        end = Offset(leftPad + graphW, strikeY),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                    )
                    val strikePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#F59E0B")
                        textSize = 19f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }
                    drawContext.canvas.nativeCanvas.drawText("STRIKE", leftPad + graphW + 6f, strikeY - 4f, strikePaint)

                    // 2b. Data-Derived Key Market Structure Support & Resistance
                    marketStructure?.primarySupport?.let { sup ->
                        val supY = priceToY(sup.price)
                        drawLine(
                            color = Color(0xFF00E676).copy(alpha = 0.65f),
                            start = Offset(leftPad, supY),
                            end = Offset(leftPad + graphW, supY),
                            strokeWidth = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                        )
                        val supPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#00E676")
                            textSize = 17f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        drawContext.canvas.nativeCanvas.drawText("SUP $${String.format(Locale.US, "%,.0f", sup.price)}", leftPad + 4f, supY - 3f, supPaint)
                    }

                    marketStructure?.primaryResistance?.let { res ->
                        val resY = priceToY(res.price)
                        drawLine(
                            color = Color(0xFFFF5252).copy(alpha = 0.65f),
                            start = Offset(leftPad, resY),
                            end = Offset(leftPad + graphW, resY),
                            strokeWidth = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                        )
                        val resPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#FF5252")
                            textSize = 17f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        drawContext.canvas.nativeCanvas.drawText("RES $${String.format(Locale.US, "%,.0f", res.price)}", leftPad + 4f, resY - 3f, resPaint)
                    }

                    // 2c. Target & Invalidation Levels & Visual Trend Path
                    val traderLevels = marketStructure?.traderLevels
                    if (traderLevels != null && traderLevels.isViable && traderLevels.tradeDirection != "NO-TRADE") {
                        val isBullish = traderLevels.tradeDirection == "UP"
                        val targetY = priceToY(traderLevels.targetLevel)
                        val invalY = priceToY(traderLevels.invalidationLevel)

                        // Invalidation Level (Dashed red/amber line across entire graph)
                        drawLine(
                            color = Color(0xFFFF334B).copy(alpha = 0.75f),
                            start = Offset(leftPad, invalY),
                            end = Offset(leftPad + graphW, invalY),
                            strokeWidth = 1.3.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 3f), 0f)
                        )
                        val invalPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#FF334B")
                            textSize = 17f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                            isFakeBoldText = true
                        }
                        drawContext.canvas.nativeCanvas.drawText("INVAL $${String.format(Locale.US, "%,.0f", traderLevels.invalidationLevel)}", leftPad + graphW - 90f, invalY - 3f, invalPaint)

                        // Target Level (Dashed green line across future section)
                        drawLine(
                            color = Color(0xFF00E676).copy(alpha = 0.75f),
                            start = Offset(nowX, targetY),
                            end = Offset(leftPad + graphW, targetY),
                            strokeWidth = 1.3.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 3f), 0f)
                        )
                        val targetPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#00E676")
                            textSize = 17f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                            isFakeBoldText = true
                        }
                        drawContext.canvas.nativeCanvas.drawText("TARGET $${String.format(Locale.US, "%,.0f", traderLevels.targetLevel)}", leftPad + graphW - 90f, targetY - 3f, targetPaint)

                        // Visual Path Corridor:
                        // "A bullish structure should visually communicate its support/trend path. A bearish structure should communicate resistance/trend path."
                        val pathEnvelope = Path().apply {
                            moveTo(nowX, priceToY(currentPrice))
                            lineTo(leftPad + graphW, targetY)
                            lineTo(leftPad + graphW, targetY + if (isBullish) 12f else -12f)
                            lineTo(nowX, priceToY(currentPrice))
                            close()
                        }
                        drawPath(
                            path = pathEnvelope,
                            color = if (isBullish) Color(0xFF00E676).copy(alpha = 0.08f) else Color(0xFFFF334B).copy(alpha = 0.08f)
                        )
                    }

                    // 3. Vertical Dividing Line at "NOW (t)"
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = 0.55f),
                        start = Offset(nowX, topPad),
                        end = Offset(nowX, topPad + graphH),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                    )

                    // 4. Authentic Historical BTC Price Line (Solid cyan line with gradient fill up to NOW)
                    if (historicalPrices.isNotEmpty()) {
                        val historyStep = (nowX - leftPad) / (historicalPrices.size - 1).coerceAtLeast(1)
                        val linePath = Path()
                        val fillPath = Path()

                        val firstX = leftPad
                        val firstY = priceToY(historicalPrices.first())
                        linePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, topPad + graphH)
                        fillPath.lineTo(firstX, firstY)

                        for (i in 1 until historicalPrices.size) {
                            val x = leftPad + (i * historyStep)
                            val y = priceToY(historicalPrices[i])
                            val prevX = leftPad + ((i - 1) * historyStep)
                            val prevY = priceToY(historicalPrices[i - 1])
                            val midX = (prevX + x) / 2f
                            linePath.quadraticTo(prevX, prevY, midX, (prevY + y) / 2f)
                            fillPath.lineTo(x, y)
                        }

                        val currentY = priceToY(currentPrice)
                        linePath.lineTo(nowX, currentY)
                        fillPath.lineTo(nowX, currentY)
                        fillPath.lineTo(nowX, topPad + graphH)
                        fillPath.close()

                        // Ambient Area Fill
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.14f), Color(0xFF00E5FF).copy(alpha = 0.00f)),
                                startY = topPad,
                                endY = topPad + graphH
                            )
                        )

                        // Solid Primary Price Line
                        drawPath(
                            path = linePath,
                            color = Color(0xFF00E5FF),
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // 4b. Swing High / Low Markers with Progression Labels (HH, HL, LH, LL)
                        marketStructure?.recentSwings?.forEach { swing ->
                            val swingAgeMs = nowMs - swing.timestamp
                            val maxAgeMs = 90_000L // 90 seconds visible historical window
                            if (swingAgeMs in 0..maxAgeMs) {
                                val progress = 1.0f - (swingAgeMs.toFloat() / maxAgeMs)
                                val sx = leftPad + (progress * (nowX - leftPad))
                                val sy = priceToY(swing.price)

                                val isHigh = swing.type == com.example.engine.structure.SwingType.SWING_HIGH
                                val dotColor = if (isHigh) Color(0xFFFF5252) else Color(0xFF00E676)

                                drawCircle(
                                    color = dotColor.copy(alpha = 0.35f),
                                    radius = 4.dp.toPx(),
                                    center = Offset(sx, sy)
                                )
                                drawCircle(
                                    color = dotColor,
                                    radius = 2.2.dp.toPx(),
                                    center = Offset(sx, sy)
                                )

                                swing.sequenceLabel?.let { lbl ->
                                    val swingLabelPaint = android.graphics.Paint().apply {
                                        color = if (isHigh) android.graphics.Color.parseColor("#FF6E6E") else android.graphics.Color.parseColor("#69F0AE")
                                        textSize = 15f
                                        isAntiAlias = true
                                        typeface = android.graphics.Typeface.MONOSPACE
                                        isFakeBoldText = true
                                    }
                                    val textY = if (isHigh) sy - 6f else sy + 14f
                                    drawContext.canvas.nativeCanvas.drawText(lbl, sx - 8f, textY, swingLabelPaint)
                                }
                            }
                        }
                    }

                    val currentY = priceToY(currentPrice)

                    // 5. Forecast Lines extending from (NOW, currentPrice) into future time
                    // Strictly starts at current authentic price and extends only into future
                    val maxFutureSec = 300f // 5 minutes

                    scalpForecasts.forEachIndexed { index, (config, forecast) ->
                        val horizonProgress = (config.seconds.toFloat() / maxFutureSec).coerceIn(0f, 1f)
                        val targetX = nowX + (horizonProgress * futureW)
                        val targetY = priceToY(forecast.predictedPrice)

                        val isPrimary = config.isPrimaryBaseline
                        val strokeWidth = if (isPrimary) 2.2.dp.toPx() else 1.4.dp.toPx()
                        val alpha = if (isPrimary) 0.95f else 0.75f
                        val dashEffect = if (isPrimary) {
                            PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f)
                        } else {
                            PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        }

                        // Smooth quadratic forecast path from (nowX, currentY) to (targetX, targetY)
                        val predPath = Path().apply {
                            moveTo(nowX, currentY)
                            val cx = (nowX + targetX) / 2f
                            val cy = (currentY + targetY) / 2f
                            quadraticTo(cx, cy, targetX, targetY)
                        }

                        drawPath(
                            path = predPath,
                            color = config.color.copy(alpha = alpha),
                            style = Stroke(width = strokeWidth, pathEffect = dashEffect, cap = StrokeCap.Round)
                        )

                        // End-node destination marker
                        val nodeRadius = if (isPrimary) 4.5.dp.toPx() else 3.2.dp.toPx()
                        drawCircle(
                            color = config.color.copy(alpha = 0.35f),
                            radius = nodeRadius + 3.dp.toPx(),
                            center = Offset(targetX, targetY)
                        )
                        drawCircle(
                            color = config.color,
                            radius = nodeRadius,
                            center = Offset(targetX, targetY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 1.4.dp.toPx(),
                            center = Offset(targetX, targetY)
                        )

                        // Horizon label alternating above / below node to prevent text collisions
                        val labelPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor(
                                String.format("#%06X", 0xFFFFFF and config.color.hashCode())
                            )
                            textSize = if (isPrimary) 20f else 17f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                            isFakeBoldText = isPrimary
                        }
                        val textY = if (index % 2 == 0) targetY - 8f else targetY + 18f
                        drawContext.canvas.nativeCanvas.drawText(config.label, targetX - 10f, textY, labelPaint)
                    }

                    // 6. Live "NOW (t)" Spot Node (Pulsing Active Point)
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = pulseAlpha),
                        radius = 8.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = 4.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.8.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )

                    // 7. Time Axis Markers
                    val axisPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#64748B")
                        textSize = 20f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    val nowPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#00E5FF")
                        textSize = 20f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }

                    drawContext.canvas.nativeCanvas.drawText("-90s", leftPad + 4f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("NOW (t)", nowX - 25f, topPad + graphH + 20f, nowPaint)
                    drawContext.canvas.nativeCanvas.drawText("+5s", nowX + (5f / 300f * futureW) - 10f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+10s", nowX + (10f / 300f * futureW) - 12f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+30s", nowX + (30f / 300f * futureW) - 15f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+60s", nowX + (60f / 300f * futureW) - 15f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+120s", nowX + (120f / 300f * futureW) - 18f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+300s (5m)", nowX + futureW - 45f, topPad + graphH + 20f, axisPaint)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Contextual Market-Activity & Order Flow Bar (Historical Observed Data)
            MarketActivityContextStrip(
                orderFlowSummary = orderFlowSummary,
                showActivityBars = showActivityBars,
                onToggleActivityBars = { showActivityBars = !showActivityBars },
                kalshiVerification = engineState.kalshiVerification,
                sourceExchange = engineState.latestExchange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Horizon Data Chips Strip: Exact values & deltas for all 9 scalp horizons
            Text(
                text = "IMMEDIATE SCALP TARGETS (SPOT DELTA):",
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                scalpForecasts.forEach { (config, forecast) ->
                    val delta = forecast.predictedPrice - currentPrice
                    val deltaColor = if (delta >= 0) Color(0xFF00E676) else Color(0xFFFF334B)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C1322))
                            .border(
                                1.dp,
                                if (config.isPrimaryBaseline) Color(0xFF00E676).copy(alpha = 0.6f) else Color(0xFF1E293B),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(config.color)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = config.label + (if (config.isPrimaryBaseline) " ★" else ""),
                                color = config.color,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$${String.format(Locale.US, "%,.1f", forecast.predictedPrice)}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "(${if (delta >= 0) "+" else ""}${String.format(Locale.US, "%.1f", delta)})",
                                color = deltaColor,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * BOTTOM GRAPH — EXTENDED FORECAST
 * Explicitly visualizes:
 * - Horizons: 30s, 60s, 180s, 300s, 600s, 900s, 1200s (up to 20 minutes)
 * - Highlights 900s (15-Minute Contract Settlement Reference Target)
 * - Solid Primary Price Line = authentic real-time BTC price history (-300s / 5m to NOW)
 * - Forecast Lines = model projections extending from (NOW, currentPrice) into future time
 * - Restrained distinguishable colors & transparency
 * - No fabricated confidence bands
 * - Transparent attribution to single multi-scale econometric engine
 */
@Composable
fun ExtendedForecastCard(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    val currentPrice = engineState.latestPrice
    val nowMs = if (engineState.latestTimestamp > 0) engineState.latestTimestamp else System.currentTimeMillis()
    val settlementRef = engineState.latestPrediction?.settlementReference
        ?: engineState.contractSettlementReference
        ?: engineState.rollingReferencePrice
        ?: currentPrice

    // Resolve canonical extended forecasts truthfully
    val extendedForecasts = remember(engineState.latestPrediction, currentPrice, nowMs) {
        EXTENDED_FORECAST_HORIZONS.mapNotNull { config ->
            val forecast = resolveTruthfulForecast(engineState, config.seconds)
            if (forecast != null) config to forecast else null
        }
    }

    // Historical prices (up to ~300s back / 5 min)
    val historicalPrices = remember(engineState.recentPrices, currentPrice) {
        if (engineState.recentPrices.isNotEmpty()) {
            engineState.recentPrices.takeLast(120)
        } else if (currentPrice > 0.0) {
            listOf(currentPrice)
        } else {
            emptyList()
        }
    }

    // Authentic observed historical points for extended contextual volume/order-flow bars
    val rawPoints = remember(engineState.recentPoints, historicalPrices, currentPrice) {
        if (engineState.recentPoints.isNotEmpty()) {
            engineState.recentPoints.takeLast(120)
        } else {
            historicalPrices.map { p ->
                PricePoint(
                    price = p,
                    timestamp = nowMs,
                    volume = 0.0,
                    exchange = engineState.latestExchange
                )
            }
        }
    }

    val activityBars = remember(rawPoints) {
        MarketActivityProcessor.process(rawPoints, lookbackCount = 120)
    }

    val orderFlowSummary = remember(activityBars) {
        MarketActivityProcessor.computeOrderFlowSummary(activityBars)
    }

    var showActivityBars by remember { mutableStateOf(true) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_ext")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha_ext"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF080D1A)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1B283D), RoundedCornerShape(14.dp))
            .testTag("extended_forecast_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Title, Horizon List & Model Attribution
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Extended Graph",
                            tint = Color(0xFFF97316),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BOTTOM GRAPH — EXTENDED FORECAST",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Horizons: 30s, 60s, 120s, 300s, 600s, 900s (15m)",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // 15m Settlement Highlight Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0F172A))
                        .border(0.8.dp, Color(0xFFF97316).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "15m Settlement: 900s",
                        color = Color(0xFFF97316),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Canvas Chart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF050811))
                    .border(1.dp, Color(0xFF152238), RoundedCornerShape(10.dp))
                    .testTag("extended_forecast_canvas")
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    val leftPad = 8.dp.toPx()
                    val rightPad = 68.dp.toPx()
                    val topPad = 18.dp.toPx()
                    val bottomPad = 26.dp.toPx()

                    val graphW = w - leftPad - rightPad
                    val graphH = h - topPad - bottomPad

                    // Time axis partitioning:
                    // 25% history (-300s / 5m to NOW), 75% future (0 to 1200s / 20m)
                    val nowX = leftPad + (graphW * 0.25f)
                    val futureW = graphW * 0.75f

                    // Calculate Y price bounds encompassing history, current, strike, and all 7 extended predictions
                    var minP = historicalPrices.minOrNull() ?: currentPrice
                    var maxP = historicalPrices.maxOrNull() ?: currentPrice
                    minP = min(minP, currentPrice)
                    maxP = max(maxP, currentPrice)
                    minP = min(minP, settlementRef)
                    maxP = max(maxP, settlementRef)

                    for ((_, forecast) in extendedForecasts) {
                        minP = min(minP, forecast.predictedPrice)
                        maxP = max(maxP, forecast.predictedPrice)
                    }

                    val spread = max(14.0, maxP - minP)
                    val yMin = minP - (spread * 0.16)
                    val yMax = maxP + (spread * 0.16)
                    val yRange = max(1.0, yMax - yMin)

                    fun priceToY(price: Double): Float {
                        val norm = (price - yMin) / yRange
                        return (topPad + graphH * (1.0f - norm.toFloat())).coerceIn(topPad, topPad + graphH)
                    }

                    // 0. Transparent Background Market-Activity Bars (Observed Volume & Order Flow, strictly t <= NOW)
                    // Visual hierarchy: transparent background context. Never drawn in future forecast zone (t > NOW).
                    if (showActivityBars && activityBars.isNotEmpty()) {
                        val barStep = (nowX - leftPad) / (activityBars.size - 1).coerceAtLeast(1)
                        val maxBarHeight = graphH * 0.28f
                        val barWidth = max(2.0f, barStep - 2.0f)

                        for (i in activityBars.indices) {
                            val bar = activityBars[i]
                            if (bar.isMissingOrZero || bar.normalizedHeight <= 0.001f) continue

                            val x = leftPad + (i * barStep)
                            val barH = bar.normalizedHeight * maxBarHeight
                            val barTop = (topPad + graphH) - barH
                            val barLeft = x - (barWidth / 2f)

                            val barColor = when {
                                bar.isBuyerDominant -> Color(0xFF10B981)
                                else -> Color(0xFFF43F5E)
                            }

                            drawRect(
                                color = barColor.copy(alpha = if (bar.isSurge) 0.30f else 0.18f),
                                topLeft = Offset(barLeft, barTop),
                                size = Size(barWidth, barH)
                            )

                            if (bar.isSurge) {
                                drawLine(
                                    color = barColor.copy(alpha = 0.60f),
                                    start = Offset(barLeft, barTop),
                                    end = Offset(barLeft + barWidth, barTop),
                                    strokeWidth = 1.2.dp.toPx()
                                )
                            }
                        }
                    }

                    // 1. Grid & Price Labels
                    val gridSteps = 4
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#475569")
                        textSize = 20f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }

                    for (i in 0..gridSteps) {
                        val gPrice = yMin + (yRange * (i.toDouble() / gridSteps))
                        val gY = priceToY(gPrice)
                        drawLine(
                            color = Color(0xFF131E30),
                            start = Offset(leftPad, gY),
                            end = Offset(leftPad + graphW, gY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.US, "%,.0f", gPrice),
                            leftPad + graphW + 6f,
                            gY + 6f,
                            textPaint
                        )
                    }

                    // 2. Strike Reference Horizontal Line
                    val strikeY = priceToY(settlementRef)
                    drawLine(
                        color = Color(0xFFF59E0B).copy(alpha = 0.80f),
                        start = Offset(leftPad, strikeY),
                        end = Offset(leftPad + graphW, strikeY),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                    )
                    val strikePaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#F59E0B")
                        textSize = 19f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }
                    drawContext.canvas.nativeCanvas.drawText("STRIKE", leftPad + graphW + 6f, strikeY - 4f, strikePaint)

                    // 3. Vertical Dividing Line at "NOW (t)"
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = 0.55f),
                        start = Offset(nowX, topPad),
                        end = Offset(nowX, topPad + graphH),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                    )

                    // 4. Authentic Historical BTC Price Line (Solid cyan line with gradient fill up to NOW)
                    if (historicalPrices.isNotEmpty()) {
                        val historyStep = (nowX - leftPad) / (historicalPrices.size - 1).coerceAtLeast(1)
                        val linePath = Path()
                        val fillPath = Path()

                        val firstX = leftPad
                        val firstY = priceToY(historicalPrices.first())
                        linePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, topPad + graphH)
                        fillPath.lineTo(firstX, firstY)

                        for (i in 1 until historicalPrices.size) {
                            val x = leftPad + (i * historyStep)
                            val y = priceToY(historicalPrices[i])
                            val prevX = leftPad + ((i - 1) * historyStep)
                            val prevY = priceToY(historicalPrices[i - 1])
                            val midX = (prevX + x) / 2f
                            linePath.quadraticTo(prevX, prevY, midX, (prevY + y) / 2f)
                            fillPath.lineTo(x, y)
                        }

                        val currentY = priceToY(currentPrice)
                        linePath.lineTo(nowX, currentY)
                        fillPath.lineTo(nowX, currentY)
                        fillPath.lineTo(nowX, topPad + graphH)
                        fillPath.close()

                        // Ambient Area Fill
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.14f), Color(0xFF00E5FF).copy(alpha = 0.00f)),
                                startY = topPad,
                                endY = topPad + graphH
                            )
                        )

                        // Solid Primary Price Line
                        drawPath(
                            path = linePath,
                            color = Color(0xFF00E5FF),
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    val currentY = priceToY(currentPrice)

                    // 5. Extended Forecast Lines extending from (NOW, currentPrice) into future time
                    val maxFutureSec = 900f // 15 minutes canonical contract horizon

                    extendedForecasts.forEachIndexed { index, (config, forecast) ->
                        val horizonProgress = (config.seconds.toFloat() / maxFutureSec).coerceIn(0f, 1f)
                        val targetX = nowX + (horizonProgress * futureW)
                        val targetY = priceToY(forecast.predictedPrice)

                        val isPrimary = config.isPrimaryBaseline || config.isContractTarget
                        val strokeWidth = if (isPrimary) 2.2.dp.toPx() else 1.4.dp.toPx()
                        val alpha = if (isPrimary) 0.95f else 0.75f
                        val dashEffect = if (isPrimary) {
                            PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f)
                        } else {
                            PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        }

                        val predPath = Path().apply {
                            moveTo(nowX, currentY)
                            val cx = (nowX + targetX) / 2f
                            val cy = (currentY + targetY) / 2f
                            quadraticTo(cx, cy, targetX, targetY)
                        }

                        drawPath(
                            path = predPath,
                            color = config.color.copy(alpha = alpha),
                            style = Stroke(width = strokeWidth, pathEffect = dashEffect, cap = StrokeCap.Round)
                        )

                        // End-node destination marker
                        val nodeRadius = if (isPrimary) 4.5.dp.toPx() else 3.2.dp.toPx()
                        drawCircle(
                            color = config.color.copy(alpha = 0.35f),
                            radius = nodeRadius + 3.dp.toPx(),
                            center = Offset(targetX, targetY)
                        )
                        drawCircle(
                            color = config.color,
                            radius = nodeRadius,
                            center = Offset(targetX, targetY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 1.4.dp.toPx(),
                            center = Offset(targetX, targetY)
                        )

                        // Horizon label alternating above / below node
                        val labelPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor(
                                String.format("#%06X", 0xFFFFFF and config.color.hashCode())
                            )
                            textSize = if (isPrimary) 20f else 17f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                            isFakeBoldText = isPrimary
                        }
                        val textY = if (index % 2 == 0) targetY - 8f else targetY + 18f
                        drawContext.canvas.nativeCanvas.drawText(config.label, targetX - 12f, textY, labelPaint)
                    }

                    // 6. Live "NOW (t)" Spot Node (Pulsing Active Point)
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = pulseAlpha),
                        radius = 8.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = 4.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.8.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )

                    // 7. Time Axis Markers
                    val axisPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#64748B")
                        textSize = 20f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    val nowPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#00E5FF")
                        textSize = 20f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                        isFakeBoldText = true
                    }

                    drawContext.canvas.nativeCanvas.drawText("-300s (5m)", leftPad + 4f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("NOW (t)", nowX - 25f, topPad + graphH + 20f, nowPaint)
                    drawContext.canvas.nativeCanvas.drawText("+30s", nowX + (30f / 900f * futureW) - 10f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+60s", nowX + (60f / 900f * futureW) - 12f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+120s (2m)", nowX + (120f / 900f * futureW) - 16f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+300s (5m)", nowX + (300f / 900f * futureW) - 20f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+600s (10m)", nowX + (600f / 900f * futureW) - 22f, topPad + graphH + 20f, axisPaint)
                    drawContext.canvas.nativeCanvas.drawText("+900s (15m)", nowX + futureW - 48f, topPad + graphH + 20f, axisPaint)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Contextual Market-Activity & Order Flow Bar (Historical Observed Data)
            MarketActivityContextStrip(
                orderFlowSummary = orderFlowSummary,
                showActivityBars = showActivityBars,
                onToggleActivityBars = { showActivityBars = !showActivityBars },
                kalshiVerification = engineState.kalshiVerification,
                sourceExchange = engineState.latestExchange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Horizon Data Chips Strip: Exact values & deltas for all 7 extended horizons
            Text(
                text = "EXTENDED TARGETS (SPOT DELTA):",
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                extendedForecasts.forEach { (config, forecast) ->
                    val delta = forecast.predictedPrice - currentPrice
                    val deltaColor = if (delta >= 0) Color(0xFF00E676) else Color(0xFFFF334B)
                    val borderColor = when {
                        config.isContractTarget -> Color(0xFFF97316).copy(alpha = 0.7f)
                        config.isPrimaryBaseline -> Color(0xFF00E676).copy(alpha = 0.6f)
                        else -> Color(0xFF1E293B)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C1322))
                            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(config.color)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = config.label + (if (config.isContractTarget) " (15m)" else if (config.isPrimaryBaseline) " ★" else ""),
                                color = config.color,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$${String.format(Locale.US, "%,.1f", forecast.predictedPrice)}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "(${if (delta >= 0) "+" else ""}${String.format(Locale.US, "%.1f", delta)})",
                                color = deltaColor,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Operational Outcome & Performance Strip:
 * Reports last resolution and measured live accuracy without cluttering the screen.
 */
@Composable
fun OperationalOutcomeStrip(engineState: EngineState) {
    val stats = engineState.performanceStats
    val lastResolved = engineState.recentPredictions.firstOrNull {
        it.result != null && it.result != "PENDING" && it.result != "UNRESOLVED"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1527))
            .border(1.dp, Color(0xFF1C2B45), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("multi_horizon_outcome_strip"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Last Outcome
        Row(verticalAlignment = Alignment.CenterVertically) {
            val outcomeResult = lastResolved?.result ?: "PENDING"
            val (icon, outcomeColor) = when (outcomeResult) {
                "CORRECT" -> Icons.Default.CheckCircle to Color(0xFF00E676)
                "INCORRECT" -> Icons.Default.RemoveCircle to Color(0xFFFF334B)
                "TIE" -> Icons.Default.RemoveCircle to Color(0xFFFFD600)
                else -> Icons.Default.HourglassEmpty to Color(0xFF38BDF8)
            }

            Icon(
                imageVector = icon,
                contentDescription = "Outcome",
                tint = outcomeColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "LAST RESOLUTION: $outcomeResult",
                color = outcomeColor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            if (lastResolved?.actualPrice != null) {
                Spacer(modifier = Modifier.width(4.dp))
                val delta = lastResolved.actualPrice!! - lastResolved.settlementReference
                Text(
                    text = "(${if (delta >= 0) "+" else ""}${String.format(Locale.US, "%.1f", delta)})",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Win Rate
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "OPERATIONAL ACCURACY: ",
                color = Color(0xFF64748B),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            val wrColor = if (stats.operationalWinRatePercent >= 75.0) {
                Color(0xFF00E676)
            } else if (stats.operationalWinRatePercent >= 50.0) {
                Color(0xFF38BDF8)
            } else {
                Color(0xFFFFD600)
            }
            Text(
                text = "${stats.operationalWinRatePercent}%",
                color = wrColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "(${stats.operationalCorrectCount}/${stats.operationalResolvedCount})",
                color = Color(0xFF94A3B8),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Contextual Market-Activity & Order Flow Status Strip.
 * Displays observed market information, normalization peak, net aggressor flow delta,
 * derivative Kalshi order-book depth distinction, and user toggle control.
 *
 * CRITICAL INTEGRITY MANDATE:
 * Displays exclusively OBSERVED data (t <= NOW) and explicitly states that forecasts are price-only.
 */
@Composable
fun MarketActivityContextStrip(
    orderFlowSummary: OrderFlowSummary,
    showActivityBars: Boolean,
    onToggleActivityBars: () -> Unit,
    kalshiVerification: com.example.kalshi.KalshiVerificationResult? = null,
    sourceExchange: String = "CONSOLIDATED SPOT",
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF060A14)),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF152238), RoundedCornerShape(8.dp))
            .testTag("market_activity_context_strip")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Header Row: Authentic Status, Integrity Badge & Visibility Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (orderFlowSummary.isDataAvailable) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                    )
                    Text(
                        text = "OBSERVED MARKET ACTIVITY (HISTORICAL t <= NOW)",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.3.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Integrity statement: Forecasts are price-only, never predicted volume
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF0F172A))
                            .border(0.5.dp, Color(0xFF334155), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Forecast: Price Only (No Predicted Vol)",
                            color = Color(0xFF64748B),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Visibility Toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (showActivityBars) Color(0xFF1E293B) else Color(0xFF0B1220))
                            .border(
                                0.8.dp,
                                if (showActivityBars) Color(0xFF38BDF8).copy(alpha = 0.6f) else Color(0xFF1E293B),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onToggleActivityBars() }
                            .padding(horizontal = 6.dp, vertical = 2.5.dp)
                            .testTag("toggle_activity_bars")
                    ) {
                        Text(
                            text = if (showActivityBars) "BARS: ON" else "BARS: OFF",
                            color = if (showActivityBars) Color(0xFF38BDF8) else Color(0xFF64748B),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Metrics Row: Color Key, Order Flow Imbalance, Window Peak, Kalshi Book Depth & Provenance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Color Key
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.8f))
                    )
                    Text(
                        text = "Buyer Flow",
                        color = Color(0xFF10B981),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color(0xFFF43F5E).copy(alpha = 0.8f))
                    )
                    Text(
                        text = "Seller Flow",
                        color = Color(0xFFF43F5E),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Volume & Order-Flow Metrics
                if (orderFlowSummary.isDataAvailable) {
                    Text(
                        text = "Peak: ${String.format(Locale.US, "%,.2f", orderFlowSummary.maxBarVolume)} BTC",
                        color = Color(0xFFCBD5E1),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    val deltaText = if (orderFlowSummary.orderFlowImbalancePercent >= 0) {
                        "+${String.format(Locale.US, "%.1f", orderFlowSummary.orderFlowImbalancePercent)}% Buy Delta"
                    } else {
                        "${String.format(Locale.US, "%.1f", orderFlowSummary.orderFlowImbalancePercent)}% Sell Delta"
                    }
                    val deltaColor = if (orderFlowSummary.orderFlowImbalancePercent >= 0) Color(0xFF10B981) else Color(0xFFF43F5E)
                    Text(
                        text = "Flow: $deltaText",
                        color = deltaColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        text = orderFlowSummary.statusMessage,
                        color = Color(0xFFF59E0B),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Distinguish authentic Kalshi derivative contract order-flow depth from spot volume
                if (kalshiVerification != null && (kalshiVerification.totalYesDepth > 0 || kalshiVerification.totalNoDepth > 0)) {
                    Text(
                        text = "Kalshi Depth: ${kalshiVerification.totalYesDepth.toInt()}Y / ${kalshiVerification.totalNoDepth.toInt()}N (Contracts)",
                        color = Color(0xFFF59E0B),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Authentic Feed Provenance
                Text(
                    text = "Feed: $sourceExchange",
                    color = Color(0xFF64748B),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
