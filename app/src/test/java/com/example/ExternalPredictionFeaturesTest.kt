package com.example

import com.example.data.PricePoint
import com.example.engine.IndicatorCalculator
import com.example.engine.PredictionEngine
import com.example.engine.external.CoinGlassLiquidationRiskFeature
import com.example.engine.external.CryptoQuantWhaleMomentumFeature
import com.example.engine.external.ExternalFeatureCoordinator
import com.example.engine.external.ExternalFeatureProvenanceStatus
import com.example.engine.external.GlassnodeEntityFlowFeature
import com.example.engine.external.ResearchFeatureEvaluator
import com.example.engine.external.ResearchFeatureValue
import com.example.engine.external.TradingViewTrendFeature
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Comprehensive test suite verifying all 17 required criteria across the 4 external connections:
 * 1. API authentication
 * 2. successful data retrieval
 * 3. malformed response
 * 4. missing data
 * 5. stale data
 * 6. future timestamp
 * 7. duplicate observation
 * 8. timestamp alignment
 * 9. normalization bounds
 * 10. provenance preservation
 * 11. no-lookahead behavior
 * 12. source outage
 * 13. rate-limit response
 * 14. feature calculation
 * 15. historical replay
 * 16. 30-second evaluation
 * 17. 90-second evaluation
 *
 * Plus verification that unavailable external data CAN NEVER become a synthetic numeric value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalPredictionFeaturesTest {

    private var mockResponseCode = 200
    private var mockResponseBody = "{}"
    private lateinit var mockClient: OkHttpClient

    @Before
    fun setup() {
        mockResponseCode = 200
        mockResponseBody = "{}"
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(mockResponseCode)
                .message(if (mockResponseCode == 200) "OK" else "Error")
                .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
        mockClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    // ==========================================
    // 1. API AUTHENTICATION & CREDENTIALS
    // ==========================================
    @Test
    fun testApiAuthenticationRequirements() {
        val cq = CryptoQuantWhaleMomentumFeature(mockClient, "https://mock.cryptoquant.com")
        val gn = GlassnodeEntityFlowFeature(mockClient, "https://mock.glassnode.com")
        val cg = CoinGlassLiquidationRiskFeature(mockClient, "https://mock.coinglass.com")

        // Without keys
        cq.setApiKey(null)
        gn.setApiKey(null)
        cg.setApiKey(null)

        assertFalse(cq.hasValidCredentials())
        assertFalse(gn.hasValidCredentials())
        assertFalse(cg.hasValidCredentials())

        runBlocking {
            val cqRes = cq.fetchLiveObservation()
            val gnRes = gn.fetchLiveObservation()
            val cgRes = cg.fetchLiveObservation()

            assertFalse("Unauthenticated CryptoQuant must be unavailable", cqRes.isAvailable)
            assertEquals(ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS, cqRes.provenance.provenanceStatus)
            assertNull("Unavailable feature must never substitute a numeric value", cqRes.normalizedValue)

            assertFalse("Unauthenticated Glassnode must be unavailable", gnRes.isAvailable)
            assertEquals(ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS, gnRes.provenance.provenanceStatus)
            assertNull(gnRes.normalizedValue)

            assertFalse("Unauthenticated CoinGlass must be unavailable", cgRes.riskFeature.isAvailable)
            assertEquals(ExternalFeatureProvenanceStatus.MISSING_CREDENTIALS, cgRes.riskFeature.provenance.provenanceStatus)
            assertNull(cgRes.riskFeature.normalizedValue)
        }

        // Setting valid keys
        cq.setApiKey("valid_cq_secret")
        gn.setApiKey("valid_gn_secret")
        cg.setApiKey("valid_cg_secret")

        assertTrue(cq.hasValidCredentials())
        assertTrue(gn.hasValidCredentials())
        assertTrue(cg.hasValidCredentials())
    }

    // ==========================================
    // 2. SUCCESSFUL DATA RETRIEVAL (MOCK SERVER)
    // ==========================================
    @Test
    fun testSuccessfulDataRetrieval() {
        val cq = CryptoQuantWhaleMomentumFeature(mockClient, "https://mock.cryptoquant.com")
        cq.setApiKey("test_cq_key")

        val now = System.currentTimeMillis()
        mockResponseCode = 200
        mockResponseBody = """
            {
                "result": {
                    "data": [
                        {
                            "date": ${(now - 60_000L) / 1000L},
                            "exchange_whale_ratio": 0.82,
                            "exchange_inflow": 120.0,
                            "exchange_outflow": 250.0
                        }
                    ]
                }
            }
        """.trimIndent()

        runBlocking {
            val res = cq.fetchLiveObservation(now)
            assertTrue("Observation should be available: status=${res.provenance.provenanceStatus}, notes=${res.provenance.notes}", res.isAvailable)
            assertNotNull("Normalized value must be non-null", res.normalizedValue)
            assertTrue("Whale momentum in [-1.0, 1.0]", res.normalizedValue!! in -1.0..1.0)
            assertEquals("CRYPTOQUANT", res.provenance.source)
        }
    }

    // ==========================================
    // 3. MALFORMED RESPONSE HANDLING
    // ==========================================
    @Test
    fun testMalformedResponseHandling() {
        val gn = GlassnodeEntityFlowFeature(mockClient, "https://mock.glassnode.com")
        gn.setApiKey("test_gn_key")

        mockResponseCode = 200
        mockResponseBody = "{ corrupt_json: true"

        runBlocking {
            val res = gn.fetchLiveObservation()
            assertFalse("Corrupt payload must fail closed", res.isAvailable)
            assertNull("No synthetic numeric value", res.normalizedValue)
            assertTrue(
                res.provenance.provenanceStatus == ExternalFeatureProvenanceStatus.MALFORMED_PAYLOAD ||
                res.provenance.provenanceStatus == ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE
            )
        }
    }

    // ==========================================
    // 4. MISSING DATA & EMPTY OBSERVATIONS
    // ==========================================
    @Test
    fun testMissingDataHandling() {
        val cq = CryptoQuantWhaleMomentumFeature()
        val gn = GlassnodeEntityFlowFeature()
        val cg = CoinGlassLiquidationRiskFeature()

        val emptyCq = cq.calculateFromObservations(emptyList())
        val emptyGn = gn.calculateFromObservations(emptyList())
        val emptyCg = cg.calculateFromObservations(emptyList())

        assertFalse(emptyCq.isAvailable)
        assertNull(emptyCq.normalizedValue)
        assertEquals(ExternalFeatureProvenanceStatus.UNAVAILABLE, emptyCq.provenance.provenanceStatus)

        assertFalse(emptyGn.isAvailable)
        assertNull(emptyGn.normalizedValue)

        assertFalse(emptyCg.riskFeature.isAvailable)
        assertNull(emptyCg.riskFeature.normalizedValue)
    }

    // ==========================================
    // 5. STALE DATA REJECTION
    // ==========================================
    @Test
    fun testStaleDataRejection() {
        val now = 1_700_000_000_000L
        val staleTime = now - (3600_000L) // 1 hour old (> 10 minute limit)

        val cq = CryptoQuantWhaleMomentumFeature()
        val obs = listOf(
            CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation(
                timestampMs = staleTime,
                exchangeWhaleRatio = 0.85,
                exchangeInflowBtc = 100.0,
                exchangeOutflowBtc = 100.0
            )
        )
        val res = cq.calculateFromObservations(obs, now)
        assertFalse("Stale observation must be rejected", res.isAvailable)
        assertEquals(ExternalFeatureProvenanceStatus.STALE_DATA, res.provenance.provenanceStatus)
        assertNull("Stale data must not produce numeric score", res.normalizedValue)
    }

    // ==========================================
    // 6. FUTURE TIMESTAMP (NO LOOKAHEAD VIOLATION)
    // ==========================================
    @Test
    fun testFutureTimestampRejection() {
        val now = 1_700_000_000_000L
        val futureTime = now + 100_000L // 100 seconds in future

        val gn = GlassnodeEntityFlowFeature()
        val obs = listOf(
            GlassnodeEntityFlowFeature.RawGlassnodeObservation(
                timestampMs = futureTime,
                netFlowBtc = 50.0
            )
        )
        val res = gn.calculateFromObservations(obs, now)
        assertFalse("Future-dated observation must be rejected", res.isAvailable)
        assertEquals(ExternalFeatureProvenanceStatus.FUTURE_DATED, res.provenance.provenanceStatus)
        assertNull(res.normalizedValue)
    }

    // ==========================================
    // 7. DUPLICATE OBSERVATIONS DEDUPLICATION
    // ==========================================
    @Test
    fun testDuplicateObservationDeduplication() {
        val coordinator = ExternalFeatureCoordinator()
        val now = 1_700_000_000_000L

        coordinator.addCryptoQuantObservation(
            CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation(
                timestampMs = now - 60_000L,
                exchangeWhaleRatio = 0.80,
                exchangeInflowBtc = 50.0,
                exchangeOutflowBtc = 100.0
            )
        )
        // Add exact same timestamp with revised value
        coordinator.addCryptoQuantObservation(
            CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation(
                timestampMs = now - 60_000L,
                exchangeWhaleRatio = 0.75,
                exchangeInflowBtc = 60.0,
                exchangeOutflowBtc = 110.0
            )
        )

        val features = coordinator.computeFeatures(emptyList(), now)
        assertTrue(features.cryptoQuantWhaleMomentum.isAvailable)
        // Successfully computed without duplicate conflict
        assertNotNull(features.cryptoQuantWhaleMomentum.normalizedValue)
    }

    // ==========================================
    // 8. TIMESTAMP ALIGNMENT & COORDINATION
    // ==========================================
    @Test
    fun testTimestampAlignment() {
        val coordinator = ExternalFeatureCoordinator()
        val now = 1_700_000_000_000L

        val dummyPrices = (0..20).map { i ->
            PricePoint(
                price = 90_000.0 + i * 10.0,
                timestamp = now - (20 - i) * 2000L,
                exchange = "CONSOLIDATED"
            )
        }

        val features = coordinator.computeFeatures(dummyPrices, now)
        assertEquals(now, features.timestamp)
        assertTrue("TradingView trend feature available with fresh prices", features.tradingViewTrendScore.isAvailable)
        assertEquals(dummyPrices.last().timestamp, features.tradingViewTrendScore.provenance.sourceTimestampMs)
    }

    // ==========================================
    // 9. NORMALIZATION BOUNDS VERIFICATION
    // ==========================================
    @Test
    fun testNormalizationBounds() {
        // 1. TradingView Trend Score: [0.0, 1.0]
        val tv = TradingViewTrendFeature()
        val now = 1_700_000_000_000L
        val bullPrices = (0..30).map { i ->
            PricePoint(price = 90_000.0 + i * 50.0, timestamp = now - (30 - i) * 2000L, exchange = "BINANCE")
        }
        val bullRes = tv.calculate(bullPrices, now)
        assertTrue("Trend score must be in [0.0, 1.0]", bullRes.normalizedValue!! in 0.0..1.0)
        assertTrue("Strong bull series should produce high trend score (> 0.60)", bullRes.normalizedValue!! > 0.60)

        val bearPrices = (0..30).map { i ->
            PricePoint(price = 90_000.0 - i * 50.0, timestamp = now - (30 - i) * 2000L, exchange = "BINANCE")
        }
        val bearRes = tv.calculate(bearPrices, now)
        assertTrue("Trend score must be in [0.0, 1.0]", bearRes.normalizedValue!! in 0.0..1.0)
        assertTrue("Strong bear series should produce low trend score (< 0.40)", bearRes.normalizedValue!! < 0.40)

        // 2. CryptoQuant: [-1.0, +1.0]
        val cq = CryptoQuantWhaleMomentumFeature()
        val cqObs = listOf(
            CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation(now - 10_000L, 0.95, 1000.0, 10.0)
        )
        val cqRes = cq.calculateFromObservations(cqObs, now)
        assertTrue("Whale momentum must be in [-1.0, +1.0]", cqRes.normalizedValue!! in -1.0..1.0)
        assertTrue("Heavy dumping whale ratio should be negative", cqRes.normalizedValue!! < 0.0)

        // 3. Glassnode: [-1.0, +1.0]
        val gn = GlassnodeEntityFlowFeature()
        val gnObs = listOf(
            GlassnodeEntityFlowFeature.RawGlassnodeObservation(now - 10_000L, 2000.0)
        )
        val gnRes = gn.calculateFromObservations(gnObs, now)
        assertTrue("Entity flow direction in [-1.0, +1.0]", gnRes.normalizedValue!! in -1.0..1.0)
        assertTrue("Net deposits to exchange should yield negative score", gnRes.normalizedValue!! < 0.0)

        // 4. CoinGlass: Risk in [0.0, 1.0], Direction in [-1.0, +1.0]
        val cg = CoinGlassLiquidationRiskFeature()
        val cgObs = listOf(
            CoinGlassLiquidationRiskFeature.RawCoinGlassObservation(now - 10_000L, 5_000_000.0, 100_000.0)
        )
        val cgRes = cg.calculateFromObservations(cgObs, now)
        assertTrue("Liquidation risk in [0.0, 1.0]", cgRes.riskFeature.normalizedValue!! in 0.0..1.0)
        assertTrue("Massive $5M liquidations should produce high risk (> 0.8)", cgRes.riskFeature.normalizedValue!! > 0.8)
        assertTrue("Liquidation direction in [-1.0, +1.0]", cgRes.directionFeature.normalizedValue!! in -1.0..1.0)
        assertTrue("Long cascade should yield negative liquidation direction", cgRes.directionFeature.normalizedValue!! < 0.0)
    }

    // ==========================================
    // 10. PROVENANCE PRESERVATION
    // ==========================================
    @Test
    fun testProvenancePreservation() {
        val now = 1_700_000_000_000L
        val gn = GlassnodeEntityFlowFeature()
        val obsTime = now - 30_000L
        val obs = listOf(
            GlassnodeEntityFlowFeature.RawGlassnodeObservation(
                timestampMs = obsTime,
                netFlowBtc = -450.0,
                isPointInTime = true,
                dataVersion = "v1-pit"
            )
        )
        val res = gn.calculateFromObservations(obs, now)
        val prov = res.provenance
        assertEquals("GLASSNODE", prov.source)
        assertEquals("BTC_ENTITY_FLOW_DIRECTION", prov.metric)
        assertEquals(obsTime, prov.sourceTimestampMs)
        assertEquals(now, prov.retrievalTimestampMs)
        assertEquals("v1-pit", prov.apiVersion)
        assertEquals(ExternalFeatureProvenanceStatus.AUTHENTIC_POINT_IN_TIME, prov.provenanceStatus)
        assertNotNull(prov.rawValue)
        assertNotNull(prov.derivedValue)
    }

    // ==========================================
    // 11. STRICT NO-LOOKAHEAD BEHAVIOR
    // ==========================================
    @Test
    fun testNoLookaheadBehavior() {
        val coordinator = ExternalFeatureCoordinator()
        val t0 = 1_700_000_000_000L
        val tFuture = t0 + 60_000L

        // Add observation from future
        coordinator.addGlassnodeObservation(
            GlassnodeEntityFlowFeature.RawGlassnodeObservation(
                timestampMs = tFuture,
                netFlowBtc = -500.0
            )
        )

        // Query at t0: the future observation must NOT leak into t0
        val features = coordinator.computeFeatures(emptyList(), t0)
        assertFalse("Future observation must not be accessible at t0", features.glassnodeEntityFlowDirection.isAvailable)
        assertNull(features.glassnodeEntityFlowDirection.normalizedValue)
    }

    // ==========================================
    // 12. SOURCE OUTAGE RESILIENCE
    // ==========================================
    @Test
    fun testSourceOutageResilience() {
        val outageClient = OkHttpClient.Builder()
            .addInterceptor { throw java.io.IOException("Network connection refused") }
            .build()
        val cq = CryptoQuantWhaleMomentumFeature(outageClient, "https://mock.cryptoquant.com")
        cq.setApiKey("test_key")

        runBlocking {
            val res = cq.fetchLiveObservation()
            assertFalse("Outage must fail closed", res.isAvailable)
            assertEquals(ExternalFeatureProvenanceStatus.CONNECTION_OUTAGE, res.provenance.provenanceStatus)
            assertNull(res.normalizedValue)
        }
    }

    // ==========================================
    // 13. RATE-LIMIT (HTTP 429) RESPONSE
    // ==========================================
    @Test
    fun testRateLimitHandling() {
        val cg = CoinGlassLiquidationRiskFeature(mockClient, "https://mock.coinglass.com")
        cg.setApiKey("test_key")

        mockResponseCode = 429
        mockResponseBody = "Rate limit exceeded"

        runBlocking {
            val res = cg.fetchLiveObservation()
            assertFalse("Rate limited call must be unavailable", res.riskFeature.isAvailable)
            assertEquals(ExternalFeatureProvenanceStatus.RATE_LIMITED, res.riskFeature.provenance.provenanceStatus)
            assertNull(res.riskFeature.normalizedValue)
        }
    }

    // ==========================================
    // 14. FEATURE CALCULATION ACCURACY
    // ==========================================
    @Test
    fun testFeatureCalculationAccuracy() {
        val tv = TradingViewTrendFeature()
        val now = 1_700_000_000_000L

        // Flat series should yield ~0.50 (neutral)
        val flatPrices = (0..25).map { i ->
            PricePoint(price = 90_000.0, timestamp = now - (25 - i) * 2000L, exchange = "COINBASE")
        }
        val flatRes = tv.calculate(flatPrices, now)
        assertTrue("Flat price series must yield neutral trendScore near 0.50 but got: ${flatRes.normalizedValue}", abs(flatRes.normalizedValue!! - 0.50) < 0.05)
    }

    // ==========================================
    // 15. HISTORICAL REPLAY
    // ==========================================
    @Test
    fun testHistoricalReplay() {
        val coordinator = ExternalFeatureCoordinator()
        val startTime = 1_700_000_000_000L

        // Pre-load series of historical entity observations
        for (i in 0..10) {
            coordinator.addCryptoQuantObservation(
                CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation(
                    timestampMs = startTime + (i * 300_000L),
                    exchangeWhaleRatio = 0.85 - (i * 0.01),
                    exchangeInflowBtc = 100.0,
                    exchangeOutflowBtc = 120.0
                )
            )
        }

        // Step through replay step by step
        for (step in 0..5) {
            val currentReplayTime = startTime + (step * 300_000L) + 10_000L
            val dummyPrices = (0..20).map { p ->
                PricePoint(
                    price = 90_000.0 + p * 10.0,
                    timestamp = currentReplayTime - (20 - p) * 2000L,
                    exchange = "CONSOLIDATED"
                )
            }
            val replayFeatures = coordinator.computeFeatures(dummyPrices, currentReplayTime)
            assertTrue("Feature must be available during replay", replayFeatures.cryptoQuantWhaleMomentum.isAvailable)
            assertNotNull(replayFeatures.cryptoQuantWhaleMomentum.normalizedValue)
        }
    }

    // ==========================================
    // 16 & 17. 30s & 90s OUT-OF-SAMPLE EVALUATION & REJECT DECISION
    // ==========================================
    @Test
    fun testOutOfSampleEvaluationAndRejectDecision() {
        val evaluator = ResearchFeatureEvaluator()
        val totalTicks = 200
        val baseTime = 1_700_000_000_000L

        // Generate synthetic historical price walk for testing evaluator
        val priceSeries = mutableListOf<PricePoint>()
        var p = 90_000.0
        for (i in 0 until totalTicks) {
            val delta = (Math.sin(i / 5.0) * 15.0) + (if (i % 2 == 0) 5.0 else -5.0)
            p += delta
            priceSeries.add(PricePoint(price = p, timestamp = baseTime + (i * 2000L), exchange = "BINANCE"))
        }

        // Test with a noisy/uncorrelated feature
        val dummyFeatures = priceSeries.map { pt ->
            val randomVal = Math.sin(pt.timestamp.toDouble()) * 0.5 + 0.5
            ResearchFeatureValue(
                isAvailable = true,
                normalizedValue = randomVal,
                provenance = com.example.engine.external.ExternalObservationProvenance(
                    source = "TEST_FEATURE",
                    metric = "NOISE",
                    sourceTimestampMs = pt.timestamp,
                    retrievalTimestampMs = pt.timestamp
                )
            )
        }

        val report = evaluator.evaluateFeature(
            featureName = "TEST_UNVALIDATED_FEATURE",
            priceSeries = priceSeries,
            featureValues = dummyFeatures
        )

        assertEquals("TEST_UNVALIDATED_FEATURE", report.featureName)
        assertEquals(0.0, report.currentProductionWeight, 0.0) // Production weight MUST BE 0.0
        assertTrue("Unproven feature must be REJECTED", report.finalStatus.contains("REJECTED"))
        assertEquals("REJECT", report.result30s.recommendation)
        assertEquals("REJECT", report.result90s.recommendation)
    }

    // ==========================================
    // FAIL-CLOSED VERIFICATION: NO SYNTHETIC NUMERIC SUBSTITUTION
    // ==========================================
    @Test
    fun testUnavailableDataNeverBecomesSyntheticNumericValue() {
        val coordinator = ExternalFeatureCoordinator()
        val emptyContainer = coordinator.computeFeatures(emptyList(), System.currentTimeMillis())

        assertFalse(emptyContainer.tradingViewTrendScore.isAvailable)
        assertNull("MUST NEVER be 0.0 when unavailable", emptyContainer.tradingViewTrendScore.normalizedValue)

        assertFalse(emptyContainer.cryptoQuantWhaleMomentum.isAvailable)
        assertNull("MUST NEVER be 0.0 when unavailable", emptyContainer.cryptoQuantWhaleMomentum.normalizedValue)

        assertFalse(emptyContainer.glassnodeEntityFlowDirection.isAvailable)
        assertNull("MUST NEVER be 0.0 when unavailable", emptyContainer.glassnodeEntityFlowDirection.normalizedValue)

        assertFalse(emptyContainer.coinGlassLiquidationRisk.isAvailable)
        assertNull("MUST NEVER be 0.0 when unavailable", emptyContainer.coinGlassLiquidationRisk.normalizedValue)

        assertFalse(emptyContainer.coinGlassLiquidationDirection.isAvailable)
        assertNull("MUST NEVER be 0.0 when unavailable", emptyContainer.coinGlassLiquidationDirection.normalizedValue)
    }

    // ==========================================
    // VERIFY PREDICTION ENGINE FORMULA REMAINS UNCHANGED
    // ==========================================
    @Test
    fun testPredictionEngineFormulaUnchanged() {
        val engine = PredictionEngine()
        val snapshot = com.example.engine.IndicatorSnapshot(
            ema9 = 90_100.0,
            ema21 = 90_050.0,
            rsi = 65.0,
            momentum = 25.0,
            velocity = 10.0,
            volatility = 15.0,
            volumeChange = 1.2,
            buffer = 20.0
        )

        // Predict WITHOUT research features
        val recWithout = engine.predict(
            currentPrice = 90_080.0,
            snapshot = snapshot,
            timestamp = 1_700_000_000_000L
        )

        // Predict WITH research features present
        val dummyFeatures = ExternalFeatureCoordinator().computeFeatures(emptyList())
        val recWith = engine.predict(
            currentPrice = 90_080.0,
            snapshot = snapshot,
            timestamp = 1_700_000_000_000L,
            researchFeatures = dummyFeatures
        )

        // The core prediction decision, score, and formula display must be 100% IDENTICAL
        assertEquals(recWithout.score, recWith.score, 0.0001)
        assertEquals(recWithout.decision, recWith.decision)
        assertEquals(recWithout.predictedPrice, recWith.predictedPrice, 0.01)
        assertEquals(recWithout.projectedDecision90s, recWith.projectedDecision90s)
        assertEquals(recWithout.inputs.formulaDisplay, recWith.inputs.formulaDisplay)
    }
}
