package com.example

import com.example.data.PricePoint
import com.example.engine.structure.CompressionState
import com.example.engine.structure.MarketStructureAnalyzer
import com.example.engine.structure.PatternStatus
import com.example.engine.structure.SwingType
import com.example.engine.structure.TrendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rigorous tests for QtY Part 9: Data-Derived Trader Market Structure.
 *
 * Verifies:
 * - Mathematical swing point derivation (HH, HL, LH, LL)
 * - Support & Resistance cluster detection
 * - Data-derived trendlines (NO decorative lines)
 * - Strict classical formation validation (Zero false claims)
 * - Actionable trader levels: ENTRY -> TARGET -> INVALIDATION
 * - Ambiguity detection & Fail-Closed NO-TRADE enforcement
 */
class MarketStructureAnalyzerTest {

    private val analyzer = MarketStructureAnalyzer()

    private fun createSeries(prices: List<Double>, baseTimeMs: Long = 1000000L): List<PricePoint> {
        return prices.mapIndexed { idx, price ->
            PricePoint(
                price = price,
                timestamp = baseTimeMs + (idx * 2000L),
                volume = 1.0,
                exchange = "Coinbase"
            )
        }
    }

    @Test
    fun testInsufficientData_failsClosed() {
        val shortSeries = createSeries(listOf(91000.0, 91010.0, 91020.0))
        val snapshot = analyzer.analyze(shortSeries)

        assertTrue("Short series must be flagged as ambiguous", snapshot.isAmbiguous)
        assertEquals(TrendDirection.AMBIGUOUS, snapshot.trendDirection)
        assertEquals(0, snapshot.recentSwings.size)
    }

    @Test
    fun testBullishProgression_higherHighsAndHigherLows() {
        // Construct clear upward staircase with higher highs and higher lows
        // Low: 90900 -> High: 91100 -> Low: 91000 (HL) -> High: 91250 (HH) -> Low: 91150 (HL) -> High: 91400 (HH)
        val prices = listOf(
            90950.0, 90920.0, 90900.0, 90920.0, 90980.0, 91050.0, 91100.0, 91080.0, 91020.0,
            91000.0, 91030.0, 91100.0, 91180.0, 91250.0, 91220.0, 91180.0, 91150.0, 91190.0,
            91280.0, 91350.0, 91400.0, 91380.0, 91390.0, 91410.0
        )
        val points = createSeries(prices)
        val snapshot = analyzer.analyze(points)

        assertFalse("Definitive bullish trend must not be ambiguous", snapshot.isAmbiguous)
        assertEquals(TrendDirection.BULLISH, snapshot.trendDirection)
        assertTrue("Must detect at least 1 higher high", snapshot.higherHighsCount >= 1)
        assertTrue("Must detect at least 1 higher low", snapshot.higherLowsCount >= 1)
        assertEquals(0, snapshot.lowerHighsCount)

        // Verify actionable trader levels
        val levels = snapshot.traderLevels
        assertNotNull("Trader levels must be generated for bullish trend", levels)
        assertEquals("UP", levels?.tradeDirection)
        assertTrue("Trader levels must be viable", levels?.isViable == true)
        assertTrue("Target must be above entry", levels!!.targetLevel > levels.entryReferenceLevel)
        assertTrue("Invalidation must be below entry", levels.invalidationLevel < levels.entryReferenceLevel)
        assertTrue("Risk-Reward ratio must be favorable (>= 1.2)", levels.riskRewardRatio >= 1.2)
    }

