package com.example.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Tune
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
import com.example.engine.IndicatorSnapshot
import java.util.Locale
import kotlin.math.tanh

/**
 * ENGINE ROOM TAB
 *
 * Dedicated Quantitative Telemetry Screen housing:
 * 1. Quantitative Math Formulation & Vector Dot Product Blackboard
 * 2. Active Feature Vector Coefficients & Dynamic Activations
 * 3. Real-Time Indicator Matrix Grid
 * 4. Multi-Source Spot Consolidation & Cross-Exchange Divergence Inspector
 * 5. Closed-Loop Empirical Adaptive Calibration
 */
@Composable
fun EngineRoomTab(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Multi-Exchange Connectivity & Live Tick Counters
        item {
            DataConnectionsTable(
                sourceStatuses = engineState.sourceStatuses,
                totalTicks = engineState.totalTicks
            )
        }

        // 2. Multi-Exchange Spot Consolidation & Divergence Inspector
        item {
            SpotConsolidationInspectorCard(engineState = engineState)
        }

        // 3. Quantitative Scalping Formulation Blackboard
        item {
            EngineRoomMathCard(engineState = engineState)
        }

        // 4. Real-Time Indicator Metrics Matrix
        item {
            EngineIndicatorsGridCard(snapshot = engineState.latestSnapshot)
        }

        // 5. Closed-Loop Empirical Learning Calibration (v1 Frozen vs v2 Calibrated)
        item {
            AdaptiveCalibrationCard(engineState = engineState)
        }
    }
}

/**
 * Mathematical Calculation Blackboard with exact vector formulations and decision boundaries.
 */
