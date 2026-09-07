package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.engine.EngineState

/**
 * QtY Primary Trading-Visualization Workspace.
 *
 * Implements the Two-Level Forecasting Visualization:
 * - TOP GRAPH — IMMEDIATE SCALP FORECAST:
 *   5s, 10s, 30s, 60s, 90s, 120s, 180s, 240s, 300s.
 * - BOTTOM GRAPH — EXTENDED FORECAST:
 *   30s, 60s, 180s, 300s, 600s, 900s (15m Strike), 1200s (20m).
 *
 * Clearly distinguishes:
 * - SOLID/PRIMARY PRICE LINE: Authentic real-time BTC price history up to NOW (t).
 * - FORECAST LINES: Model projections extending strictly from current price into future time.
 * - Restrained, distinguishable colors & transparency (no visual mess, no fabricated confidence bands).
 * - Transparent attribution: Single multi-scale econometric engine.
 */
@Composable
fun BtcLivePredictionChart(
    engineState: EngineState,
    modifier: Modifier = Modifier
) {
    MultiHorizonForecastWorkspace(
        engineState = engineState,
        modifier = modifier
    )
}
