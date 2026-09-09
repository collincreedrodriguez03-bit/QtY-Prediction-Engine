package com.example

import com.example.engine.EngineState
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionEngine
import com.example.ui.EXTENDED_FORECAST_HORIZONS
import com.example.ui.IMMEDIATE_SCALP_HORIZONS
import com.example.ui.resolveTruthfulForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiHorizonForecastVisualizationTest {

    @Test
    fun testImmediateScalpHorizonsExactMatch() {
        val expected = listOf(5, 10, 30, 60, 120, 300)
        val actual = IMMEDIATE_SCALP_HORIZONS.map { it.seconds }
        assertEquals("Immediate Scalp must contain exactly the 6 canonical horizons", expected, actual)
        val primary = IMMEDIATE_SCALP_HORIZONS.find { it.isPrimaryBaseline }
        assertNotNull("Must designate a primary baseline", primary)
        assertEquals("30s must be the primary baseline", 30, primary!!.seconds)
    }

    @Test
    fun testExtendedForecastHorizonsExactMatch() {
        val expected = listOf(30, 60, 120, 300, 600, 900)
        val actual = EXTENDED_FORECAST_HORIZONS.map { it.seconds }
        assertEquals("Extended Forecast must contain exactly the 6 canonical horizons", expected, actual)
        val primary = EXTENDED_FORECAST_HORIZONS.find { it.isPrimaryBaseline }
        assertNotNull("Must designate a primary baseline", primary)
        assertEquals("30s must be the primary baseline anchor", 30, primary!!.seconds)
        val contractTarget = EXTENDED_FORECAST_HORIZONS.find { it.isContractTarget }
        assertNotNull("Must designate 15m contract target", contractTarget)
        assertEquals("900s must be the 15-minute contract target", 900, contractTarget!!.seconds)
    }

    @Test
    fun testHorizonsHaveDistinguishableColors() {
        val scalpColors = IMMEDIATE_SCALP_HORIZONS.map { it.color }
        val scalpDistinct = scalpColors.distinct()
        assertEquals("Each immediate scalp horizon must have a distinct color", scalpColors.size, scalpDistinct.size)
        val extColors = EXTENDED_FORECAST_HORIZONS.map { it.color }
        val extDistinct = extColors.distinct()
        assertEquals("Each extended forecast horizon must have a distinct color", extColors.size, extDistinct.size)
    }

    @Test
    fun testTruthfulResolutionIntegrity() {
        val engine = PredictionEngine(predictionHorizonSeconds = 30)
        val currentPrice = 90500.0
        val snapshot = IndicatorSnapshot(
            ema9 = 90520.0,
            ema21 = 90480.0,
            rsi = 62.0,
            momentum = 40.0,
            velocity = 6.0,
            volatility = 18.0,
            volumeChange = 1.1,
            buffer = 25.0
        )
        val timestamp = 1700000000000L
        val settlementRef = 90450.0
        val record = engine.predict(
            currentPrice = currentPrice,
            snapshot = snapshot,
            timestamp = timestamp,
            settlementReference = settlementRef
        )
        val engineState = EngineState(
            isRunning = true,
            latestPrice = currentPrice,
            latestTimestamp = timestamp,
            latestSnapshot = snapshot,
            latestPrediction = record
        )

        for (config in IMMEDIATE_SCALP_HORIZONS) {
            val forecast = resolveTruthfulForecast(
                engineState = engineState,
                horizonSec = config.seconds
            )
            assertNotNull(forecast)
            assertEquals(config.seconds, forecast!!.horizonSeconds)
            assertEquals(currentPrice, forecast.currentPrice, 1e-4)
            assertEquals(timestamp + config.seconds * 1000L, forecast.maturityTimestamp)
            assertTrue(forecast.predictedPrice > 0.0)
            assertTrue(forecast.score in 0.0..1.0)
        }

        for (config in EXTENDED_FORECAST_HORIZONS) {
            val forecast = resolveTruthfulForecast(
                engineState = engineState,
                horizonSec = config.seconds
            )
            assertNotNull(forecast)
            assertEquals(config.seconds, forecast!!.horizonSeconds)
            assertEquals(currentPrice, forecast.currentPrice, 1e-4)
            assertEquals(timestamp + config.seconds * 1000L, forecast.maturityTimestamp)
            assertTrue(forecast.predictedPrice > 0.0)
        }
    }
}
