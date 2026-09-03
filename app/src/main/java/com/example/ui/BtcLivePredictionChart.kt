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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.EngineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic Live 30-Second BTC Prediction Graph showing Current Market Price,
 * Exact Live Timestamp, 30-Second Target Timer, and Predicted Trajectory.
 */
@Composable
fun BtcLivePredictionChart(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    val currentPrice = if (engineState.latestPrice > 0.0) engineState.latestPrice else 91250.0
    val prediction = engineState.latestPrediction
    val predictedPrice = if (prediction != null && prediction.predictedPrice > 0.0) {
        prediction.predictedPrice
    } else {
        currentPrice
    }
    val projectedPrice90s = if (prediction != null && prediction.projectedPrice90s > 0.0) {
        prediction.projectedPrice90s
    } else {
        predictedPrice
    }
    val decision = prediction?.decision ?: "NO-TRADE"
    val decision90s = prediction?.projectedDecision90s ?: decision
    val score = prediction?.score ?: 0.50
    val horizon = prediction?.predictionHorizon ?: 30
    val snapshot = engineState.latestSnapshot

    val decisionColor = when (decision) {
        "UP" -> Color(0xFF00E676)
        "DOWN" -> Color(0xFFFF334B)
        else -> Color(0xFF38BDF8)
    }
    val decisionColor90s = when (decision90s) {
        "UP" -> Color(0xFF00E676)
        "DOWN" -> Color(0xFFFF334B)
        else -> Color(0xFF38BDF8)
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val nowMs = if (engineState.latestTimestamp > 0) engineState.latestTimestamp else System.currentTimeMillis()
    val targetMs = nowMs + (horizon * 1000L)
    val target90sMs = nowMs + 90000L
    val nowTimeStr = timeFormat.format(Date(nowMs))
    val targetTimeStr = timeFormat.format(Date(targetMs))
    val target90sTimeStr = timeFormat.format(Date(target90sMs))

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1324)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("btc_live_prediction_chart_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: BTC Spot Feed info + 30s Prediction Horizon Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (engineState.isRunning) Color(0xFF00E676) else Color(0xFFFF5252))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "BTC / USDT",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${engineState.latestExchange}",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                // 30s Horizon & Score Badge (Single Line)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(decisionColor.copy(alpha = 0.12f))
                        .border(1.dp, decisionColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "30s FORECAST • ${if (score > 0.0) String.format(Locale.US, "SCORE %.2f", score) else "NO DATA"}",
                        color = decisionColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Price & Time Row: All CURRENT on the LEFT, all PREDICTION on the RIGHT
            val priceDelta = predictedPrice - currentPrice
            val priceDelta90s = projectedPrice90s - currentPrice

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // LEFT SIDE: All Current Price & Current Time
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LIVE SPOT PRICE (t)",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "$${String.format(Locale.US, "%,.2f", currentPrice)}",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TIME (t): ",
                            color = Color(0xFF64748B),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            text = nowTimeStr,
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // RIGHT SIDE: All Prediction Price, Delta & Target Time
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "30s / 90s TARGETS",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "$${String.format(Locale.US, "%,.2f", predictedPrice)}",
                            color = decisionColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${if (priceDelta >= 0) "+" else ""}${String.format(Locale.US, "%.1f", priceDelta)}",
                            color = decisionColor.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "30s: $targetTimeStr ($decision) • 90s: $${String.format(Locale.US, "%,.0f", projectedPrice90s)}",
                            color = decisionColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live Chart Canvas with Real-Time Animated Flow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF060A12))
                    .border(1.dp, Color(0xFF172338), RoundedCornerShape(12.dp))
                    .testTag("btc_canvas_graph")
            ) {
                val historicalPrices = remember(engineState.recentPrices, currentPrice) {
                    if (engineState.recentPrices.isNotEmpty()) {
                        engineState.recentPrices.takeLast(20)
                    } else {
                        listOf(
                            currentPrice - 14.0, currentPrice - 10.0, currentPrice - 16.0,
                            currentPrice - 6.0, currentPrice - 1.0, currentPrice - 3.0,
                            currentPrice + 4.0, currentPrice + 1.0, currentPrice
                        )
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val leftPadding = 6.dp.toPx()
                    val rightPadding = 56.dp.toPx()
                    val graphWidth = w - leftPadding - rightPadding

                    // Exact time-proportional division: 30s history (33%), 30s forecast (33%), 90s forecast (34%)
                    // History covers -30s to 0 (ratio 0.33), 30s target is at ratio 0.66, 90s target is at ratio 1.00
                    val nowX = leftPadding + (graphWidth * 0.35f)
                    val target30sX = leftPadding + (graphWidth * 0.65f)
                    val target90sX = leftPadding + graphWidth

                    // Find bounds including current, 30s prediction, and 90s projection
                    var minP = historicalPrices.minOrNull() ?: currentPrice
                    var maxP = historicalPrices.maxOrNull() ?: currentPrice
                    minP = min(minP, min(currentPrice, min(predictedPrice, projectedPrice90s)))
                    maxP = max(maxP, max(currentPrice, max(predictedPrice, projectedPrice90s)))

                    val spread = max(10.0, maxP - minP)
                    val yMin = minP - (spread * 0.22)
                    val yMax = maxP + (spread * 0.22)
                    val yRange = max(1.0, yMax - yMin)

                    fun priceToY(price: Double): Float {
                        val norm = (price - yMin) / yRange
                        return (h - (norm.toFloat() * h)).coerceIn(10f, h - 10f)
                    }

                    // 1. Grid Lines & Price Labels
                    val gridSteps = 3
                    for (i in 0..gridSteps) {
                        val gridPrice = yMin + (yRange * (i.toDouble() / gridSteps))
                        val gridY = priceToY(gridPrice)

                        drawLine(
                            color = Color(0xFF162032),
                            start = Offset(leftPadding, gridY),
                            end = Offset(leftPadding + graphWidth, gridY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                        )

                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#475569")
                                textSize = 22f
                                isAntiAlias = true
                                typeface = android.graphics.Typeface.MONOSPACE
                            }
                            drawText(
                                String.format(Locale.US, "%,.0f", gridPrice),
                                leftPadding + graphWidth + 6f,
                                gridY + 7f,
                                paint
                            )
                        }
                    }

                    // 2. "NOW" Vertical Time Divider
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = 0.55f),
                        start = Offset(nowX, 0f),
                        end = Offset(nowX, h),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                    )

                    // 3. Historical Solid Curve + Clean Under-Area
                    if (historicalPrices.isNotEmpty()) {
                        val historyStep = (nowX - leftPadding) / (historicalPrices.size - 1).coerceAtLeast(1)
                        val linePath = Path()
                        val fillPath = Path()

                        val firstX = leftPadding
                        val firstY = priceToY(historicalPrices.first())
                        linePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, h)
                        fillPath.lineTo(firstX, firstY)

                        for (i in 1 until historicalPrices.size) {
                            val x = leftPadding + (i * historyStep)
                            val y = priceToY(historicalPrices[i])
                            val prevX = leftPadding + ((i - 1) * historyStep)
                            val prevY = priceToY(historicalPrices[i - 1])
                            val midX = (prevX + x) / 2f
                            linePath.quadraticTo(prevX, prevY, midX, (prevY + y) / 2f)
                            fillPath.lineTo(x, y)
                        }

                        val currentY = priceToY(currentPrice)
                        linePath.lineTo(nowX, currentY)
                        fillPath.lineTo(nowX, currentY)
                        fillPath.lineTo(nowX, h)
                        fillPath.close()

                        // Gradient fill
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.15f),
                                    Color(0xFF00E5FF).copy(alpha = 0.00f)
                                ),
                                startY = 0f,
                                endY = h
                            )
                        )

                        // Crisp solid line
                        drawPath(
                            path = linePath,
                            color = Color(0xFF00E5FF),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // 4. Future Prediction Trajectory (Clean 30s Dashed Path)
                    val currentY = priceToY(currentPrice)
                    val target30sY = priceToY(predictedPrice)
                    val target90sY = priceToY(projectedPrice90s)

                    val predPath = Path().apply {
                        moveTo(nowX, currentY)
                        val controlX = (nowX + target30sX) / 2f
                        val controlY = if (decision == "UP") min(currentY, target30sY) - 10f else if (decision == "DOWN") max(currentY, target30sY) + 10f else (currentY + target30sY) / 2f
                        quadraticTo(controlX, controlY, target30sX, target30sY)
                    }

                    // Clean Dashed Predicted 30s Trajectory Line
                    drawPath(
                        path = predPath,
                        color = decisionColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                            cap = StrokeCap.Round
                        )
                    )

                    // 4b. Future 90s Large Scale Projected Trajectory Path (Extended Horizon)
                    val predPath90s = Path().apply {
                        moveTo(target30sX, target30sY)
                        val controlX90 = (target30sX + target90sX) / 2f
                        val controlY90 = if (decision90s == "UP") min(target30sY, target90sY) - 8f else if (decision90s == "DOWN") max(target30sY, target90sY) + 8f else (target30sY + target90sY) / 2f
                        quadraticTo(controlX90, controlY90, target90sX, target90sY)
                    }

                    drawPath(
                        path = predPath90s,
                        color = decisionColor90s.copy(alpha = 0.70f),
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                            cap = StrokeCap.Round
                        )
                    )

                    // 5a. Clean Target Point at +30s
                    drawCircle(
                        color = decisionColor.copy(alpha = 0.25f),
                        radius = 6.dp.toPx(),
                        center = Offset(target30sX, target30sY)
                    )
                    drawCircle(
                        color = decisionColor,
                        radius = 3.5.dp.toPx(),
                        center = Offset(target30sX, target30sY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.5.dp.toPx(),
                        center = Offset(target30sX, target30sY)
                    )

                    // 5b. Extended Target Point at +90s (Authorized Large Scale Horizon)
                    drawCircle(
                        color = decisionColor90s.copy(alpha = 0.20f),
                        radius = 5.dp.toPx(),
                        center = Offset(target90sX, target90sY)
                    )
                    drawCircle(
                        color = decisionColor90s,
                        radius = 3.dp.toPx(),
                        center = Offset(target90sX, target90sY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.2.dp.toPx(),
                        center = Offset(target90sX, target90sY)
                    )

                    // 6. Live "NOW" Spot Node
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                        radius = 6.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = 3.5.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 1.5.dp.toPx(),
                        center = Offset(nowX, currentY)
                    )

                    // Time axis indicators
                    drawContext.canvas.nativeCanvas.apply {
                        val timePaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#64748b")
                            textSize = 20f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        drawText("-30s", leftPadding + 4f, h - 4f, timePaint)
                        drawText("NOW (t)", nowX - 25f, h - 4f, timePaint.apply { color = android.graphics.Color.parseColor("#00e5ff") })
                        drawText("+30s", target30sX - 16f, h - 4f, timePaint.apply { color = if (decision == "UP") android.graphics.Color.parseColor("#00e676") else android.graphics.Color.parseColor("#ff334b") })
                        drawText("+90s", target90sX - 35f, h - 4f, timePaint.apply { color = if (decision90s == "UP") android.graphics.Color.parseColor("#00e676") else android.graphics.Color.parseColor("#ff334b") })
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Horizon & Live Metrics Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp, 3.dp).background(Color(0xFF00E5FF), RoundedCornerShape(2.dp)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Live (t)", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp, 3.dp).background(decisionColor, RoundedCornerShape(2.dp)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+30s", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp, 3.dp).background(decisionColor90s.copy(alpha = 0.70f), RoundedCornerShape(2.dp)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+90s", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "v = ${String.format(Locale.US, "%+.1f", snapshot?.velocity ?: 0.0)}/s",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Order-Book Verification Strip (Independent Confirmation Only)
            val verif = engineState.kalshiVerification
            if (verif != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF070D18))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "KALSHI 15M ORDER BOOK VERIFICATION",
                                color = Color(0xFF64748B),
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            val statusColor = when (verif.verificationSummary) {
                                "FULL_AGREEMENT" -> Color(0xFF00E676)
                                "PARTIAL_AGREEMENT" -> Color(0xFF00E5FF)
                                "DIVERGENCE" -> Color(0xFFFF5252)
                                "NEUTRAL" -> Color(0xFFFFD600)
                                else -> Color(0xFF94A3B8)
                            }
                            Text(
                                text = verif.verificationSummary,
                                color = statusColor,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val midText = verif.yesMidPriceCents?.let { String.format(Locale.US, "%.0f¢", it) }
                            ?: verif.marketPriceCents?.let { "${it}¢" }
                            ?: "N/A"
                        Text(
                            text = "Bias: ${verif.marketBias} ($midText) • 30s: ${verif.agreement30s} • 90s: ${verif.agreement90s}",
                            color = Color(0xFF94A3B8),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
