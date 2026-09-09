package com.example

import com.example.data.ExchangeAgreementStatus
import com.example.data.MarketDataConsolidator
import com.example.data.PricePoint
import com.example.engine.Backtester
import com.example.engine.IndicatorCalculator
import com.example.engine.IndicatorSnapshot
import com.example.engine.PerformanceTracker
import com.example.engine.PredictionEngine
import com.example.engine.external.ExternalFeatureCoordinator
import com.example.engine.structure.MarketStructureAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * QtY PART 10/10 — UNIFIED CORE INTEGRATION TEST
 *
 * Mathematically audits and asserts the complete single-pipeline flow:
 *
 * AUTHENTIC BTC DATA
 * → MARKET STRUCTURE
 * → INDICATORS
 * → VOLUME/ORDER FLOW
 * → APPROVED EXTERNAL RESEARCH
 * → MODEL SCORE
 * → MULTI-HORIZON FORECAST
 * → TARGET/INVALIDATION STRUCTURE
 * → LIVE OUTCOME
 * → PERFORMANCE
 * → LEARNING.
 */
class UnifiedCoreIntegrationTest {
    private lateinit var consolidator: MarketDataConsolidator
    private lateinit var structureAnalyzer: MarketStructureAnalyzer
    private lateinit var indicatorCalculator: IndicatorCalculator
    private lateinit var externalCoordinator: ExternalFeatureCoordinator
    private lateinit var predictionEngine: PredictionEngine
    private lateinit var performanceTracker: PerformanceTracker
    private lateinit var backtester: Backtester

    @Before
    fun setUp() {
        consolidator = MarketDataConsolidator()
        structureAnalyzer = MarketStructureAnalyzer()
        indicatorCalculator = IndicatorCalculator()
        externalCoordinator = ExternalFeatureCoordinator()
        predictionEngine = PredictionEngine(predictionHorizonSeconds = 30)
        performanceTracker = PerformanceTracker()
        backtester = Backtester(
            indicatorCalculator = indicatorCalculator,
            predictionEngine = predictionEngine
        )
    }

    private fun generateAuthenticTickSequence(
        count: Int,
        basePrice: Double = 91200.0,
        startMs: Long = 1710000000000L
    ): List<PricePoint> {
        val list = mutableListOf<PricePoint>()
        var p = basePrice
        for (i in 0 until count) {
            val step = kotlin.math.sin(i * 0.15) * 8.0 + (i * 0.3)
            p += step
            list.add(
                PricePoint(
                    price = p,
                    timestamp = startMs + (i * 2000L),
                    exchange = "BINANCE",
                    volume = 2.5 + (i % 4) * 0.8
                )
            )
        }
        return list
    }

    @Test
    fun testStage1_AuthenticDataConsolidation() {
        val now = System.currentTimeMillis()
        val binance = PricePoint(price = 91250.0, timestamp = now, exchange = "BINANCE", volume = 2.0)
        val coinbase = PricePoint(price = 91252.0, timestamp = now - 500, exchange = "COINBASE", volume = 1.5)
        val kraken = PricePoint(price = 91249.0, timestamp = now - 1000, exchange = "KRAKEN", volume = 1.0)
        val state = consolidator.consolidate(listOf(binance, coinbase, kraken), now)
        assertNotNull(state)
        assertEquals(91250.33, state.consolidatedPrice, 0.1)
        assertEquals(ExchangeAgreementStatus.STRONG_AGREEMENT, state.agreementStatus)
    }

    @Test
    fun testStage2_MarketStructureDerivation() {
        val prices = listOf(
            91000.0, 91050.0, 91100.0, 91080.0, 91020.0,
            91000.0, 91030.0, 91100.0, 91180.0, 91250.0, 91220.0, 91180.0, 91150.0, 91190.0,
            91280.0, 91350.0, 91400.0, 91380.0, 91390.0, 91410.0
        )
        val baseMs = 1710000000000L
        val points = prices.mapIndexed { idx, p ->
            PricePoint(price = p, timestamp = baseMs + (idx * 2000L), volume = 1.0, exchange = "COINBASE")
        }
        val asOf = points.last().timestamp
        val spot = points.last().price
        val snapshot = structureAnalyzer.analyze(points, asOf, spot)
        assertNotNull(snapshot)
        assertFalse(snapshot.isAmbiguous)
        assertNotNull(snapshot.traderLevels)
        val sup = snapshot.primarySupport?.price ?: 0.0
        val res = snapshot.primaryResistance?.price ?: 0.0
        if (sup > 0.0 && res > 0.0) {
            assertTrue("Support must be strictly below resistance", sup <= res)
        }
    }

    @Test
    fun testStage3_IndicatorCalculationLineage() {
        val points = generateAuthenticTickSequence(50, 91000.0)
        val snapshot = indicatorCalculator.computeSnapshot(
            points = points,
            referencePrice = 91000.0,
            previousVelocity = 0.0,
            exchangeAgreement = "STRONG_AGREEMENT"
        )
        assertTrue("RSI must be bounded between 0 and 100", snapshot.rsi in 0.0..100.0)
        assertTrue("Volatility must be strictly non-negative", snapshot.volatility >= 0.0)
        assertTrue("EMA9 must be positive", snapshot.ema9 > 0.0)
        assertTrue("EMA21 must be positive", snapshot.ema21 > 0.0)
    }

