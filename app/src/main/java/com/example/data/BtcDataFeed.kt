package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Multi-Exchange Data Feed for BTC with 1-second timeout per request.
 * Prioritizes Binance, validates with Kraken and Bitstamp.
 */
class BtcDataFeed(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1000, TimeUnit.MILLISECONDS)
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .writeTimeout(1000, TimeUnit.MILLISECONDS)
        .build()
) {
    companion object {
        private const val TAG = "QtY_BtcDataFeed"
        const val BINANCE_URL = "https://api.binance.com/api/v3/ticker/bookTicker?symbol=BTCUSDT"
        const val BINANCE_FALLBACK_URL = "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"
        const val KRAKEN_URL = "https://api.kraken.com/0/public/Ticker?pair=XBTUSDT"
        const val BITSTAMP_URL = "https://www.bitstamp.net/api/v2/ticker/btcusd/"
    }

    /**
     * Fetches primary BTC price from Binance with millisecond timestamp.
     */
    suspend fun fetchBinancePrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            // First try bookTicker for bid/ask spread precision
            val request = Request.Builder().url(BINANCE_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val bid = json.optDouble("bidPrice", 0.0)
                        val ask = json.optDouble("askPrice", 0.0)
                        val bidQty = json.optDouble("bidQty", 0.0)
                        val askQty = json.optDouble("askQty", 0.0)
                        val midPrice = if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else 0.0

                        if (midPrice > 0.0) {
                            return@withContext PricePoint(
                                price = midPrice,
                                timestamp = now,
                                exchange = "BINANCE",
                                volume = bidQty + askQty,
                                bidPrice = bid,
                                askPrice = ask
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Binance bookTicker failed: ${e.message}, trying fallback price endpoint")
        }

        // Fallback to simple price endpoint
        try {
            val fallbackRequest = Request.Builder().url(BINANCE_FALLBACK_URL).build()
            client.newCall(fallbackRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val priceStr = json.optString("price", "")
                        val price = priceStr.toDoubleOrNull() ?: json.optDouble("price", 0.0)
                        if (price > 0.0) {
                            return@withContext PricePoint(
                                price = price,
                                timestamp = now,
                                exchange = "BINANCE",
                                volume = 1.0,
                                bidPrice = price - 0.5,
                                askPrice = price + 0.5
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Binance price fetch error: ${e.message}")
        }
        null
    }

    /**
     * Fetches validation BTC price from Kraken.
     */
    suspend fun fetchKrakenPrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(KRAKEN_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val result = json.optJSONObject("result")
                        if (result != null) {
                            // Key might be XBTUSDT or XXBTZUSD
                            val tickerKey = result.keys().asSequence().firstOrNull()
                            if (tickerKey != null) {
                                val ticker = result.getJSONObject(tickerKey)
                                val closeArr = ticker.optJSONArray("c")
                                val askArr = ticker.optJSONArray("a")
                                val bidArr = ticker.optJSONArray("b")
                                val volArr = ticker.optJSONArray("v")

                                val price = closeArr?.optString(0)?.toDoubleOrNull() ?: 0.0
                                val ask = askArr?.optString(0)?.toDoubleOrNull() ?: price
                                val bid = bidArr?.optString(0)?.toDoubleOrNull() ?: price
                                val volume = volArr?.optString(1)?.toDoubleOrNull() ?: 0.0

                                if (price > 0.0) {
                                    return@withContext PricePoint(
                                        price = price,
                                        timestamp = now,
                                        exchange = "KRAKEN",
                                        volume = volume,
                                        bidPrice = bid,
                                        askPrice = ask
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kraken price fetch error: ${e.message}")
        }
        null
    }

    /**
     * Fetches optional validation BTC price from Bitstamp.
     */
    suspend fun fetchBitstampPrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(BITSTAMP_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val price = json.optString("last").toDoubleOrNull() ?: 0.0
                        val ask = json.optString("ask").toDoubleOrNull() ?: price
                        val bid = json.optString("bid").toDoubleOrNull() ?: price
                        val vol = json.optString("volume").toDoubleOrNull() ?: 0.0

                        if (price > 0.0) {
                            return@withContext PricePoint(
                                price = price,
                                timestamp = now,
                                exchange = "BITSTAMP",
                                volume = vol,
                                bidPrice = bid,
                                askPrice = ask
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bitstamp price fetch error: ${e.message}")
        }
        null
    }
}
