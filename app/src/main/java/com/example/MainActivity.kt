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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Memory
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
import com.example.engine.LivePerformanceStats
import com.example.engine.PredictionRecord
import com.example.ui.Btc15MinMarketChart
import com.example.ui.BtcLivePredictionChart
import com.example.ui.DataConnectionsTable
import com.example.ui.EngineRoomTab
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

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
                    onToggleAutomation = { viewModel.toggleAutomation() },
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
    onToggleAutomation: () -> Unit = {},
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
                onRefresh = onSingleCycle,
                onToggleAutomation = onToggleAutomation
            )

            // 3-Part Navigation Structure:
            // 0: PREDICTION (MAIN) | 1: ENGINE ROOM | 2: BACKTEST & AUDIT
            TabRow(
                selectedTabIndex = uiState.activeTab.coerceIn(0, 2),
                containerColor = Color(0xFF0F172A),
                contentColor = Color(0xFF00E5FF),
                indicator = { tabPositions ->
                    val curTab = uiState.activeTab.coerceIn(0, 2)
                    if (curTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[curTab]),
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
                            Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (uiState.activeTab == 0) Color(0xFF00E5FF) else Color(0xFF64748B))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("MAIN", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
                        }
                    },
                    modifier = Modifier.testTag("tab_main_prediction")
                )
                Tab(
                    selected = uiState.activeTab == 1,
                    onClick = { onTabSelected(1) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (uiState.activeTab == 1) Color(0xFF00E5FF) else Color(0xFF64748B))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ENGINE ROOM", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
                        }
                    },
                    modifier = Modifier.testTag("tab_engine_room")
                )
                Tab(
                    selected = uiState.activeTab == 2,
                    onClick = { onTabSelected(2) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoGraph, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (uiState.activeTab == 2) Color(0xFF00E5FF) else Color(0xFF64748B))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("BACKTEST", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)
                        }
                    },
                    modifier = Modifier.testTag("tab_backtest_audit")
                )
            }

            // Tab Body
            when (uiState.activeTab) {
                0 -> MainPredictionTab(engineState = uiState.engineState)
                1 -> EngineRoomTab(engineState = uiState.engineState)
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
    onRefresh: () -> Unit,
    onToggleAutomation: () -> Unit = {}
) {
    Surface(
        color = Color(0xFF0F172A),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        text = "2s Engine • Cycle #${engineState.cycleCount}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // The single Automation ON/OFF control
                Button(
                    onClick = onToggleAutomation,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (engineState.isAutomationEnabled) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF1E293B),
                        contentColor = if (engineState.isAutomationEnabled) Color(0xFF00E676) else Color(0xFF94A3B8)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .border(
                            1.dp,
                            if (engineState.isAutomationEnabled) Color(0xFF00E676).copy(alpha = 0.6f) else Color(0xFF334155),
                            RoundedCornerShape(6.dp)
                        )
                        .testTag("automation_toggle_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (engineState.isAutomationEnabled) Color(0xFF00E676) else Color(0xFF64748B))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (engineState.isAutomationEnabled) "AUTO ON" else "AUTO OFF",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

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
 * Tab 0: MAIN / PREDICTION
 * Centered around:
 * 1. 30s Scalp Prediction Banner & Target
 * 2. 15-Minute Real BTC Market-Price Graph (Historical/Live Spot only - NO predictions)
 * 3. 30-Second Dynamic BTC Prediction Graph (Live Spot Price, Exact Time, 30s Target & Timer)
 * 4. Connected Spot APIs Table (Real validated multi-exchange feeds)
 * 5. Real Measured Live Scalp Accuracy Card
 */
@Composable
fun MainPredictionTab(engineState: EngineState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Clean 15-Minute Real BTC Market-Price Graph (Historical & Live Real Data Only)
        item {
            Btc15MinMarketChart(engineState = engineState)
        }

        // 2. Focused Live 30-Second BTC Prediction Graph (Current Spot & Time on Left, Projected Price & Timer on Right)
        item {
            BtcLivePredictionChart(engineState = engineState)
        }

        // 3. Measured Live Scalp Performance & Statistical Evaluation Card
        item {
            LivePerformanceCard(stats = engineState.performanceStats)
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("prediction_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "30s PREDICTION",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(decisionColor.copy(alpha = 0.15f))
                        .border(1.dp, decisionColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "30s HORIZON",
                        color = decisionColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(decisionColor.copy(alpha = 0.15f))
                            .border(1.dp, decisionColor.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (decision == "UP") Icons.Default.ArrowUpward else if (decision == "DOWN") Icons.Default.ArrowDownward else Icons.Default.PlayArrow,
                            contentDescription = decision,
                            tint = decisionColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = decision,
                            color = decisionColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "STRENGTH: ${pred?.strength ?: "NEUTRAL"}",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SCORE: ${String.format(Locale.US, "%.2f", score)}",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    if (pred != null && pred.predictedPrice > 0.0) {
                        Text(
                            text = "Target: $${String.format(Locale.US, "%,.2f", pred.predictedPrice)}",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LivePerformanceCard(stats: LivePerformanceStats) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .testTag("live_performance_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header with Market Regime
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = "Performance",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LIVE EVALUATION METRICS",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }

                val regimeColor = when (stats.marketRegime) {
                    "HIGH VOLATILITY" -> Color(0xFFFF9100)
                    "TRENDING BULL" -> Color(0xFF00E676)
                    "TRENDING BEAR" -> Color(0xFFFF334B)
                    else -> Color(0xFF38BDF8)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(regimeColor.copy(alpha = 0.15f))
                        .border(1.dp, regimeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stats.marketRegime,
                        color = regimeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dual Stream Win Rate & Stat Matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Operational Stream (Continuous 2s Stream)
                Column {
                    Text(
                        text = "OPERATIONAL (2s STREAM)",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    if (stats.operationalResolvedCount > 0) {
                        val wrColor = if (stats.operationalWinRatePercent >= 75.0) Color(0xFF00E676) else if (stats.operationalWinRatePercent >= 50.0) Color(0xFF38BDF8) else Color(0xFFFFD600)
                        Text(
                            text = "${stats.operationalWinRatePercent}%",
                            color = wrColor,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        Text(
                            text = "AWAITING...",
                            color = Color(0xFF94A3B8),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Resolved: ${stats.operationalResolvedCount} / ${stats.operationalPredictionCount}",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Statistical Non-Overlapping Stream (T, T+30s, ...)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "STATISTICAL (30s STREAM)",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    if (stats.statisticalEvaluationCount > 0) {
                        val statColor = if (stats.statisticalWinRatePercent >= 75.0) Color(0xFF00E676) else if (stats.statisticalWinRatePercent >= 50.0) Color(0xFF38BDF8) else Color(0xFFFFD600)
                        Text(
                            text = "${stats.statisticalWinRatePercent}%",
                            color = statColor,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        Text(
                            text = "AWAITING...",
                            color = Color(0xFF94A3B8),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Samples: ${stats.statisticalEvaluationCount} (Non-Overlap)",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Baselines Benchmarking Bar (Using identical non-overlapping samples)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF090E17))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACTIVE TRADE BASELINE:",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "UP: ${stats.baselineAlwaysUpWinRate}%",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "DOWN: ${stats.baselineAlwaysDownWinRate}%",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GLOBAL INTERVAL BASELINE:",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "UP: ${stats.globalBaselineAlwaysUpWinRate}%",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "DOWN: ${stats.globalBaselineAlwaysDownWinRate}%",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 2: BACKTEST & AUDIT
 * Houses CCXT Replay Engine, Cumulative Persistent Metrics, Factor Attribution Matrix, and JSON Log Export.
 */
@Composable
fun BacktestAndLogsTab(
    uiState: MainUiState,
    onRunBacktest: () -> Unit,
    onCopyJson: () -> Unit
) {
    val stats = uiState.engineState.performanceStats
    val cumulative = uiState.cumulativeBacktest

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. CCXT Backtest Trigger Button
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
                    Text("Running 150-Cycle Backtest...", color = Color.Black, fontWeight = FontWeight.Bold)
                } else {
                    Text("RUN 150-CYCLE CCXT BACKTEST (30s HORIZON)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. Cumulative Backtest Performance Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                    .testTag("cumulative_backtest_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CUMULATIVE BACKTEST METRICS (30s HORIZON)",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "RUNS: ${cumulative.totalRuns}",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "CUMULATIVE WIN RATE",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val winRateDisplay = if (cumulative.totalTrades > 0) "${cumulative.winRatePercent}%" else "0.0%"
                            Text(
                                text = winRateDisplay,
                                color = if (cumulative.winRatePercent >= 60.0) Color(0xFF00E676) else if (cumulative.totalTrades > 0) Color(0xFFFFD600) else Color(0xFF64748B),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Total Samples: ${cumulative.totalSamples}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(text = "Total Trades: ${cumulative.totalTrades}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(text = "Correct: ${cumulative.correctPredictions}", color = Color(0xFF00E676), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(text = "Incorrect: ${cumulative.incorrectPredictions}", color = Color(0xFFFF334B), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // 3. Latest Backtest Run Details (if executed this session)
        if (uiState.backtestResult != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1424)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .testTag("latest_backtest_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "HISTORICAL REPLAY RUN (150 CYCLES • 30s)",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "HISTORICAL BACKTEST",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "MODEL WIN RATE",
                                    color = Color(0xFF64748B),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${uiState.backtestResult.winRatePercent}%",
                                    color = if (uiState.backtestResult.winRatePercent >= 60.0) Color(0xFF00E676) else Color(0xFFFFD600),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = "UP / DOWN: ${uiState.backtestResult.upPredictions} / ${uiState.backtestResult.downPredictions}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Trades: ${uiState.backtestResult.totalTrades}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Correct: ${uiState.backtestResult.correctPredictions} • Incorrect: ${uiState.backtestResult.incorrectPredictions}", color = Color(0xFF38BDF8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Baseline Comparisons
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
                                text = "BENCHMARK BASELINES:",
                                color = Color(0xFF64748B),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Always-UP: ${uiState.backtestResult.baselineAlwaysUpWinRate}%",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Always-DOWN: ${uiState.backtestResult.baselineAlwaysDownWinRate}%",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Persistent Backtest Run History
        if (cumulative.historyList.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PERSISTENT BACKTEST RUN HISTORY",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        cumulative.historyList.take(5).forEachIndexed { idx, run ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Run #${cumulative.historyList.size - idx} • ${run.totalSamples} pts (${run.horizonSeconds}s)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Trades: ${run.totalTrades} | ${run.winRatePercent}%",
                                    color = if (run.winRatePercent >= 60.0) Color(0xFF00E676) else Color(0xFFFFD600),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Empirical Factor Attribution Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                    .testTag("factor_attribution_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "EMPIRICAL FACTOR ATTRIBUTION & CALIBRATION",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (stats.factorAttributions.isEmpty()) {
                        Text(
                            text = "Calibrating empirical factor matrix with live data...",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        stats.factorAttributions.forEach { factor ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
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
                                    text = "Active: ${factor.totalTimesActive} • Win: ${factor.winRate}%",
                                    color = if (factor.winRate >= 60.0) Color(0xFF00E676) else Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                val offsetStr = String.format(Locale.US, "%+.2f", factor.suggestedWeightOffset)
                                Text(
                                    text = "Δw: $offsetStr",
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

        // 4. Structured JSON Logs Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
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