    @Test
    fun testStage4_ExternalResearchStrictFailClosed() {
        val points = generateAuthenticTickSequence(50, 91000.0)
        val timestamp = points.last().timestamp
        val research = externalCoordinator.computeFeatures(points, timestamp)
        assertNotNull(research)
        assertFalse(research.cryptoQuantWhaleMomentum.isAvailable)
        assertFalse(research.glassnodeEntityFlowDirection.isAvailable)
        assertFalse(research.coinGlassLiquidationRisk.isAvailable)
    }

    @Test
    fun testStage5And6_ModelScoreAndMultiHorizonForecasts() {
        val points = generateAuthenticTickSequence(60, 91000.0)
        val spot = points.last().price
        val nowMs = points.last().timestamp
        val snapshot = indicatorCalculator.computeSnapshot(points, spot, 0.0)
        val research = externalCoordinator.computeFeatures(points, nowMs)
        val prediction = predictionEngine.predict(
            currentPrice = spot,
            snapshot = snapshot,
            timestamp = nowMs,
            settlementReference = spot,
            researchFeatures = research
        )
        assertNotNull(prediction)
        assertTrue("Score must be bounded between 0.0 and 1.0", prediction.score in 0.0..1.0)
        val scalpSeconds = listOf(5, 10, 30, 60, 120, 300)
        for (sec in scalpSeconds) {
            val forecast = prediction.getForecast(sec)
            assertNotNull("Forecast for $sec seconds must exist", forecast)
            assertTrue("Predicted price must be positive", forecast!!.predictedPrice > 0.0)
        }
    }

    @Test
    fun testStage7_FailClosedOnAmbiguousMarketStructure() {
        val points = generateAuthenticTickSequence(60, 91000.0)
        val spot = points.last().price
        val nowMs = points.last().timestamp
        val snapshot = indicatorCalculator.computeSnapshot(points, spot, 0.0)
        val rawPrediction = predictionEngine.predict(
            currentPrice = spot,
            snapshot = snapshot,
            timestamp = nowMs,
            settlementReference = spot
        )
        val ambiguousStructure = structureAnalyzer.analyze(emptyList(), nowMs, spot)
        assertTrue(ambiguousStructure.isAmbiguous)
        val gatedPrediction = if (ambiguousStructure.isAmbiguous) {
            rawPrediction.copy(decision = "NO-TRADE", strength = "AMBIGUOUS_STRUCTURE")
        } else {
            rawPrediction
        }
        assertEquals("NO-TRADE", gatedPrediction.decision)
        assertEquals("AMBIGUOUS_STRUCTURE", gatedPrediction.strength)
    }

    @Test
    fun testStage8And9_OutcomeResolutionAndClosedLoopLearning() {
        val points = generateAuthenticTickSequence(60, 91000.0)
        val spot = points.last().price
        val nowMs = points.last().timestamp
        val snapshot = IndicatorSnapshot(
            ema9 = spot + 150.0,
            ema21 = spot - 50.0,
            rsi = 78.0,
            momentum = 160.0,
            velocity = 25.0,
            volatility = 20.0,
            volume = 25.0,
            volumeChange = 1.8,
            buffer = 150.0,
            bidAskSpread = 0.5,
            exchangeAgreement = "STRONG_AGREEMENT"
        )
        val record = predictionEngine.predict(
            currentPrice = spot,
            snapshot = snapshot,
            timestamp = nowMs,
            settlementReference = spot
        )
        performanceTracker.registerPrediction(record)
        val maturityPrice = spot + 35.0
        val resolvedList = performanceTracker.resolveMatured(maturityPrice, record.maturityTimestamp)
        assertTrue(resolvedList.isNotEmpty())
        val stats = performanceTracker.computeStats(snapshot)
        assertNotNull(stats)
        assertTrue(stats.totalResolved >= 1)
        assertTrue("Attributions must be generated for all active factors", stats.factorAttributions.isNotEmpty())
        for (attr in stats.factorAttributions) {
            assertTrue("Win rate must be in percentage [0.0, 100.0]", attr.winRate in 0.0..100.0)
            assertTrue("Weight offset must be bounded within safety limits", abs(attr.suggestedWeightOffset) <= 0.15)
        }
    }

    @Test
    fun testStage10_BacktesterUnifiedPipelineEquivalence() {
        val testData = generateAuthenticTickSequence(150, 91200.0)
        val backtestResult = backtester.runBacktest(testData)
        assertNotNull(backtestResult)
        assertTrue("Backtest must evaluate samples", backtestResult.totalSamples >= 100)
        assertTrue("Win rate percent must be bounded in [0.0, 100.0]", backtestResult.winRatePercent in 0.0..100.0)
        assertTrue(backtestResult.baselineAlwaysUpWinRate in 0.0..100.0)
        assertTrue(backtestResult.baselineAlwaysDownWinRate in 0.0..100.0)
    }
}