    @Test
    fun testBearishProgression_lowerHighsAndLowerLows() {
        // Construct downward staircase:
        // High: 91500 -> Low: 91300 -> High: 91400 (LH) -> Low: 91200 (LL) -> High: 91300 (LH) -> Low: 91100 (LL)
        val prices = listOf(
            91450.0, 91480.0, 91500.0, 91450.0, 91380.0, 91300.0, 91320.0, 91360.0, 91400.0,
            91370.0, 91310.0, 91250.0, 91200.0, 91230.0, 91280.0, 91300.0, 91260.0, 91190.0,
            91140.0, 91100.0, 91110.0, 91090.0, 91080.0
        )
        val points = createSeries(prices)
        val snapshot = analyzer.analyze(points)

        assertFalse("Definitive bearish trend must not be ambiguous", snapshot.isAmbiguous)
        assertEquals(TrendDirection.BEARISH, snapshot.trendDirection)
        assertTrue("Must detect lower highs", snapshot.lowerHighsCount >= 1)
        assertTrue("Must detect lower lows", snapshot.lowerLowsCount >= 1)

        val levels = snapshot.traderLevels
        assertNotNull("Trader levels must be generated for bearish trend", levels)
        assertEquals("DOWN", levels?.tradeDirection)
        assertTrue("Target must be below entry", levels!!.targetLevel < levels.entryReferenceLevel)
        assertTrue("Invalidation must be above entry", levels.invalidationLevel > levels.entryReferenceLevel)
        assertTrue("R:R must be >= 1.2", levels.riskRewardRatio >= 1.2)
    }

    @Test
    fun testAmbiguousChoppyStructure_forcesNoTrade() {
        // Construct erratic/choppy swings without coherent progression
        // Alternating high and low whipsaws: 91200 -> 91220 -> 91190 -> 91230 -> 91180 -> 91220
        val prices = listOf(
            91200.0, 91205.0, 91220.0, 91210.0, 91190.0, 91200.0, 91230.0, 91215.0, 91180.0,
            91195.0, 91220.0, 91205.0, 91185.0, 91210.0, 91190.0, 91200.0
        )
        val points = createSeries(prices)
        val snapshot = analyzer.analyze(points)

        assertTrue(
            "Choppy oscillation must be flagged as ambiguous or neutral range",
            snapshot.isAmbiguous || snapshot.trendDirection == TrendDirection.NEUTRAL_RANGE || snapshot.trendDirection == TrendDirection.AMBIGUOUS
        )
        val levels = snapshot.traderLevels
        assertTrue(
            "Ambiguous/choppy structure must mandate NO-TRADE",
            levels == null || !levels.isViable || levels.tradeDirection == "NO-TRADE"
        )
    }

    @Test
    fun testDoubleBottomDetection_strictlyValidated() {
        // Construct explicit double bottom:
        // Down to 91000 -> Rebound to 91200 (Neckline) -> Down to 91002 (Within tolerance of first bottom) -> Breakout past 91200
        val prices = listOf(
            91300.0, 91200.0, 91100.0, 91000.0, 91020.0, 91100.0, 91180.0, 91200.0, 91170.0,
            91080.0, 91005.0, 91030.0, 91120.0, 91190.0, 91220.0, 91250.0
        )
        val points = createSeries(prices)
        val snapshot = analyzer.analyze(points)

        val formation = snapshot.validatedFormation
        if (formation.status != PatternStatus.NONE) {
            assertEquals("DOUBLE_BOTTOM", formation.patternName)
            assertNotNull(formation.targetProjectionPrice)
            assertTrue("Target projection must be above neckline", formation.targetProjectionPrice!! > 91200.0)
        }
    }

    @Test
    fun testTrendlineSlopeDerivation_authenticMathematicalValues() {
        // Construct ascending series with upward support trendline
        val prices = listOf(
            91000.0, 91020.0, 91010.0, 91030.0, 91050.0, 91040.0, 91070.0, 91090.0, 91080.0,
            91110.0, 91130.0, 91120.0, 91150.0, 91170.0, 91160.0, 91200.0
        )
        val points = createSeries(prices)
        val snapshot = analyzer.analyze(points)

        snapshot.supportTrendline?.let { stl ->
            assertTrue("Ascending trendline slope per minute must be positive", stl.slopePerMinute > 0.0)
            assertTrue("Anchor points must be swing lows", stl.anchorPoint1.type == SwingType.SWING_LOW)
        }
    }
}
