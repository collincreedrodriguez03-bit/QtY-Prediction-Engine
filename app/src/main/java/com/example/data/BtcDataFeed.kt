package com.example.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Streamlined High-Performance Real-Time BTC Spot Market Data Feed.
 *
 * Connects directly to top tier spot cryptocurrency exchanges:
 * 1. Binance (BTC/USDT - WebSocket bookTicker + REST fallback)
 * 2. Coinbase (BTC/USD - WebSocket ticker + REST fallback)
 * 3. Kraken (XBT/USDT - WebSocket ticker + REST fallback)
 * 4. Bitstamp (BTC/USD - Verified Spot REST fallback)
 *
 * Tracks live tick counts, latencies, and provides stable spot points for 2-second engine cycles.
 */
class BtcDataFeed(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "QtY_BtcDataFeed"

        const val BINANCE_WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@bookTicker"
        const val BINANCE_REST_URL = "https://api.binance.com/api/v3/ticker/bookTicker?symbol=BTCUSDT"

        const val COINBASE_WS_URL = "wss://ws-feed.exchange.coinbase.com"
        const val COINBASE_REST_URL = "https://api.coinbase.com/v2/prices/BTC-USD/spot"

        const val KRAKEN_WS_URL = "wss://ws.kraken.com"
        const val KRAKEN_REST_URL = "https://api.kraken.com/0/public/Ticker?pair=XBTUSD"

        const val BITSTAMP_REST_URL = "https://www.bitstamp.net/api/v2/ticker/btcusd/"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var streamingJob: Job? = null

    // Latest raw PricePoints per exchange
    private val latestSpotPoints = ConcurrentHashMap<String, PricePoint>()

    // Separate Market Ticks (WS messages) from REST Fetches (polling)
    val binanceMarketTicks = AtomicLong(0L)
    val coinbaseMarketTicks = AtomicLong(0L)
    val krakenMarketTicks = AtomicLong(0L)
    val bitstampMarketTicks = AtomicLong(0L)

    val binanceRestFetches = AtomicLong(0L)
    val coinbaseRestFetches = AtomicLong(0L)
    val krakenRestFetches = AtomicLong(0L)
    val bitstampRestFetches = AtomicLong(0L)

    // Backwards compatibility references
    val binanceTickCount: AtomicLong get() = binanceMarketTicks
    val coinbaseTickCount: AtomicLong get() = coinbaseMarketTicks
    val krakenTickCount: AtomicLong get() = krakenMarketTicks
    val bitstampTickCount: AtomicLong get() = bitstampRestFetches

    // Hysteresis tracking: require 3 consecutive successful WS messages before leaving REST fallback
    private val consecutiveWsSuccess = ConcurrentHashMap<String, Int>()
    private val isRestFallbackActive = ConcurrentHashMap<String, Boolean>()

    // Factual Status Map for connected spot exchanges (initially DISCONNECTED until verified)
    private val _sourceStatuses = MutableStateFlow<Map<String, DataSourceStatus>>(
        mapOf(
            "BINANCE" to DataSourceStatus(
                sourceId = "BINANCE",
                displayName = "Binance Spot",
                sourceType = SourceType.BTC_SPOT,
                connectionType = ConnectionType.WEBSOCKET,
                feedState = FeedState.DISCONNECTED,
                rateLimitInfo = "Unlimited WS (bookTicker)"
            ),
            "COINBASE" to DataSourceStatus(
                sourceId = "COINBASE",
                displayName = "Coinbase Spot",
                sourceType = SourceType.BTC_SPOT,
                connectionType = ConnectionType.WEBSOCKET,
                feedState = FeedState.DISCONNECTED,
                rateLimitInfo = "Unlimited WS (ticker)"
            ),
            "KRAKEN" to DataSourceStatus(
                sourceId = "KRAKEN",
                displayName = "Kraken Spot",
                sourceType = SourceType.BTC_SPOT,
                connectionType = ConnectionType.WEBSOCKET,
                feedState = FeedState.DISCONNECTED,
                rateLimitInfo = "Unlimited WS (ticker)"
            ),
            "BITSTAMP" to DataSourceStatus(
                sourceId = "BITSTAMP",
                displayName = "Bitstamp Spot",
                sourceType = SourceType.BTC_SPOT,
                connectionType = ConnectionType.REST,
                feedState = FeedState.DISCONNECTED,
                rateLimitInfo = "8000 req/10min (REST)"
            )
        )
    )
    val sourceStatuses: StateFlow<Map<String, DataSourceStatus>> = _sourceStatuses.asStateFlow()

    private var binanceWs: WebSocket? = null
    private var coinbaseWs: WebSocket? = null
    private var krakenWs: WebSocket? = null

    /**
     * Starts background continuous streaming and maintenance loops.
     */
    fun startStreaming() {
        if (streamingJob?.isActive == true) return

        connectBinanceWs()
        connectCoinbaseWs()
        connectKrakenWs()

        streamingJob = scope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()

                    // Bitstamp REST polling: actively participates in the live consolidation path
                    fetchBitstampPrice()

                    // Fallback REST sync if any exchange WS is silent for > 4 seconds
                    val binanceAge = now - (_sourceStatuses.value["BINANCE"]?.lastUpdateTimestamp ?: 0L)
                    if (binanceAge > 4000L) {
                        isRestFallbackActive["BINANCE"] = true
                        consecutiveWsSuccess["BINANCE"] = 0
                        fetchBinancePrice()
                    }
                    val krakenAge = now - (_sourceStatuses.value["KRAKEN"]?.lastUpdateTimestamp ?: 0L)
                    if (krakenAge > 4000L) {
                        isRestFallbackActive["KRAKEN"] = true
                        consecutiveWsSuccess["KRAKEN"] = 0
                        fetchKrakenPrice()
                    }
                    val coinbaseAge = now - (_sourceStatuses.value["COINBASE"]?.lastUpdateTimestamp ?: 0L)
                    if (coinbaseAge > 4000L) {
                        isRestFallbackActive["COINBASE"] = true
                        consecutiveWsSuccess["COINBASE"] = 0
                        fetchCoinbasePrice()
                    }
                } catch (e: Exception) {
                    SafeLog.w(TAG, "Streaming maintenance error: ${e.message}")
                }
                delay(2000L)
            }
        }
    }

    /**
     * Stops background streaming.
     */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        binanceWs?.close(1000, "App paused")
        coinbaseWs?.close(1000, "App paused")
        krakenWs?.close(1000, "App paused")
        updateStatus("BINANCE", FeedState.DISCONNECTED, null, "Stopped", null, binanceMarketTicks.get())
        updateStatus("COINBASE", FeedState.DISCONNECTED, null, "Stopped", null, coinbaseMarketTicks.get())
        updateStatus("KRAKEN", FeedState.DISCONNECTED, null, "Stopped", null, krakenMarketTicks.get())
        updateStatus("BITSTAMP", FeedState.DISCONNECTED, null, "Stopped", null, bitstampRestFetches.get())
    }

    // ==========================================
    // 1. BINANCE STREAMING & REST
    // ==========================================

    private fun connectBinanceWs() {
        try {
            val request = Request.Builder().url(BINANCE_WS_URL).build()
            binanceWs = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    updateStatus("BINANCE", FeedState.STREAMING, null, "WS Connected (btcusdt)", null, binanceMarketTicks.get())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val bid = json.optDouble("b", 0.0)
                        val ask = json.optDouble("a", 0.0)
                        val bidQty = json.optDouble("B", 0.0)
                        val askQty = json.optDouble("A", 0.0)
                        val mid = if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else 0.0

                        if (mid > 0.0) {
                            val now = System.currentTimeMillis()
                            val count = binanceMarketTicks.incrementAndGet()
                            val succ = (consecutiveWsSuccess["BINANCE"] ?: 0) + 1
                            consecutiveWsSuccess["BINANCE"] = succ
                            if (succ >= 3) isRestFallbackActive["BINANCE"] = false

                            val pt = PricePoint(
                                price = mid,
                                timestamp = now,
                                exchange = "BINANCE",
                                volume = bidQty + askQty,
                                bidPrice = bid,
                                askPrice = ask,
                                quoteCurrency = "USDT",
                                baseVolumeBtc = bidQty + askQty,
                                quoteVolumeUsd = (bidQty + askQty) * mid,
                                exchangeTimestamp = now
                            )
                            latestSpotPoints["BINANCE"] = pt
                            updateStatus("BINANCE", FeedState.STREAMING, mid, "Bid: $bid | Ask: $ask", null, count)
                        }
                    } catch (e: Exception) {
                        SafeLog.w(TAG, "Binance WS parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    consecutiveWsSuccess["BINANCE"] = 0
                    isRestFallbackActive["BINANCE"] = true
                    updateStatus("BINANCE", FeedState.RECONNECTING, null, "WS Reconnecting", t.message, binanceMarketTicks.get())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    updateStatus("BINANCE", FeedState.DISCONNECTED, null, "WS Closed ($reason)", null, binanceMarketTicks.get())
                }
            })
        } catch (e: Exception) {
            SafeLog.w(TAG, "Binance WS connect error: ${e.message}")
        }
    }

    suspend fun fetchBinancePrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(BINANCE_REST_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val bid = json.optDouble("bidPrice", 0.0)
                        val ask = json.optDouble("askPrice", 0.0)
                        val bidQty = json.optDouble("bidQty", 0.0)
                        val askQty = json.optDouble("askQty", 0.0)
                        val mid = if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else 0.0

                        if (mid > 0.0) {
                            val count = binanceRestFetches.incrementAndGet()
                            val pt = PricePoint(
                                price = mid,
                                timestamp = now,
                                exchange = "BINANCE",
                                volume = bidQty + askQty,
                                bidPrice = bid,
                                askPrice = ask,
                                quoteCurrency = "USDT",
                                baseVolumeBtc = bidQty + askQty,
                                quoteVolumeUsd = (bidQty + askQty) * mid,
                                exchangeTimestamp = now
                            )
                            latestSpotPoints["BINANCE"] = pt
                            updateStatus("BINANCE", FeedState.POLLING, mid, "REST Fallback", null, binanceMarketTicks.get())
                            return@withContext pt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Binance REST fetch error: ${e.message}")
        }
        null
    }

    // ==========================================
    // 2. COINBASE STREAMING & REST
    // ==========================================

    private fun connectCoinbaseWs() {
        try {
            val request = Request.Builder().url(COINBASE_WS_URL).build()
            coinbaseWs = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val subMessage = JSONObject().apply {
                        put("type", "subscribe")
                        put("product_ids", org.json.JSONArray(listOf("BTC-USD")))
                        put("channels", org.json.JSONArray(listOf("ticker")))
                    }
                    webSocket.send(subMessage.toString())
                    updateStatus("COINBASE", FeedState.STREAMING, null, "WS Subscribed (BTC-USD)", null, coinbaseMarketTicks.get())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val productId = json.optString("product_id")
                        if (json.optString("type") == "ticker" && (productId.isEmpty() || productId == "BTC-USD")) {
                            val price = json.optString("price").toDoubleOrNull() ?: 0.0
                            val ask = json.optString("best_ask").toDoubleOrNull() ?: price
                            val bid = json.optString("best_bid").toDoubleOrNull() ?: price
                            val vol = json.optString("volume_24h").toDoubleOrNull() ?: 1.0

                            if (price > 0.0) {
                                val now = System.currentTimeMillis()
                                val count = coinbaseMarketTicks.incrementAndGet()
                                val succ = (consecutiveWsSuccess["COINBASE"] ?: 0) + 1
                                consecutiveWsSuccess["COINBASE"] = succ
                                if (succ >= 3) isRestFallbackActive["COINBASE"] = false

                                val pt = PricePoint(
                                    price = price,
                                    timestamp = now,
                                    exchange = "COINBASE",
                                    volume = vol,
                                    bidPrice = bid,
                                    askPrice = ask,
                                    quoteCurrency = "USD",
                                    baseVolumeBtc = vol,
                                    quoteVolumeUsd = vol * price,
                                    exchangeTimestamp = now
                                )
                                latestSpotPoints["COINBASE"] = pt
                                updateStatus("COINBASE", FeedState.STREAMING, price, "Spot WS", null, count)
                            }
                        }
                    } catch (e: Exception) {
                        SafeLog.w(TAG, "Coinbase WS parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    consecutiveWsSuccess["COINBASE"] = 0
                    isRestFallbackActive["COINBASE"] = true
                    updateStatus("COINBASE", FeedState.RECONNECTING, null, "WS Reconnecting", t.message, coinbaseMarketTicks.get())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    updateStatus("COINBASE", FeedState.DISCONNECTED, null, "WS Closed ($reason)", null, coinbaseMarketTicks.get())
                }
            })
        } catch (e: Exception) {
            SafeLog.w(TAG, "Coinbase WS connect error: ${e.message}")
        }
    }

    suspend fun fetchCoinbasePrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(COINBASE_REST_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val data = json.optJSONObject("data")
                        val amount = data?.optString("amount")?.toDoubleOrNull() ?: 0.0
                        if (amount > 0.0) {
                            val count = coinbaseRestFetches.incrementAndGet()
                            val pt = PricePoint(
                                price = amount,
                                timestamp = now,
                                exchange = "COINBASE",
                                volume = 1.0,
                                bidPrice = amount,
                                askPrice = amount,
                                quoteCurrency = "USD",
                                baseVolumeBtc = 1.0,
                                quoteVolumeUsd = amount,
                                exchangeTimestamp = now
                            )
                            latestSpotPoints["COINBASE"] = pt
                            updateStatus("COINBASE", FeedState.POLLING, amount, "REST Spot", null, coinbaseMarketTicks.get())
                            return@withContext pt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Coinbase REST fetch error: ${e.message}")
        }
        null
    }

    // ==========================================
    // 3. KRAKEN STREAMING & REST
    // ==========================================

    private fun connectKrakenWs() {
        try {
            val request = Request.Builder().url(KRAKEN_WS_URL).build()
            krakenWs = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val subMessage = JSONObject().apply {
                        put("event", "subscribe")
                        put("pair", org.json.JSONArray(listOf("XBT/USD")))
                        put("subscription", JSONObject().apply { put("name", "ticker") })
                    }
                    webSocket.send(subMessage.toString())
                    updateStatus("KRAKEN", FeedState.STREAMING, null, "WS Subscribed (XBT/USD)", null, krakenMarketTicks.get())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        if (text.startsWith("[")) {
                            val jsonArr = org.json.JSONArray(text)
                            if (jsonArr.length() >= 2) {
                                val pair = if (jsonArr.length() >= 4) jsonArr.optString(3) else ""
                                if (pair.isNotEmpty() && pair != "XBT/USD" && pair != "XBT/USDT") {
                                    return
                                }
                                val tickerData = jsonArr.optJSONObject(1)
                                if (tickerData != null) {
                                    val closeArr = tickerData.optJSONArray("c")
                                    val askArr = tickerData.optJSONArray("a")
                                    val bidArr = tickerData.optJSONArray("b")
                                    val volArr = tickerData.optJSONArray("v")

                                    val price = closeArr?.optString(0)?.toDoubleOrNull() ?: 0.0
                                    val ask = askArr?.optString(0)?.toDoubleOrNull() ?: price
                                    val bid = bidArr?.optString(0)?.toDoubleOrNull() ?: price
                                    val vol = volArr?.optString(1)?.toDoubleOrNull() ?: 1.0

                                    if (price > 0.0) {
                                        val now = System.currentTimeMillis()
                                        val count = krakenMarketTicks.incrementAndGet()
                                        val succ = (consecutiveWsSuccess["KRAKEN"] ?: 0) + 1
                                        consecutiveWsSuccess["KRAKEN"] = succ
                                        if (succ >= 3) isRestFallbackActive["KRAKEN"] = false

                                        val pt = PricePoint(
                                            price = price,
                                            timestamp = now,
                                            exchange = "KRAKEN",
                                            volume = vol,
                                            bidPrice = bid,
                                            askPrice = ask,
                                            quoteCurrency = "USD",
                                            baseVolumeBtc = vol,
                                            quoteVolumeUsd = vol * price,
                                            exchangeTimestamp = now
                                        )
                                        latestSpotPoints["KRAKEN"] = pt
                                        updateStatus("KRAKEN", FeedState.STREAMING, price, "XBT Spot WS", null, count)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        SafeLog.w(TAG, "Kraken WS message error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    consecutiveWsSuccess["KRAKEN"] = 0
                    isRestFallbackActive["KRAKEN"] = true
                    updateStatus("KRAKEN", FeedState.RECONNECTING, null, "WS Reconnecting", t.message, krakenMarketTicks.get())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    updateStatus("KRAKEN", FeedState.DISCONNECTED, null, "WS Closed ($reason)", null, krakenMarketTicks.get())
                }
            })
        } catch (e: Exception) {
            SafeLog.w(TAG, "Kraken WS connect error: ${e.message}")
        }
    }

    suspend fun fetchKrakenPrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(KRAKEN_REST_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val result = json.optJSONObject("result")
                        val pairData = result?.optJSONObject("XXBTZUSD")
                            ?: result?.optJSONObject("XBTUSD")
                            ?: result?.optJSONObject("XBTUSDT")
                        if (pairData != null) {
                            val c = pairData.optJSONArray("c")
                            val a = pairData.optJSONArray("a")
                            val b = pairData.optJSONArray("b")
                            val v = pairData.optJSONArray("v")

                            val price = c?.optString(0)?.toDoubleOrNull() ?: 0.0
                            val ask = a?.optString(0)?.toDoubleOrNull() ?: price
                            val bid = b?.optString(0)?.toDoubleOrNull() ?: price
                            val vol = v?.optString(1)?.toDoubleOrNull() ?: 1.0

                            if (price > 0.0) {
                                val count = krakenRestFetches.incrementAndGet()
                                val pt = PricePoint(
                                    price = price,
                                    timestamp = now,
                                    exchange = "KRAKEN",
                                    volume = vol,
                                    bidPrice = bid,
                                    askPrice = ask,
                                    quoteCurrency = "USD",
                                    baseVolumeBtc = vol,
                                    quoteVolumeUsd = vol * price,
                                    exchangeTimestamp = now
                                )
                                latestSpotPoints["KRAKEN"] = pt
                                updateStatus("KRAKEN", FeedState.POLLING, price, "REST Ticker", null, krakenMarketTicks.get())
                                return@withContext pt
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Kraken REST fetch error: ${e.message}")
        }
        null
    }

    // ==========================================
    // 4. BITSTAMP SPOT REST (Live Participation)
    // ==========================================

    suspend fun fetchBitstampPrice(): PricePoint? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(BITSTAMP_REST_URL).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val price = json.optString("last").toDoubleOrNull() ?: 0.0
                        val ask = json.optString("ask").toDoubleOrNull() ?: price
                        val bid = json.optString("bid").toDoubleOrNull() ?: price
                        val vol = json.optString("volume").toDoubleOrNull() ?: 1.0

                        if (price > 0.0) {
                            val count = bitstampRestFetches.incrementAndGet()
                            val pt = PricePoint(
                                price = price,
                                timestamp = now,
                                exchange = "BITSTAMP",
                                volume = vol,
                                bidPrice = bid,
                                askPrice = ask,
                                quoteCurrency = "USD",
                                baseVolumeBtc = vol,
                                quoteVolumeUsd = vol * price,
                                exchangeTimestamp = now
                            )
                            latestSpotPoints["BITSTAMP"] = pt
                            updateStatus("BITSTAMP", FeedState.POLLING, price, "REST Spot", null, count)
                            return@withContext pt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Bitstamp price fetch error: ${e.message}")
        }
        null
    }

    /**
     * Fetches recent 15-minute 1m kline candles from Binance (with Coinbase fallback)
     * for authentic historical market charting on startup.
     */
    suspend fun fetchRecent15mCandles(): List<PricePoint> = withContext(Dispatchers.IO) {
        val points = mutableListOf<PricePoint>()
        // 1. Try Binance 1m klines (15 minutes)
        try {
            val url = "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=15"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val array = org.json.JSONArray(body)
                        for (i in 0 until array.length()) {
                            val candle = array.getJSONArray(i)
                            val openTime = candle.getLong(0)
                            val closePrice = candle.getString(4).toDoubleOrNull() ?: 0.0
                            val volume = candle.getString(5).toDoubleOrNull() ?: 1.0
                            if (closePrice > 0.0) {
                                points.add(
                                    PricePoint(
                                        price = closePrice,
                                        timestamp = openTime,
                                        exchange = "BINANCE",
                                        volume = volume
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Binance 15m candle fetch error: ${e.message}")
        }

        if (points.isNotEmpty()) {
            return@withContext points
        }

        // 2. Coinbase fallback for 1m candles
        try {
            val url = "https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=60"
            val req = Request.Builder().url(url).header("User-Agent", "QtY-Quant").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val array = org.json.JSONArray(body)
                        val count = minOf(array.length(), 15)
                        for (i in (count - 1) downTo 0) {
                            val candle = array.getJSONArray(i)
                            val epochSec = candle.getLong(0)
                            val closePrice = candle.getDouble(4)
                            val volume = candle.getDouble(5)
                            if (closePrice > 0.0) {
                                points.add(
                                    PricePoint(
                                        price = closePrice,
                                        timestamp = epochSec * 1000L,
                                        exchange = "COINBASE",
                                        volume = volume
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Coinbase 15m candle fallback error: ${e.message}")
        }

        points
    }

    /**
     * Retrieves all recently cached spot PricePoints for consolidation.
     */
    fun getLatestSpotPoints(): List<PricePoint> {
        return latestSpotPoints.values.toList()
    }

    fun getTotalMarketTicks(): Long {
        return binanceMarketTicks.get() + coinbaseMarketTicks.get() + krakenMarketTicks.get()
    }

    fun getTotalRestFetches(): Long {
        return binanceRestFetches.get() + coinbaseRestFetches.get() + krakenRestFetches.get() + bitstampRestFetches.get()
    }

    fun getTotalTicks(): Long {
        return getTotalMarketTicks()
    }

    private fun updateStatus(
        sourceId: String,
        state: FeedState,
        price: Double?,
        meta: String?,
        error: String?,
        ticks: Long
    ) {
        val currentMap = _sourceStatuses.value.toMutableMap()
        val existing = currentMap[sourceId] ?: return
        currentMap[sourceId] = existing.copy(
            feedState = state,
            lastUpdateTimestamp = System.currentTimeMillis(),
            latestPrice = price ?: existing.latestPrice,
            latestMetadata = meta ?: existing.latestMetadata,
            errorState = error,
            messageCount = ticks
        )
        _sourceStatuses.value = currentMap
    }
}
