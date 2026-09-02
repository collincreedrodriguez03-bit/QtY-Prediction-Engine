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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    uiState: com.example.ui.MainUiState,
    onToggleEngine: () -> Unit,
    onSingleCycle: () -> Unit,
    onRunBacktest: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onCopyJson: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0A0E17)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header
            HeaderBar(
                engineState = uiState.engineState,
                onToggle = onToggleEngine,
                onRefresh = onSingleCycle
            )

            // Navigation Tabs
            TabRow(
                selectedTabIndex = uiState.activeTab,
                containerColor = Color(0xFF111827),
                contentColor = Color(0xFF00E5FF),
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.activeTab == 0,
                    onClick = { onTabSelected(0) },
                    text = { Text("LIVE ENGINE", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    modifier = Modifier.testTag("tab_live_engine")
                )
                Tab(
                    selected = uiState.activeTab == 1,
                    onClick = { onTabSelected(1) },
                    text = { Text("CCXT BACKTEST", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    modifier = Modifier.testTag("tab_backtest")
                )
                Tab(
                    selected = uiState.activeTab == 2,
                    onClick = { onTabSelected(2) },
                    text = { Text("JSON FEED", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    modifier = Modifier.testTag("tab_json_feed")
                )
            }

            // Tab Content
            when (uiState.activeTab) {
                0 -> LiveEngineTab(engineState = uiState.engineState)
                1 -> BacktestTab(
                    backtestResult = uiState.backtestResult,
                    isBacktesting = uiState.isBacktesting,
                    onRun = onRunBacktest
                )
                2 -> JsonFeedTab(
                    predictions = uiState.engineState.recentPredictions,
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
        color = Color(0xFF111827),
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
                    Text(
                        text = "QtY PREDICTION ENGINE",
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Phase 1: Real Data • 2s Cycle #${engineState.cycleCount}",
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

@Composable
fun LiveEngineTab(engineState: EngineState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live Price & Multi-Exchange Ticker
        item {
            PriceTickerCard(engineState = engineState)
        }

        // 2. Active Mathematics Box
        item {
            MathBoxCard(engineState = engineState)
        }

        // 3. Primary Prediction Card
        item {
            PredictionCard(engineState = engineState)
        }

        // 4. Indicator Matrix Grid
        item {
            IndicatorsGridCard(snapshot = engineState.latestSnapshot)
        }

        // 5. Recent Predictions History Preview
        item {
            RecentPredictionsCard(predictions = engineState.recentPredictions)
        }
    }
}

@Composable
fun PriceTickerCard(engineState: EngineState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("price_ticker_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BTC / USDT",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                val agreement = engineState.exchangeComparison?.agreementStatus?.name ?: "SYNCING"
                val agreementColor = when (agreement) {
                    "STRONG_AGREEMENT" -> Color(0xFF00E676)
                    "DISAGREEMENT" -> Color(0xFFFF5252)
                    else -> Color(0xFFFFD600)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(agreementColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = agreement.replace("_", " "),
                        color = agreementColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (engineState.latestPrice > 0) "$${String.format(Locale.US, "%,.2f", engineState.latestPrice)}" else "Fetching...",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )

                Text(
                    text = "Source: ${engineState.latestExchange}",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    fontFamily = FontFamily.Monospace
                )
            }

            if (engineState.krakenPrice != null && engineState.krakenPrice > 0.0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Kraken: $${String.format(Locale.US, "%,.2f", engineState.krakenPrice)}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Spread: $${String.format(Locale.US, "%.2f", engineState.latestSnapshot?.bidAskSpread ?: 0.0)}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun MathBoxCard(engineState: EngineState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .testTag("mathematics_box")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE MATHEMATICAL EQUATION",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "2s REFRESH",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = engineState.mathDisplay,
                color = Color(0xFFE2E8F0),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PredictionCard(engineState: EngineState) {
    val pred = engineState.latestPrediction
    val decision = pred?.decision ?: "NO-TRADE"
    val decisionColor = when (decision) {
        "UP" -> Color(0xFF00E676)
        "DOWN" -> Color(0xFFFF3D00)
        else -> Color(0xFF94A3B8)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("prediction_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEXT 60s SCALP PREDICTION",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "HORIZON: ${pred?.predictionHorizon ?: 60}s",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (decision == "UP") Icons.Default.ArrowUpward else if (decision == "DOWN") Icons.Default.ArrowDownward else Icons.Default.PlayArrow,
                        contentDescription = decision,
                        tint = decisionColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = decision,
                            color = decisionColor,
                            fontSize = 28.sp,
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
                        text = "${Math.round((pred?.score ?: 0.5) * 100.0)}% SCORE",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
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
fun IndicatorsGridCard(snapshot: IndicatorSnapshot?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
        shape = RoundedCornerShape(12.dp),
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
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0F172A))
            .padding(8.dp)
    ) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun RecentPredictionsCard(predictions: List<PredictionRecord>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("recent_predictions_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "RECENT PREDICTIONS (${predictions.size})",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

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
                            "DOWN" -> Color(0xFFFF3D00)
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

@Composable
fun BacktestTab(
    backtestResult: BacktestResult?,
    isBacktesting: Boolean,
    onRun: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Button(
                onClick = onRun,
                enabled = !isBacktesting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_backtest_button")
            ) {
                if (isBacktesting) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Running CCXT Replay...", color = Color.Black, fontWeight = FontWeight.Bold)
                } else {
                    Text("RUN 150-CYCLE CCXT BACKTEST", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (backtestResult != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("backtest_metrics_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "BACKTEST EVALUATION RESULTS",
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
                                    text = "${backtestResult.winRatePercent}%",
                                    color = if (backtestResult.winRatePercent >= 60.0) Color(0xFF00E676) else Color(0xFFFFD600),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = "Total Samples: ${backtestResult.totalSamples}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Total Trades: ${backtestResult.totalTrades}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Correct: ${backtestResult.correctPredictions}", color = Color(0xFF00E676), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(text = "Incorrect: ${backtestResult.incorrectPredictions}", color = Color(0xFFFF3D00), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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

            items(backtestResult.samplePredictions) { sample ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
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
                            Text(text = "Actual 60s: $${String.format(Locale.US, "%,.1f", sample.actualPrice ?: 0.0)}", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        val resColor = when (sample.result) {
                            "CORRECT" -> Color(0xFF00E676)
                            "INCORRECT" -> Color(0xFFFF3D00)
                            else -> Color(0xFF64748B)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = sample.decision, color = if (sample.decision == "UP") Color(0xFF00E676) else Color(0xFFFF3D00), fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Text(text = sample.result ?: "PENDING", color = resColor, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JsonFeedTab(
    predictions: List<PredictionRecord>,
    onCopyJson: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
        ) {
            LazyColumn(modifier = Modifier.padding(12.dp)) {
                items(predictions.takeLast(10).reversed()) { rec ->
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
