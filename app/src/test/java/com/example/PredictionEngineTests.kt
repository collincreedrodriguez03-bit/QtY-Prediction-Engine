package com.example

import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PredictionEngineTests {

    private lateinit var engine: PredictionEngine

    @Before
    fun setup() {
        engine = PredictionEngine()
    }

    @Test
    fun testUpPredictionWhenFactorsBullish() {
        val currentPrice = 65000.0
        val bullishSnapshot = IndicatorSnapshot(
            ema9 = 65150.0,
            ema21 = 64900.0,
            rsi = 78.0,
            momentum = 150.0,
            velocity = 20.0,
            acceleration = 5.0,
            volatility = 40.0,
            volume = 25.0,
            volumeChange = 1.8,
            buffer = 200.0,
            bidAskSpread = 0.5,
            exchangeAgreement = "STRONG_AGREEMENT"
        )

        val prediction = engine.predict(currentPrice, bullishSnapshot)

        assertEquals("UP", prediction.decision)
        assertTrue("Score should be >= 0.65 for UP, got ${prediction.score}", prediction.score >= 0.65)
        assertTrue("Predicted price should be higher than current price", prediction.predictedPrice > currentPrice)
        assertTrue("Formula display should not be empty", prediction.inputs.formulaDisplay.isNotBlank())
    }

    @Test
    fun testDownPredictionWhenFactorsBearish() {
        val currentPrice = 65000.0
        val bearishSnapshot = IndicatorSnapshot(
            ema9 = 64850.0,
            ema21 = 65100.0,
            rsi = 22.0,
            momentum = -180.0,
            velocity = -25.0,
            acceleration = -6.0,
            volatility = 45.0,
            volume = 30.0,
            volumeChange = 2.0,
            buffer = -250.0,
            bidAskSpread = 0.5,
            exchangeAgreement = "STRONG_AGREEMENT"
        )

        val prediction = engine.predict(currentPrice, bearishSnapshot)

        assertEquals("DOWN", prediction.decision)
        assertTrue("Score should be <= 0.35 for DOWN, got ${prediction.score}", prediction.score <= 0.35)
        assertTrue("Predicted price should be lower than current price", prediction.predictedPrice < currentPrice)
    }

    @Test
    fun testNoTradeWhenMixedSignals() {
        val currentPrice = 65000.0
        val neutralSnapshot = IndicatorSnapshot(
            ema9 = 65000.0,
            ema21 = 65000.0,
            rsi = 50.0,
            momentum = 0.0,
            velocity = 0.0,
            acceleration = 0.0,
            volatility = 10.0,
            volume = 10.0,
            volumeChange = 1.0,
            buffer = 0.0,
            bidAskSpread = 0.5,
            exchangeAgreement = "STRONG_AGREEMENT"
        )

        val prediction = engine.predict(currentPrice, neutralSnapshot)

        assertEquals("NO-TRADE", prediction.decision)
        assertTrue("Score should be between 0.35 and 0.65, got ${prediction.score}", prediction.score in 0.35..0.65)
    }

    @Test
    fun testScoreRangeBoundedZeroToOne() {
        val extremeHigh = IndicatorSnapshot(
            ema9 = 100000.0,
            ema21 = 50000.0,
            rsi = 100.0,
            momentum = 5000.0,
            velocity = 500.0,
            acceleration = 100.0,
            volatility = 1000.0,
            volume = 100.0,
            volumeChange = 10.0,
            buffer = 5000.0,
            bidAskSpread = 0.1
        )
        val extremeLow = IndicatorSnapshot(
            ema9 = 50000.0,
            ema21 = 100000.0,
            rsi = 0.0,
            momentum = -5000.0,
            velocity = -500.0,
            acceleration = -100.0,
            volatility = 1000.0,
            volume = 100.0,
            volumeChange = 10.0,
            buffer = -5000.0,
            bidAskSpread = 0.1
        )

        val highPred = engine.predict(65000.0, extremeHigh)
        val lowPred = engine.predict(65000.0, extremeLow)

        assertTrue(highPred.score in 0.0..1.0)
        assertTrue(lowPred.score in 0.0..1.0)
        assertTrue(highPred.score >= 0.80)
        assertTrue(lowPred.score <= 0.20)
    }

    @Test
    fun testStrengthLevelsAssignedCorrectly() {
        val strongUp = IndicatorSnapshot(
            ema9 = 70000.0, ema21 = 60000.0, rsi = 90.0, momentum = 500.0,
            velocity = 50.0, acceleration = 10.0, volatility = 50.0,
            volume = 50.0, volumeChange = 2.0, buffer = 500.0, bidAskSpread = 0.2
        )
        val pred = engine.predict(65000.0, strongUp)
        assertEquals("STRONG", pred.strength)
    }
}
