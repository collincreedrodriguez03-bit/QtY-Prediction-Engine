package com.example

import com.example.data.ConnectionType
import com.example.data.DataSourceStatus
import com.example.data.ExchangeAgreementStatus
import com.example.data.FeedState
import com.example.data.MarketDataConsolidator
import com.example.data.PricePoint
import com.example.data.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarketDataConsolidatorTests {

    private lateinit var consolidator: MarketDataConsolidator

    @Before
    fun setup() {
        consolidator = MarketDataConsolidator(maxAgeMillis = 5000L, decayLambda = 0.4)
    }

    @Test
    fun testConsolidateMultipleConformingFeeds() {
        val now = System.currentTimeMillis()
        val binance = PricePoint(price = 91250.0, timestamp = now, exchange = "BINANCE", volume = 2.0)
        val coinbase = PricePoint(price = 91252.0, timestamp = now - 500, exchange = "COINBASE", volume = 1.5)
        val kraken = PricePoint(price = 91249.0, timestamp = now - 1000, exchange = "KRAKEN", volume = 1.0)

        val result = consolidator.consolidate(listOf(binance, coinbase, kraken), now)

        assertTrue("Consolidated price should be between min and max feeds", result.consolidatedPrice in 91249.0..91252.0)
        assertEquals(3, result.activeSpotFeeds.size)
        assertEquals(3, result.sourceProvenance.size)
        assertEquals(ExchangeAgreementStatus.STRONG_AGREEMENT, result.agreementStatus)
        assertTrue(result.divergencePercent < 0.05)
        assertTrue(result.consolidationFormula.contains("P_cons"))
    }

    @Test
    fun testConsolidateSingleFeed() {
        val now = System.currentTimeMillis()
        val binance = PricePoint(price = 91500.0, timestamp = now, exchange = "BINANCE", volume = 1.0)

        val result = consolidator.consolidate(listOf(binance), now)

        assertEquals(91500.0, result.consolidatedPrice, 0.01)
        assertEquals(1, result.activeSpotFeeds.size)
        assertEquals(ExchangeAgreementStatus.SINGLE_EXCHANGE, result.agreementStatus)
        assertEquals(0.0, result.divergencePercent, 0.001)
    }

    @Test
    fun testFilterStaleFeeds() {
        val now = System.currentTimeMillis()
        val freshFeed = PricePoint(price = 91300.0, timestamp = now, exchange = "BINANCE", volume = 1.0)
        val staleFeed = PricePoint(price = 90000.0, timestamp = now - 10000L, exchange = "KRAKEN", volume = 1.0) // 10s old > 5s maxAge

        val result = consolidator.consolidate(listOf(freshFeed, staleFeed), now)

        assertEquals(1, result.activeSpotFeeds.size)
        assertEquals(91300.0, result.consolidatedPrice, 0.01)
        assertFalse("Stale feed should not be included in provenance", result.sourceProvenance.containsKey("KRAKEN"))
    }

    @Test
    fun testDivergentFeedsDetection() {
        val now = System.currentTimeMillis()
        val binance = PricePoint(price = 90000.0, timestamp = now, exchange = "BINANCE", volume = 1.0)
        val kraken = PricePoint(price = 91000.0, timestamp = now, exchange = "KRAKEN", volume = 1.0) // > 1% divergence

        val result = consolidator.consolidate(listOf(binance, kraken), now)

        assertEquals(ExchangeAgreementStatus.DISAGREEMENT, result.agreementStatus)
        assertTrue(result.divergencePercent > 0.5)
    }

    @Test
    fun testEmptyFeedsHandling() {
        val now = System.currentTimeMillis()
        val result = consolidator.consolidate(emptyList(), now)

        assertEquals(0.0, result.consolidatedPrice, 0.001)
        assertEquals(0, result.activeSpotFeeds.size)
        assertEquals(ExchangeAgreementStatus.DISAGREEMENT, result.agreementStatus)
    }

    @Test
    fun testDataSourceStatusAttributes() {
        val status = DataSourceStatus(
            sourceId = "BINANCE",
            displayName = "Binance",
            sourceType = SourceType.BTC_SPOT,
            connectionType = ConnectionType.WEBSOCKET,
            feedState = FeedState.STREAMING,
            lastUpdateTimestamp = System.currentTimeMillis() - 1200L,
            latestPrice = 91400.0,
            rateLimitInfo = "Unlimited WS"
        )

        assertEquals("Binance", status.displayName)
        assertEquals(SourceType.BTC_SPOT, status.sourceType)
        assertEquals(ConnectionType.WEBSOCKET, status.connectionType)
        assertEquals(FeedState.STREAMING, status.feedState)
        assertTrue("Data age should be ~1.2s", status.dataAgeSeconds in 1.0..2.0)
        assertNotNull(status.formattedAge)
    }

    @Test
    fun testQuoteIsolationPrefersUsdOverUsdt() {
        val now = System.currentTimeMillis()
        val krakenUsd = PricePoint(price = 90000.0, timestamp = now, exchange = "KRAKEN", volume = 2.0, quoteCurrency = "USD")
        val coinbaseUsd = PricePoint(price = 90010.0, timestamp = now, exchange = "COINBASE", volume = 2.0, quoteCurrency = "USD")
        val binanceUsdt = PricePoint(price = 90500.0, timestamp = now, exchange = "BINANCE", volume = 5.0, quoteCurrency = "USDT")

        val result = consolidator.consolidate(listOf(krakenUsd, coinbaseUsd, binanceUsdt), now)

        // When USD feeds are available, USDT feeds must be isolated and not included in active consolidation
        assertEquals(2, result.activeSpotFeeds.size)
        assertTrue(result.sourceProvenance.containsKey("KRAKEN"))
        assertTrue(result.sourceProvenance.containsKey("COINBASE"))
        assertFalse(result.sourceProvenance.containsKey("BINANCE"))
        assertTrue(result.consolidatedPrice in 90000.0..90010.0)
    }

    @Test
    fun testAllFeedsStaleFailsClosed() {
        val now = System.currentTimeMillis()
        val staleKraken = PricePoint(price = 90000.0, timestamp = now - 15000L, exchange = "KRAKEN", volume = 1.0)
        val staleCoinbase = PricePoint(price = 90010.0, timestamp = now - 12000L, exchange = "COINBASE", volume = 1.0)

        val result = consolidator.consolidate(listOf(staleKraken, staleCoinbase), now)

        assertEquals(0.0, result.consolidatedPrice, 0.001)
        assertEquals(0, result.activeSpotFeeds.size)
        assertEquals(ExchangeAgreementStatus.DISAGREEMENT, result.agreementStatus)
    }

    @Test
    fun testBitstampConsolidationWithOtherExchanges() {
        val now = System.currentTimeMillis()
        val kraken = PricePoint(price = 91000.0, timestamp = now, exchange = "KRAKEN", volume = 2.0, quoteCurrency = "USD")
        val bitstamp = PricePoint(price = 91005.0, timestamp = now, exchange = "BITSTAMP", volume = 1.0, quoteCurrency = "USD")

        val result = consolidator.consolidate(listOf(kraken, bitstamp), now)

        assertEquals(2, result.activeSpotFeeds.size)
        assertTrue(result.sourceProvenance.containsKey("KRAKEN"))
        assertTrue(result.sourceProvenance.containsKey("BITSTAMP"))
        assertTrue(result.consolidatedPrice in 91000.0..91005.0)
    }
}