@Composable
fun EngineRoomMathCard(engineState: EngineState) {
    val snapshot = engineState.latestSnapshot
    val prediction = engineState.latestPrediction
    val currentPrice = if (engineState.latestPrice > 0.0) engineState.latestPrice else 91250.0
    val score = prediction?.score ?: 0.50
    val horizon = prediction?.predictionHorizon ?: 30
    val decision = prediction?.decision ?: "NO-TRADE"

    val phiEma = if (snapshot != null && currentPrice > 0) {
        val diff = snapshot.ema9 - snapshot.ema21
        val pct = (diff / currentPrice) * 1000.0
        (0.5 + (tanh(pct) * 0.5)).coerceIn(0.0, 1.0)
    } else 0.50

    val phiRsi = if (snapshot != null) (snapshot.rsi / 100.0).coerceIn(0.0, 1.0) else 0.50

    val phiMom = if (snapshot != null && currentPrice > 0) {
        val scaled = snapshot.momentum / (currentPrice * 0.0008)
        (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
    } else 0.50

    val phiVel = if (snapshot != null && currentPrice > 0) {
        val scaled = snapshot.velocity / (currentPrice * 0.0003)
        (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
    } else 0.50

    val phiVol = if (snapshot != null && currentPrice > 0) {
        val volPct = (snapshot.volatility / currentPrice) * 100.0
        val volMultiplier = (tanh(volPct * 5.0) * 0.5)
        (0.5 + (phiEma - 0.5) * volMultiplier * 2.0).coerceIn(0.0, 1.0)
    } else 0.50

    val phiBuf = if (snapshot != null && currentPrice > 0) {
        val scaled = snapshot.buffer / (currentPrice * 0.005)
        (0.5 + (tanh(scaled) * 0.5)).coerceIn(0.0, 1.0)
    } else 0.50

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101D)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .testTag("engine_room_mathematics_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Functions,
                        contentDescription = "Math Formulation",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "QUANTITATIVE ENGINE FORMULATION",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Δt = 2.0s • H = ${horizon}s",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Equations Blackboard
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF04070D))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "1. Model:  S(t) = σ ( w⃗ · φ⃗(X_t) ) + δ_agreement",
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val dotProductStr = String.format(
                        Locale.US,
                        "2. Active: S(t) = 0.25(%.2f) + 0.20(%.2f) + 0.20(%.2f) + 0.15(%.2f) + 0.10(%.2f) + 0.05(%.2f)",
                        phiEma, phiRsi, phiMom, phiVel, phiVol, phiBuf
                    )
                    Text(
                        text = dotProductStr,
                        color = Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val evalScoreStr = String.format(
                        Locale.US,
                        "3. Output: S(t) = %.3f  ⇒  P̂(t+%ds) = P_t · [ 1 + (S(t) - 0.50) · 0.0015 ]",
                        score, horizon
                    )
                    Text(
                        text = evalScoreStr,
                        color = Color(0xFFF1F5F9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val decColor = if (decision == "UP") Color(0xFF00E676) else if (decision == "DOWN") Color(0xFFFF334B) else Color(0xFF94A3B8)
                    Text(
                        text = "4. Thresholds: S(t) ≥ 0.65 ⇒ UP  |  S(t) ≤ 0.35 ⇒ DOWN  [ STATUS: $decision ]",
                        color = decColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Factor Vector Matrix Chips
            Text(
                text = "ACTIVE VECTOR COEFFICIENTS & ACTIVATIONS (φ_i)",
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EngineFactorChip("φ_EMA", "25%", String.format(Locale.US, "%.2f", phiEma))
                EngineFactorChip("φ_RSI", "20%", String.format(Locale.US, "%.2f", phiRsi))
                EngineFactorChip("φ_MOM", "20%", String.format(Locale.US, "%.2f", phiMom))
                EngineFactorChip("φ_VEL", "15%", String.format(Locale.US, "%.2f", phiVel))
                EngineFactorChip("φ_VOL", "10%", String.format(Locale.US, "%.2f", phiVol))
                EngineFactorChip("φ_BUF", "5%", String.format(Locale.US, "%.2f", phiBuf))
            }
        }
    }
}

@Composable
fun EngineFactorChip(name: String, weight: String, activation: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF131D31))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(text = name, color = Color(0xFF38BDF8), fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(text = weight, color = Color(0xFF64748B), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(text = activation, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Indicator Matrix Grid.
 */
@Composable
fun EngineIndicatorsGridCard(snapshot: IndicatorSnapshot?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("engine_indicators_grid_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Indicators",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REAL-TIME INDICATOR MATRIX",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "Agreement: ${snapshot?.exchangeAgreement ?: "STRONG"}",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                IndicatorMetricCell("EMA 9", String.format(Locale.US, "%.1f", snapshot?.ema9 ?: 0.0), Modifier.weight(1f))
                IndicatorMetricCell("EMA 21", String.format(Locale.US, "%.1f", snapshot?.ema21 ?: 0.0), Modifier.weight(1f))
                IndicatorMetricCell("RSI (14)", String.format(Locale.US, "%.1f", snapshot?.rsi ?: 50.0), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                IndicatorMetricCell("Momentum (5)", String.format(Locale.US, "%+.1f", snapshot?.momentum ?: 0.0), Modifier.weight(1f))
                IndicatorMetricCell("Velocity (dP/dt)", String.format(Locale.US, "%+.2f/s", snapshot?.velocity ?: 0.0), Modifier.weight(1f))
                IndicatorMetricCell("Acceleration (d²P/dt²)", String.format(Locale.US, "%+.2f/s²", snapshot?.acceleration ?: 0.0), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                IndicatorMetricCell("Volatility (10)", String.format(Locale.US, "%.2f", snapshot?.volatility ?: 0.0), Modifier.weight(1f))
                IndicatorMetricCell("Vol Surge", String.format(Locale.US, "%.2fx", snapshot?.volumeChange ?: 1.0), Modifier.weight(1f))
                IndicatorMetricCell("Buffer (P-Pref)", String.format(Locale.US, "%+.1f", snapshot?.buffer ?: 0.0), Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun IndicatorMetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF090E17))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Multi-Exchange Consolidation & Divergence Inspector Card.
 */
@Composable
fun SpotConsolidationInspectorCard(engineState: EngineState) {
    val state = engineState.consolidatedMarketState

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("spot_consolidation_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = "Consolidation",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MULTI-SOURCE SPOT CONSOLIDATION",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }

                val divColor = if ((state?.divergencePercent ?: 0.0) <= 0.08) Color(0xFF00E676) else Color(0xFFFF9100)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(divColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Δ = ${String.format(Locale.US, "%.3f%%", state?.divergencePercent ?: 0.0)}",
                        color = divColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mathematical Consolidation Formula Display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF070B13))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = state?.consolidationFormula ?: "P_cons = ∑(w_i·l_i·P_i) / ∑(w_i·l_i)",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Source Provenance List
            if (state != null && state.sourceProvenance.isNotEmpty()) {
                state.sourceProvenance.forEach { (exchange, pt) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = exchange,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "$${String.format(Locale.US, "%,.2f", pt.price)} (Vol: ${String.format(Locale.US, "%.2f", pt.volume)})",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                Text(
                    text = "Awaiting cross-exchange spot telemetry...",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Adaptive Calibration Card.
 */
@Composable
fun AdaptiveCalibrationCard(engineState: EngineState) {
    val stats = engineState.performanceStats
    val latestPred = engineState.latestPrediction

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("adaptive_calibration_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Adaptive Learning",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ADAPTIVE CALIBRATION (v1 FROZEN vs v2)",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF00E676).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "v1 BASELINE FROZEN",
                        color = Color(0xFF00E676),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Score Comparison: v1 Frozen Score vs v2 Advisory Score
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF090E17))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "v1 Live Score: ${latestPred?.score ?: 0.50}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v2 Calibrated Score: ${latestPred?.calibratedScore ?: 0.50}",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (stats.factorAttributions.isEmpty()) {
                Text(
                    text = "Calibrating empirical factor offsets with live feed...",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                stats.factorAttributions.forEach { factor ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = factor.factorName,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Win: ${factor.winRate}% (n=${factor.totalTimesActive})",
                            color = if (factor.winRate >= 60.0) Color(0xFF00E676) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        val offsetStr = String.format(Locale.US, "%+.2f", factor.suggestedWeightOffset)
                        Text(
                            text = "v2 Δw: $offsetStr",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
