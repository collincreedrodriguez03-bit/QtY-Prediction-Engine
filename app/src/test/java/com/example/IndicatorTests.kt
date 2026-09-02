package com.example

import com.example.data.PricePoint
import com.example.engine.IndicatorCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IndicatorTests {

    private lateinit var calculator: IndicatorCalculator

    @Before
    fun setup() {
        calculator = IndicatorCalculator()
    }

    @Test
    fun testEMA9Calculation() {
        // Linear price series
        val prices = listOf(
            100.0, 102.0, 104.0, 103.0, 105.0,
            107.0, 106.0, 108.0, 110.0, 112.0
        )
        val ema9 = calculator.calculateEMA(prices, 9)
        // With upward trend, EMA9 should be between initial SMA (~105.0) and latest price (112.0)
        assertTrue("EMA9 should be greater than 105.0", ema9 > 105.0)
        assertTrue("EMA9 should be less than 112.0", ema9 < 112.0)
    }

    @Test
    fun testEMA21Calculation() {
        val prices = (1..30).map { 100.0 + it * 2.0 }
        val ema21 = calculator.calculateEMA(prices, 21)
        assertTrue("EMA21 should be weighted and lag behind latest price", ema21 > 100.0 && ema21 < prices.last())
    }

    @Test
    fun testRSICalculation() {
        // Monotonically increasing prices -> RSI should be close to 100
        val risingPrices = (1..20).map { 50000.0 + it * 100.0 }
        val rsiUp = calculator.calculateRSI(risingPrices, 14)
        assertEquals(100.0, rsiUp, 0.01)

        // Monotonically decreasing prices -> RSI should be 0
        val fallingPrices = (1..20).map { 50000.0 - it * 100.0 }
        val rsiDown = calculator.calculateRSI(fallingPrices, 14)
        assertEquals(0.0, rsiDown, 0.01)

        // Alternating prices -> RSI should be near 50
        val alternatingPrices = listOf(
            100.0, 102.0, 100.0, 102.0, 100.0, 102.0, 100.0, 102.0,
            100.0, 102.0, 100.0, 102.0, 100.0, 102.0, 100.0, 102.0
        )
        val rsiNeutral = calculator.calculateRSI(alternatingPrices, 14)
        assertTrue("RSI for alternating prices should be near 50 (was $rsiNeutral)", rsiNeutral in 40.0..60.0)
    }

    @Test
    fun testMomentumCalculation() {
        val prices = listOf(50000.0, 50100.0, 50200.0, 50300.0, 50400.0, 50550.0)
        // 50550 - 50000 = 550.0 (lookback 5 periods from last)
        val momentum = calculator.calculateMomentum(prices, 5)
        assertEquals(550.0, momentum, 0.001)

        // Downward move
        val downPrices = listOf(50500.0, 50400.0, 50300.0, 50200.0, 50100.0, 50000.0)
        val downMom = calculator.calculateMomentum(downPrices, 5)
        assertEquals(-500.0, downMom, 0.001)
    }

    @Test
    fun testVelocityCalculation() {
        val t0 = 1000000L
        val t1 = t0 + 2000L // 2.0 seconds
        val p0 = PricePoint(price = 60000.0, timestamp = t0)
        val p1 = PricePoint(price = 60050.0, timestamp = t1)

        val velocity = calculator.calculateVelocity(p1, p0)
        // +50 USD in 2.0 seconds = +25.0 USD/sec
        assertEquals(25.0, velocity, 0.001)
    }

    @Test
    fun testAccelerationCalculation() {
        val v0 = 10.0 // USD/sec
        val v1 = 25.0 // USD/sec
        val deltaT = 2.0 // seconds

        val accel = calculator.calculateAcceleration(v1, v0, deltaT)
        // (25 - 10) / 2 = 7.5 USD/sec^2
        assertEquals(7.5, accel, 0.001)
    }

    @Test
    fun testVolatilityCalculation() {
        // High variance vs low variance
        val lowVariance = listOf(100.0, 100.1, 99.9, 100.0, 100.2, 99.8, 100.0)
        val highVariance = listOf(100.0, 150.0, 80.0, 140.0, 60.0, 130.0, 90.0)

        val lowVol = calculator.calculateVolatility(lowVariance, 10)
        val highVol = calculator.calculateVolatility(highVariance, 10)

        assertTrue("High volatility should be much higher than low volatility", highVol > lowVol * 10)
    }

    @Test
    fun testInsufficientDataHandledGracefully() {
        // Empty list
        assertEquals(0.0, calculator.calculateEMA(emptyList(), 9), 0.001)
        assertEquals(50.0, calculator.calculateRSI(emptyList(), 14), 0.001)
        assertEquals(0.0, calculator.calculateMomentum(emptyList(), 5), 0.001)
        assertEquals(0.0, calculator.calculateVolatility(emptyList(), 10), 0.001)

        // Single price
        val single = listOf(65000.0)
        assertEquals(65000.0, calculator.calculateEMA(single, 9), 0.001)
        assertEquals(50.0, calculator.calculateRSI(single, 14), 0.001)
        assertEquals(0.0, calculator.calculateMomentum(single, 5), 0.001)
        assertEquals(0.0, calculator.calculateVolatility(single, 10), 0.001)
    }
}
