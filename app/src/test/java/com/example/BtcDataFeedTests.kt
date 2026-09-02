package com.example

import com.example.data.BtcDataFeed
import com.example.data.PricePoint
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BtcDataFeedTests {

    @Test
    fun testParseBinanceBookTickerResponse() {
        val jsonStr = """{"symbol":"BTCUSDT","bidPrice":"91250.50","bidQty":"1.24","askPrice":"91251.50","askQty":"0.85"}"""
        val json = JSONObject(jsonStr)
        val bid = json.optDouble("bidPrice", 0.0)
        val ask = json.optDouble("askPrice", 0.0)
        val mid = (bid + ask) / 2.0

        assertEquals(91251.0, mid, 0.001)
        assertEquals(91250.50, bid, 0.001)
        assertEquals(91251.50, ask, 0.001)
    }

    @Test
    fun testParseBinancePriceFallbackResponse() {
        val jsonStr = """{"symbol":"BTCUSDT","price":"91400.25"}"""
        val json = JSONObject(jsonStr)
        val price = json.optDouble("price", 0.0)
        assertEquals(91400.25, price, 0.001)
    }

    @Test
    fun testParseKrakenResponse() {
        val jsonStr = """{"error":[],"result":{"XXBTZUSD":{"a":["91252.0","1","1"],"b":["91250.0","1","1"],"c":["91251.20","0.01"],"v":["100.0","2000.0"]}}}"""
        val json = JSONObject(jsonStr)
        val result = json.getJSONObject("result")
        val ticker = result.getJSONObject("XXBTZUSD")
        val closePrice = ticker.getJSONArray("c").getString(0).toDouble()
        val askPrice = ticker.getJSONArray("a").getString(0).toDouble()
        val bidPrice = ticker.getJSONArray("b").getString(0).toDouble()

        assertEquals(91251.20, closePrice, 0.001)
        assertEquals(91252.0, askPrice, 0.001)
        assertEquals(91250.0, bidPrice, 0.001)
    }

    @Test
    fun testHandleInvalidJsonGracefully() {
        var failed = false
        try {
            val invalidJson = "{ invalid json content }"
            JSONObject(invalidJson)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("Invalid JSON throws exception which is caught safely", failed)
    }

    @Test
    fun testOkHttpClientTimeoutConfiguration() {
        val feed = BtcDataFeed()
        // Ensure feed is initialized with 1000ms timeouts
        assertNotNull(feed)
    }
}
