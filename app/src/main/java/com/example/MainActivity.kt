package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.BacktestResult
import com.example.engine.EngineState
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.ui.BtcLivePredictionChart
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.tanh

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                val uiState by viewModel.uiState.collectAsState()
                QtYAppScreen(
                    uiState = uiState,
                    onToggleEngine = { viewModel.toggleEngineLoop() },
                    onSingleCycle = { viewModel.triggerSingleCycle() },
                    onRunBacktest = { viewModel.runBacktestReplay(150) },
                    onTabSelected = { viewModel.setActiveTab(it) },
                    onCopyJson = {
                        val json = viewModel.getExportedJson(10)
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("QtY Predictions", json))
                        Toast.makeText(this, "JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun QtYAppScreen(
    uiState: MainUiState,
    onToggleEngine: () -> Unit,
    onSingleCycle: () -> Unit,
    onRunBacktest: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onCopyJson: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF070B12)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header Bar
            HeaderBar(
                engineState = uiState.engineState,
                onToggle = onToggleEngine,
                onRefresh = onSingleCycle
            )

            // 2 Primary Tabs: LIVE PREDICTION & BACKTEST / LOGS
            TabRow(
                selectedTabIndex = uiState.activeTab.coerceIn(0, 1),
                containerColor = Color(0xFF0F172A),
                contentColor = Color(0xFF00E5FF),
                indicator = { tabPositions ->
                    if (uiState.activeTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab.coerceIn(0, 1)]),
                            color = Color(0xFF00E5FF),
                            height = 3.dp
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.activeTab == 0,
                    onClick = { onTabSelected(0) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (uiState.activeTab == 0) Color(0xFF00E5FF) else Color(0xFF64748B))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LIVE ENGINE & GRAPH", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                        }
                    },
                    modifier = Modifier.testTag("tab_live_engine")
                )
                Tab(
                    selected = uiState.activeTab == 1 || uiState.activeTab == 2,
                    onClick = { onTabSelected(1) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoGraph, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (uiState.activeTab == 1 || uiState.activeTab == 2) Color(0xFF00E5FF) else Color(0xFF64748B))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("BACKTEST & LOGS", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
                        }
                    },
                    modifier = Modifier.testTag("tab_backtest")
                )
            }

            // Main Tab Body
            when (uiState.activeTab) {
                0 -> LiveEngineTab(engineState = uiState.engineState)
                else -> BacktestAndLogsTab(
                    uiState = uiState,
                    onRunBacktest = onRunBacktest,
                    onCopyJson = onCopyJson
                )
            }
        }
    }
}

@Composable
fun HeaderBar(
    engineState: EngineState,
    onToggle: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        color = Color(0xFF0F172A),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (engineState.isRunning) Color(0xFF00E676) else Color(0xFFFF5252))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "QtY",
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF00E5FF),
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = " // QUANT TELEMETRY",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "Real Data Engine • Cycle #${engineState.cycleCount}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_cycle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Trigger Cycle",
                        tint = Color(0xFF00E5FF)
                    )
                }
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.testTag("toggle_engine_button")
                ) {
                    Icon(
                        imageVector = if (engineState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (engineState.isRunning) "Pause Engine" else "Start Engine",
                        tint = if (engineState.isRunning) Color(0xFFFF5252) else Color(0xFF00E676)
                    )
                }
            }
        }
    }
}

/**
 * Tab 0: Live Engine with Clean Dynamic BTC Live Graph and Mathematical Breakdown Box Directly Underneath.
 */
@Composable
fun LiveEngineTab(engineState: EngineState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Dynamic Live BTC Graph (Where BTC is now & where QtY predicts it will move over next ~30s)
        item {
            BtcLivePredictionChart(engineState = engineState)
        }

        // 2. Mathematical Calculation Box (Directly Underneath the Graph)
        item {
            MathBoxCard(engineState = engineState)
        }

        // 3. Primary Directional Scalp Prediction Card
        item {
            PredictionCard(engineState = engineState)
        }

        // 4. Real-Time Indicator Matrix Grid
        item {
            IndicatorsGridCard(snapshot = engineState.latestSnapshot)
        }

        // 5. Recent Predictions History Preview
        item {
            RecentPredictionsCard(predictions = engineState.recentPredictions)
        }
    }
}

/**
 * Mathematical Calculation Box showing Quantitative Formulation,
 * Vector Dot Product, Activation Functions, and Decision Boundaries.
 */
