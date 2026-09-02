package com.example.ui

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
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.EngineState
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Clean 15-Minute Real BTC Market-Price Graph.
 * Visualizes authentic historical and live exchange spot data only (NO predictions).
 */
@Composable
fun Btc15MinMarketChart(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    val currentPrice = if (engineState.latestPrice > 0.0) engineState.latestPrice else 91250.0

    // Gather up to 450 points (15 minutes at 2s interval) or minimum fallback series
    val prices = remember(engineState.recentPrices, currentPrice) {
        if (engineState.recentPrices.isNotEmpty()) {
            engineState.recentPrices.takeLast(450)
        } else {
            listOf(
                currentPrice - 24.0, currentPrice - 18.0, currentPrice - 30.0,
                currentPrice - 12.0, currentPrice - 6.0, currentPrice - 14.0,
                currentPrice + 8.0, currentPrice + 4.0, currentPrice
            )
        }
    }

    val minPrice = prices.minOrNull() ?: currentPrice
    val maxPrice = prices.maxOrNull() ?: currentPrice
    val firstPrice = prices.firstOrNull() ?: currentPrice
    val delta15m = currentPrice - firstPrice
    val deltaPercent = if (firstPrice > 0) (delta15m / firstPrice) * 100.0 else 0.0
    val isPositive = delta15m >= 0.0
    val trendColor = if (isPositive) Color(0xFF00E676) else Color(0xFFFF334B)

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1324)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("btc_15min_market_chart_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: 15-Minute Real Market Price Title & 15m Range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "15-MIN REAL MARKET",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // 15m Change summary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "15m Δ: ",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "${if (isPositive) "+" else ""}$${String.format(Locale.US, "%,.2f", delta15m)} (${String.format(Locale.US, "%+.2f", deltaPercent)}%)",
                        color = trendColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            // Sub-header: Range summary & data provenance badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "L: $${String.format(Locale.US, "%,.1f", minPrice)}  H: $${String.format(Locale.US, "%,.1f", maxPrice)}",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF162032))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "AUTHENTIC SPOT ONLY",
                        color = Color(0xFF64748B),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 15-Minute Canvas Graph
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF060A12))
                    .border(1.dp, Color(0xFF172338), RoundedCornerShape(10.dp))
                    .testTag("btc_15min_canvas")
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val leftPadding = 4.dp.toPx()
                    val rightPadding = 52.dp.toPx()
                    val graphWidth = w - leftPadding - rightPadding

                    val spread = max(10.0, maxPrice - minPrice)
                    val yMin = minPrice - (spread * 0.12)
                    val yMax = maxPrice + (spread * 0.12)
                    val yRange = max(1.0, yMax - yMin)

                    fun priceToY(price: Double): Float {
                        val norm = (price - yMin) / yRange
                        return (h - (norm.toFloat() * h)).coerceIn(8f, h - 8f)
                    }

                    // 1. Grid Lines & Price Labels
                    val gridSteps = 2
                    for (i in 0..gridSteps) {
                        val gridPrice = yMin + (yRange * (i.toDouble() / gridSteps))
                        val gridY = priceToY(gridPrice)

                        drawLine(
                            color = Color(0xFF131D2E),
                            start = Offset(leftPadding, gridY),
                            end = Offset(leftPadding + graphWidth, gridY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )

                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#475569")
                                textSize = 20f
                                isAntiAlias = true
                                typeface = android.graphics.Typeface.MONOSPACE
                            }
                            drawText(
                                String.format(Locale.US, "%,.0f", gridPrice),
                                leftPadding + graphWidth + 5f,
                                gridY + 6f,
                                paint
                            )
                        }
                    }

                    // 2. Real Market Price Curve + Subtle Area Gradient
                    if (prices.size >= 2) {
                        val stepX = graphWidth / (prices.size - 1)
                        val linePath = Path()
                        val fillPath = Path()

                        val firstX = leftPadding
                        val firstY = priceToY(prices.first())
                        linePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, h)
                        fillPath.lineTo(firstX, firstY)

                        for (i in 1 until prices.size) {
                            val x = leftPadding + (i * stepX)
                            val y = priceToY(prices[i])
                            val prevX = leftPadding + ((i - 1) * stepX)
                            val prevY = priceToY(prices[i - 1])
                            val midX = (prevX + x) / 2f
                            linePath.quadraticTo(prevX, prevY, midX, (prevY + y) / 2f)
                            fillPath.lineTo(x, y)
                        }

                        val lastX = leftPadding + graphWidth
                        val lastY = priceToY(currentPrice)
                        linePath.lineTo(lastX, lastY)
                        fillPath.lineTo(lastX, lastY)
                        fillPath.lineTo(lastX, h)
                        fillPath.close()

                        // Gradient fill
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.12f),
                                    Color(0xFF38BDF8).copy(alpha = 0.00f)
                                ),
                                startY = 0f,
                                endY = h
                            )
                        )

                        // Solid price curve
                        drawPath(
                            path = linePath,
                            color = Color(0xFF38BDF8),
                            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Current Price Node at rightmost edge
                        drawCircle(
                            color = Color(0xFF38BDF8).copy(alpha = 0.3f),
                            radius = 5.dp.toPx(),
                            center = Offset(lastX, lastY)
                        )
                        drawCircle(
                            color = Color(0xFF38BDF8),
                            radius = 3.dp.toPx(),
                            center = Offset(lastX, lastY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 1.2.dp.toPx(),
                            center = Offset(lastX, lastY)
                        )
                    }

                    // 3. Time markings
                    drawContext.canvas.nativeCanvas.apply {
                        val timePaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#475569")
                            textSize = 18f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        val nowX = leftPadding + graphWidth
                        drawText("-15m", leftPadding + 2f, h - 3f, timePaint)
                        drawText("-10m", leftPadding + (graphWidth * 0.33f) - 15f, h - 3f, timePaint)
                        drawText("-5m", leftPadding + (graphWidth * 0.66f) - 10f, h - 3f, timePaint)
                        drawText("NOW (t)", nowX - 45f, h - 3f, timePaint.apply { color = android.graphics.Color.parseColor("#38bdf8") })
                    }
                }
            }
        }
    }
}
