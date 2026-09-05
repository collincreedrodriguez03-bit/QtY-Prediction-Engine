package com.example

import com.example.engine.PredictionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * P0 Mandate 2: Volatility Mathematics Audit Test.
 *
 * Requirements:
 * - Mathematical appropriateness for BTC's price scale across authentic micro-volatility regimes
 * - No artificial/random calibration data
 * - No saturation caused by incorrect units
 * - Low/normal/high/extreme volatility tests
 * - Document mathematical reasoning
 * - Preserve unrelated model behavior
 */
class VolatilityMathematicsTest {

    private lateinit var engine: PredictionEngine
    private val btcPrice = 90_000.0 // Realistic current BTC spot price in USD

    @Before
    fun setUp() {
        engine = PredictionEngine()
    }

    @Test
    fun testZeroVolatilityReturnsNeutral() {
        // Zero volatility should neither amplify nor dampen
        val signal = engine.normalizeVolatilitySignal(0.0, btcPrice, trendDirection = 0.8)
        assertEquals(0.5, signal, 1e-4)
    }

    @Test
    fun testZeroOrNegativePriceFailsClosedToNeutral() {
        assertEquals(0.5, engine.normalizeVolatilitySignal(30.0, 0.0, 0.8), 1e-4)
        assertEquals(0.5, engine.normalizeVolatilitySignal(30.0, -100.0, 0.8), 1e-4)
    }

    @Test
    fun testNeutralTrendDirectionReturnsNeutralSignal() {
        // If trendDirection is 0.5 (neutral), any volatility level results in 0.5
        val signalLow = engine.normalizeVolatilitySignal(10.0, btcPrice, trendDirection = 0.5)
        val signalHigh = engine.normalizeVolatilitySignal(100.0, btcPrice, trendDirection = 0.5)
        assertEquals(0.5, signalLow, 1e-4)
        assertEquals(0.5, signalHigh, 1e-4)
    }

    @Test
    fun testLowVolatilityRegime() {
        // Low volatility: 0.5 to 1.5 bps (0.005% - 0.015% of spot; ~$4.50 - $13.50 on $90k BTC)
        val lowVolDollars = 9.0 // 1 bp = 0.01%
        val trendBullish = 0.8

        val signal = engine.normalizeVolatilitySignal(lowVolDollars, btcPrice, trendBullish)

        // With low volatility, directional reinforcement should be mild (damped)
        assertTrue("Low vol signal should be in (0.50, 0.65), got $signal", signal in 0.51..0.65)
        assertTrue("Signal should be strictly bounded in [0, 1]", signal in 0.0..1.0)
    }

    @Test
    fun testNormalVolatilityRegime() {
        // Normal volatility: 2.0 to 5.0 bps (0.020% - 0.050% of spot; ~$18 - $45 on $90k BTC)
        val normalVolDollars = 27.0 // 3 bps
        val trendBullish = 0.8

        val signal = engine.normalizeVolatilitySignal(normalVolDollars, btcPrice, trendBullish)

        // Normal volatility provides balanced directional reinforcement
        assertTrue("Normal vol signal should be in (0.60, 0.75), got $signal", signal in 0.60..0.75)
        assertTrue("Signal should be strictly bounded in [0, 1]", signal in 0.0..1.0)
    }

    @Test
    fun testHighVolatilityRegime() {
        // High volatility: 6.0 to 15.0 bps (0.060% - 0.150% of spot; ~$54 - $135 on $90k BTC)
        val highVolDollars = 81.0 // 9 bps
        val trendBullish = 0.8

        val signal = engine.normalizeVolatilitySignal(highVolDollars, btcPrice, trendBullish)

        // High volatility strongly reinforces the prevailing trend
        assertTrue("High vol signal should be in (0.70, 0.85), got $signal", signal in 0.70..0.85)
        assertTrue("Signal should be strictly bounded in [0, 1]", signal in 0.0..1.0)
    }

    @Test
    fun testExtremeVolatilityRegime() {
        // Extreme volatility: >= 25.0 bps (>= 0.250% of spot; >= $225 on $90k BTC)
        val extremeVolDollars = 450.0 // 50 bps
        val trendBullish = 0.8

        val signal = engine.normalizeVolatilitySignal(extremeVolDollars, btcPrice, trendBullish)

        // Under extreme volatility, tanh smoothly approaches asymptotic saturation without overflowing
        assertTrue("Extreme vol signal should approach maximum reinforcement (~0.80), got $signal", signal in 0.75..0.80)
        assertTrue("Signal must remain strictly <= 1.0", signal <= 1.0)
    }

    @Test
    fun testBearishSymmetryAcrossRegimes() {
        val trendBearish = 0.2 // (trendDirection - 0.5) = -0.3

        val normalVolDollars = 27.0 // 3 bps
        val bullSignal = engine.normalizeVolatilitySignal(normalVolDollars, btcPrice, trendDirection = 0.8)
        val bearSignal = engine.normalizeVolatilitySignal(normalVolDollars, btcPrice, trendDirection = 0.2)

        // Bullish and Bearish should be perfectly symmetric around 0.5
        val bullDelta = bullSignal - 0.5
        val bearDelta = 0.5 - bearSignal
        assertEquals("Bullish and Bearish volatility signals must be symmetric", bullDelta, bearDelta, 1e-4)
    }
}