@Composable
fun MathBoxCard(engineState: EngineState) {
    val snapshot = engineState.latestSnapshot
    val prediction = engineState.latestPrediction
    val currentPrice = if (engineState.latestPrice > 0.0) engineState.latestPrice else 91250.0
    val score = prediction?.score ?: 0.50
    val horizon = prediction?.predictionHorizon ?: 30
    val decision = prediction?.decision ?: "NO-TRADE"

    // Compute actual mathematical factor activations phi_i
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
            .testTag("mathematics_box")
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
                        text = "QUANTITATIVE SCALPING FORMULATION",
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

            // Mathematical Equations Blackboard
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF04070D))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Line 1: Vector Model
                    Text(
                        text = "1. Model:  S(t) = σ ( w⃗ · φ⃗(X_t) ) + δ_agreement",
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Line 2: Active Numeric Vector Dot Product
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

                    // Line 3: Evaluated Score & Forecast Equation
                    val evalScoreStr = String.format(
                        Locale.US,
                        "3. Output: S(t) = %.3f  ⇒  P̂(t+%ds) = P_t · [ 1 + (S(t) - 0.50) · 0.00075 ]",
                        score, horizon
                    )
                    Text(
                        text = evalScoreStr,
                        color = Color(0xFFF1F5F9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Line 4: Decision Hypothesis
                    val decColor = if (decision == "UP") "#00E676" else if (decision == "DOWN") "#FF334B" else "#94A3B8"
                    Text(
                        text = "4. Decision: if S(t) ≥ 0.65 ⇒ UP  |  if S(t) ≤ 0.35 ⇒ DOWN  [ CURRENT: $decision ]",
                        color = Color(if (decision == "UP") 0xFF00E676 else if (decision == "DOWN") 0xFFFF334B else 0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                MathFactorChip("φ_EMA", "25%", String.format(Locale.US, "%.2f", phiEma))
                MathFactorChip("φ_RSI", "20%", String.format(Locale.US, "%.2f", phiRsi))
                MathFactorChip("φ_MOM", "20%", String.format(Locale.US, "%.2f", phiMom))
                MathFactorChip("φ_VEL", "15%", String.format(Locale.US, "%.2f", phiVel))
                MathFactorChip("φ_VOL", "10%", String.format(Locale.US, "%.2f", phiVol))
                MathFactorChip("φ_BUF", "5%", String.format(Locale.US, "%.2f", phiBuf))
            }
        }
    }
}

@Composable
fun MathFactorChip(name: String, weight: String, activation: String) {
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

@Composable
fun PredictionCard(engineState: EngineState) {
    val pred = engineState.latestPrediction
    val decision = pred?.decision ?: "NO-TRADE"
    val score = pred?.score ?: 0.50
    val horizon = pred?.predictionHorizon ?: 30
    val decisionColor = when (decision) {
        "UP" -> Color(0xFF00E676)
        "DOWN" -> Color(0xFFFF334B)
        else -> Color(0xFF94A3B8)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("prediction_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEXT ${horizon}s SCALP SIGNAL",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "HORIZON: ${horizon}s",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(decisionColor.copy(alpha = 0.15f))
                            .border(1.dp, decisionColor.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (decision == "UP") Icons.Default.ArrowUpward else if (decision == "DOWN") Icons.Default.ArrowDownward else Icons.Default.PlayArrow,
                            contentDescription = decision,
                            tint = decisionColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = decision,
                            color = decisionColor,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "STRENGTH: ${pred?.strength ?: "NEUTRAL"}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${Math.round(score * 100.0)}% SCORE",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    if (pred != null && pred.predictedPrice > 0.0) {
                        Text(
                            text = "Target: $${String.format(Locale.US, "%,.2f", pred.predictedPrice)}",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Score Bar
            LinearProgressIndicator(
                progress = { score.toFloat() },
                color = decisionColor,
                trackColor = Color(0xFF1E293B),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
fun IndicatorsGridCard(snapshot: IndicatorSnapshot?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("indicators_grid_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "REAL-TIME INDICATOR METRICS",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                IndicatorCell("EMA 9", String.format(Locale.US, "%.1f", snapshot?.ema9 ?: 0.0), Modifier.weight(1f))
                IndicatorCell("EMA 21", String.format(Locale.US, "%.1f", snapshot?.ema21 ?: 0.0), Modifier.weight(1f))
                IndicatorCell("RSI (14)", String.format(Locale.US, "%.1f", snapshot?.rsi ?: 50.0), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                IndicatorCell("Momentum (5)", String.format(Locale.US, "%+.1f", snapshot?.momentum ?: 0.0), Modifier.weight(1f))
                IndicatorCell("Velocity", String.format(Locale.US, "%+.2f/s", snapshot?.velocity ?: 0.0), Modifier.weight(1f))
                IndicatorCell("Acceleration", String.format(Locale.US, "%+.2f/s²", snapshot?.acceleration ?: 0.0), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                IndicatorCell("Volatility (10)", String.format(Locale.US, "%.2f", snapshot?.volatility ?: 0.0), Modifier.weight(1f))
                IndicatorCell("Vol Surge", String.format(Locale.US, "%.2fx", snapshot?.volumeChange ?: 1.0), Modifier.weight(1f))
                IndicatorCell("Buffer", String.format(Locale.US, "%+.1f", snapshot?.buffer ?: 0.0), Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun IndicatorCell(label: String, value: String, modifier: Modifier = Modifier) {
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

@Composable
fun RecentPredictionsCard(predictions: List<PredictionRecord>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("recent_predictions_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "RECENT PREDICTIONS HISTORY (${predictions.size})",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (predictions.isEmpty()) {
                Text(
                    text = "Awaiting initial engine cycles...",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                predictions.takeLast(5).reversed().forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(rec.timestamp))
                        Text(text = timeStr, color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(text = "$${String.format(Locale.US, "%,.1f", rec.currentPrice)}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                        val decColor = when (rec.decision) {
                            "UP" -> Color(0xFF00E676)
                            "DOWN" -> Color(0xFFFF334B)
                            else -> Color(0xFF94A3B8)
                        }
                        Text(text = rec.decision, color = decColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(text = "${(rec.score * 100).toInt()}%", color = Color(0xFF38BDF8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/**
 * Tab 1: CCXT Backtest Evaluation & Structured JSON Logs
 */
@Composable
fun BacktestAndLogsTab(
    uiState: MainUiState,
    onRunBacktest: () -> Unit,
    onCopyJson: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Button(
                onClick = onRunBacktest,
                enabled = !uiState.isBacktesting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_backtest_button")
            ) {
                if (uiState.isBacktesting) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Running CCXT Replay...", color = Color.Black, fontWeight = FontWeight.Bold)
                } else {
                    Text("RUN 150-CYCLE CCXT BACKTEST", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (uiState.backtestResult != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("backtest_metrics_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "BACKTEST EVALUATION METRICS (30s HORIZON)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "DIRECTIONAL WIN RATE",
                                    color = Color(0xFF64748B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${uiState.backtestResult.winRatePercent}%",
                                    color = if (uiState.backtestResult.winRatePercent >= 60.0) Color(0xFF00E676) else Color(0xFFFFD600),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = "Total Samples: ${uiState.backtestResult.totalSamples}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Total Trades: ${uiState.backtestResult.totalTrades}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Correct: ${uiState.backtestResult.correctPredictions}", color = Color(0xFF00E676), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Incorrect: ${uiState.backtestResult.incorrectPredictions}", color = Color(0xFFFF334B), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "SAMPLE BACKTEST PREDICTIONS (LAST 10)",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            items(uiState.backtestResult.samplePredictions) { sample ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF090E17)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Entry: $${String.format(Locale.US, "%,.1f", sample.currentPrice)} -> Pred: $${String.format(Locale.US, "%,.1f", sample.predictedPrice)}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(text = "Actual 30s: $${String.format(Locale.US, "%,.1f", sample.actualPrice ?: 0.0)}", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        val resColor = when (sample.result) {
                            "CORRECT" -> Color(0xFF00E676)
                            "INCORRECT" -> Color(0xFFFF334B)
                            else -> Color(0xFF64748B)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = sample.decision, color = if (sample.decision == "UP") Color(0xFF00E676) else Color(0xFFFF334B), fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Text(text = sample.result ?: "PENDING", color = resColor, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Structured JSON Logs Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STRUCTURED JSON LOG FEED",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        OutlinedButton(
                            onClick = onCopyJson,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("copy_json_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("COPY JSON", color = Color(0xFF00E5FF), fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF060911))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        LazyColumn {
                            items(uiState.engineState.recentPredictions.takeLast(10).reversed()) { rec ->
                                Text(
                                    text = "{\n  \"id\": \"${rec.predictionId.take(8)}...\",\n  \"time\": ${rec.timestamp},\n  \"price\": ${rec.currentPrice},\n  \"decision\": \"${rec.decision}\",\n  \"score\": ${rec.score},\n  \"strength\": \"${rec.strength}\",\n  \"predictedPrice\": ${rec.predictedPrice},\n  \"formula\": \"${rec.inputs.formulaDisplay}\"\n},",
                                    color = Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
