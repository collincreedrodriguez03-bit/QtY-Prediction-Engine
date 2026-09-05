package com.example.kalshi

import com.example.BuildConfig
import com.example.data.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.PrivateKey
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Official Kalshi API v2 Client.
 * Connects to Kalshi for:
 * 1. Discovering active BTC 15-minute contracts (series: KXBTC15M)
 * 2. Validating contract expiry, settlement methodology, and strike reference
 * 3. Authenticated balance and position checking
 * 4. Submitting limit/market orders with duplicate protection and fill tracking
 */
open class KalshiApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .writeTimeout(4000, TimeUnit.MILLISECONDS)
        .build(),
    private val baseUrl: String = "https://api.elections.kalshi.com/trade-api/v2"
) {
    companion object {
        private const val TAG = "KalshiApiClient"
        const val BTC_15M_SERIES = "KXBTC15M"
    }

    private var keyId: String? = null
    private var privateKey: PrivateKey? = null

    init {
        // Attempt to load credentials from BuildConfig / Environment if injected
        try {
            val keyField = BuildConfig::class.java.fields.find { it.name == "KALSHI_KEY_ID" || it.name == "KALSHI_API_KEY" }
            val secretField = BuildConfig::class.java.fields.find { it.name == "KALSHI_PRIVATE_KEY" || it.name == "KALSHI_SECRET_KEY" }
            val kid = keyField?.get(null) as? String
            val sec = secretField?.get(null) as? String
            if (!kid.isNullOrBlank() && !sec.isNullOrBlank()) {
                setCredentials(kid, sec)
            }
        } catch (_: Throwable) {}
    }

    open fun setCredentials(keyId: String, privateKeyPemOrBase64: String) {
        val trimmedKey = keyId.trim()
        if (trimmedKey.isEmpty()) {
            this.keyId = null
            this.privateKey = null
            return
        }
        val parsedKey = KalshiSigner.parsePrivateKey(privateKeyPemOrBase64)
        if (parsedKey == null) {
            SafeLog.w(TAG, "Failed to parse Kalshi private key. Authentication disabled.")
            this.keyId = null
            this.privateKey = null
            return
        }
        this.keyId = trimmedKey
        this.privateKey = parsedKey
    }

    fun clearCredentials() {
        this.keyId = null
        this.privateKey = null
    }

    open fun isAuthenticated(): Boolean {
        return !keyId.isNullOrBlank() && privateKey != null
    }

    /**
     * Finds the currently active 15-minute BTC contract on Kalshi.
     * Public endpoint: /markets?series_ticker=KXBTC15M&status=active
     */
    open suspend fun getActiveBtc15mContracts(): Result<List<KalshiMarket>> = withContext(Dispatchers.IO) {
        try {
            // First try status=open, fallback to status=active
            var path = "/markets?series_ticker=$BTC_15M_SERIES&status=open"
            var request = Request.Builder()
                .url("$baseUrl$path")
                .get()
                .build()

            var response = client.newCall(request).execute()
            if (!response.isSuccessful && response.code == 400) {
                path = "/markets?series_ticker=$BTC_15M_SERIES&status=active"
                request = Request.Builder().url("$baseUrl$path").get().build()
                response = client.newCall(request).execute()
            }
            if (!response.isSuccessful) {
                val err = when (response.code) {
                    429 -> "Rate limit exceeded (HTTP 429)"
                    401 -> "Unauthorized access to Kalshi API (HTTP 401)"
                    403 -> "Forbidden: Check API permissions (HTTP 403)"
                    else -> "HTTP ${response.code}: ${response.message}"
                }
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val json = JSONObject(body)
            val marketsArr = json.optJSONArray("markets") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<KalshiMarket>()
            val nowMs = System.currentTimeMillis()

            for (i in 0 until marketsArr.length()) {
                val m = marketsArr.getJSONObject(i)
                val ticker = m.optString("ticker")
                val eventTicker = m.optString("event_ticker")
                val series = m.optString("series_ticker", BTC_15M_SERIES)
                val title = m.optString("title")
                val subtitle = m.optString("subtitle")
                val status = m.optString("status")

                val openMs = parseIsoTimeToMs(m.optString("open_time"))
                val closeMs = parseIsoTimeToMs(m.optString("close_time"))
                val expMs = parseIsoTimeToMs(m.optString("expiration_time"))

                val yesBid = m.optInt("yes_bid", 0)
                val yesAsk = m.optInt("yes_ask", 0)
                val noBid = m.optInt("no_bid", 0)
                val noAsk = m.optInt("no_ask", 0)
                val lastPrice = m.optInt("last_price", 0)
                val floorStrike = m.optDouble("floor_strike", Double.NaN)
                val strike = if (floorStrike.isNaN()) null else floorStrike
                val strikeType = m.optString("strike_type", "greater")

                list.add(
                    KalshiMarket(
                        ticker = ticker,
                        eventTicker = eventTicker,
                        seriesTicker = series,
                        title = title,
                        subtitle = subtitle,
                        openTimeMs = openMs,
                        closeTimeMs = closeMs,
                        expirationTimeMs = expMs,
                        status = status,
                        yesBid = yesBid,
                        yesAsk = yesAsk,
                        noBid = noBid,
                        noAsk = noAsk,
                        lastPrice = lastPrice,
                        strikePrice = strike,
                        strikeType = strikeType
                    )
                )
            }

            // Filter for contracts that close in the future and open <= now
            val active = list.filter { it.closeTimeMs > nowMs }
                .sortedBy { it.closeTimeMs }

            Result.success(active)
        } catch (e: Exception) {
            SafeLog.e(TAG, "Error fetching BTC 15m markets: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Retrieves the order book for a given market ticker from public endpoint:
     * GET /markets/{ticker}/orderbook
     *
     * Kalshi order book returns bids for YES and NO contracts.
     * Parses both integer cent representation ("orderbook") and fixed-point string representation ("orderbook_fp").
     * Never manufactures missing data.
     */
    open suspend fun getOrderBook(ticker: String, depth: Int = 20): Result<KalshiOrderBookSnapshot> = withContext(Dispatchers.IO) {
        if (ticker.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Ticker cannot be blank"))
        }

        try {
            val path = "/markets/$ticker/orderbook?depth=$depth"
            val request = Request.Builder()
                .url("$baseUrl$path")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val nowMs = System.currentTimeMillis()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
            val json = JSONObject(body)

            val yesBids = mutableListOf<KalshiOrderBookLevel>()
            val noBids = mutableListOf<KalshiOrderBookLevel>()

            // 1. Try "orderbook" object with integer cents
            val orderbookObj = json.optJSONObject("orderbook")
            if (orderbookObj != null) {
                val yesArr = orderbookObj.optJSONArray("yes")
                if (yesArr != null) {
                    for (i in 0 until yesArr.length()) {
                        val level = yesArr.optJSONArray(i) ?: continue
                        val p = level.optInt(0, 0)
                        val q = level.optDouble(1, 0.0)
                        if (p in 1..99 && q > 0.0) {
                            yesBids.add(KalshiOrderBookLevel(priceCents = p, priceDollars = p / 100.0, quantity = q))
                        }
                    }
                }

                val noArr = orderbookObj.optJSONArray("no")
                if (noArr != null) {
                    for (i in 0 until noArr.length()) {
                        val level = noArr.optJSONArray(i) ?: continue
                        val p = level.optInt(0, 0)
                        val q = level.optDouble(1, 0.0)
                        if (p in 1..99 && q > 0.0) {
                            noBids.add(KalshiOrderBookLevel(priceCents = p, priceDollars = p / 100.0, quantity = q))
                        }
                    }
                }
            }

            // 2. Try "orderbook_fp" object if yesBids and noBids are still empty
            if (yesBids.isEmpty() && noBids.isEmpty()) {
                val orderbookFpObj = json.optJSONObject("orderbook_fp")
                if (orderbookFpObj != null) {
                    val yesDollarsArr = orderbookFpObj.optJSONArray("yes_dollars")
                    if (yesDollarsArr != null) {
                        for (i in 0 until yesDollarsArr.length()) {
                            val level = yesDollarsArr.optJSONArray(i) ?: continue
                            val pStr = level.optString(0, "")
                            val qStr = level.optString(1, "")
                            val pDollars = pStr.toDoubleOrNull() ?: continue
                            val q = qStr.toDoubleOrNull() ?: continue
                            val pCents = (pDollars * 100.0).roundToInt()
                            if (pCents in 1..99 && q > 0.0) {
                                yesBids.add(KalshiOrderBookLevel(priceCents = pCents, priceDollars = pDollars, quantity = q))
                            }
                        }
                    }

                    val noDollarsArr = orderbookFpObj.optJSONArray("no_dollars")
                    if (noDollarsArr != null) {
                        for (i in 0 until noDollarsArr.length()) {
                            val level = noDollarsArr.optJSONArray(i) ?: continue
                            val pStr = level.optString(0, "")
                            val qStr = level.optString(1, "")
                            val pDollars = pStr.toDoubleOrNull() ?: continue
                            val q = qStr.toDoubleOrNull() ?: continue
                            val pCents = (pDollars * 100.0).roundToInt()
                            if (pCents in 1..99 && q > 0.0) {
                                noBids.add(KalshiOrderBookLevel(priceCents = pCents, priceDollars = pDollars, quantity = q))
                            }
                        }
                    }
                }
            }

            yesBids.sortByDescending { it.priceCents }
            noBids.sortByDescending { it.priceCents }

            val bestYesBid = yesBids.firstOrNull()?.priceCents
            val bestNoBid = noBids.firstOrNull()?.priceCents
            val impliedYesAsk = bestNoBid?.let { 100 - it }
            val impliedNoAsk = bestYesBid?.let { 100 - it }
            val totalYesDepth = yesBids.sumOf { it.quantity }
            val totalNoDepth = noBids.sumOf { it.quantity }
            val status = if (yesBids.isNotEmpty() || noBids.isNotEmpty()) "LIVE" else "EMPTY_BOOK"

            Result.success(
                KalshiOrderBookSnapshot(
                    ticker = ticker,
                    timestampMs = nowMs,
                    yesBids = yesBids,
                    noBids = noBids,
                    bestYesBidCents = bestYesBid,
                    bestNoBidCents = bestNoBid,
                    impliedYesAskCents = impliedYesAsk,
                    impliedNoAskCents = impliedNoAsk,
                    totalYesDepth = totalYesDepth,
                    totalNoDepth = totalNoDepth,
                    status = status
                )
            )
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to fetch order book for $ticker: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Checks authenticated user portfolio cash balance and total portfolio value.
     */
    open suspend fun getPortfolioBalance(): Result<KalshiBalance> = withContext(Dispatchers.IO) {
        val kid = keyId
        val pk = privateKey
        if (kid == null || pk == null) {
            return@withContext Result.failure(Exception("Account not authenticated with Kalshi credentials"))
        }

        try {
            val endpoint = "/portfolio/balance"
            val timestamp = System.currentTimeMillis()
            val signature = KalshiSigner.signMessage(timestamp, "GET", endpoint, pk)

            val request = Request.Builder()
                .url("$baseUrl$endpoint")
                .header("KALSHI-ACCESS-KEY", kid)
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp.toString())
                .header("KALSHI-ACCESS-SIGNATURE", signature)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = when (response.code) {
                    401 -> "Unauthorized: Kalshi credentials invalid or expired (HTTP 401)"
                    403 -> "Forbidden: Check Kalshi API permissions (HTTP 403)"
                    429 -> "Rate limit exceeded (HTTP 429)"
                    else -> "HTTP ${response.code}: ${response.message}"
                }
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val json = JSONObject(body)
            val balanceCents = json.optLong("balance", 0L)
            val portfolioValueCents = json.optLong("portfolio_value", balanceCents)

            Result.success(
                KalshiBalance(
                    balanceCents = balanceCents,
                    portfolioValueCents = portfolioValueCents,
                    updatedTimestampMs = timestamp
                )
            )
        } catch (e: Exception) {
            SafeLog.e(TAG, "Error fetching balance: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Gets user's open positions from /portfolio/positions.
     */
    open suspend fun getPositions(): Result<List<KalshiPosition>> = withContext(Dispatchers.IO) {
        val kid = keyId
        val pk = privateKey
        if (kid == null || pk == null) {
            return@withContext Result.failure(Exception("Account not authenticated with Kalshi credentials"))
        }

        try {
            val endpoint = "/portfolio/positions"
            val timestamp = System.currentTimeMillis()
            val signature = KalshiSigner.signMessage(timestamp, "GET", endpoint, pk)

            val request = Request.Builder()
                .url("$baseUrl$endpoint")
                .header("KALSHI-ACCESS-KEY", kid)
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp.toString())
                .header("KALSHI-ACCESS-SIGNATURE", signature)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = when (response.code) {
                    401 -> "Unauthorized: Kalshi credentials invalid or expired (HTTP 401)"
                    403 -> "Forbidden: Check Kalshi API permissions (HTTP 403)"
                    429 -> "Rate limit exceeded (HTTP 429)"
                    else -> "HTTP ${response.code}: ${response.message}"
                }
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val json = JSONObject(body)
            val positionsArr = json.optJSONArray("market_positions") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<KalshiPosition>()
            for (i in 0 until positionsArr.length()) {
                val p = positionsArr.getJSONObject(i)
                val ticker = p.optString("ticker")
                val pos = p.optInt("position", 0)
                val exposure = p.optLong("market_exposure", 0L)
                val fees = p.optLong("fees_paid", 0L)
                val realized = p.optLong("realized_pnl", 0L)
                val resting = p.optInt("resting_orders_count", 0)

                list.add(
                    KalshiPosition(
                        ticker = ticker,
                        position = pos,
                        marketExposureCents = exposure,
                        feesPaidCents = fees,
                        realizedPnlCents = realized,
                        restingOrdersCount = resting
                    )
                )
            }

            Result.success(list)
        } catch (e: Exception) {
            SafeLog.e(TAG, "Error fetching positions: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Submits a new order to Kalshi V2 /portfolio/orders.
     * Enforces clientOrderId to prevent duplicate submissions.
     */
    open suspend fun submitOrder(order: KalshiOrderRequest): Result<KalshiOrderResponse> = withContext(Dispatchers.IO) {
        val kid = keyId
        val pk = privateKey
        if (kid == null || pk == null) {
            return@withContext Result.failure(Exception("Account not authenticated with Kalshi credentials"))
        }

        // Validate order inputs before submitting
        if (order.count <= 0 || order.count > 5) {
            return@withContext Result.failure(Exception("Invalid order count (${order.count}): must be between 1 and 5"))
        }
        if (order.side != "yes" && order.side != "no") {
            return@withContext Result.failure(Exception("Invalid side (${order.side}): must be 'yes' or 'no'"))
        }
        val targetPrice = if (order.side == "yes") order.yesPrice else order.noPrice
        if (targetPrice != null && (targetPrice <= 0 || targetPrice >= 100)) {
            return@withContext Result.failure(Exception("Invalid limit price ($targetPrice cents): must be 1..99"))
        }

        try {
            val endpoint = "/portfolio/orders"
            val timestamp = System.currentTimeMillis()
            val signature = KalshiSigner.signMessage(timestamp, "POST", endpoint, pk)

            val jsonBody = JSONObject().apply {
                put("ticker", order.ticker)
                put("action", order.action)
                put("side", order.side)
                put("type", order.type)
                put("count", order.count)
                put("client_order_id", order.clientOrderId)
                put("time_in_force", order.timeInForce)
                if (order.yesPrice != null) put("yes_price", order.yesPrice)
                if (order.noPrice != null) put("no_price", order.noPrice)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl$endpoint")
                .header("KALSHI-ACCESS-KEY", kid)
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp.toString())
                .header("KALSHI-ACCESS-SIGNATURE", signature)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errReason = when (response.code) {
                    400 -> "Invalid order parameters"
                    401 -> "Unauthorized: Kalshi credentials invalid or expired"
                    403 -> "Forbidden: Check trading permissions"
                    429 -> "Rate limit exceeded"
                    else -> "HTTP ${response.code}"
                }
                return@withContext Result.failure(Exception("Order rejected ($errReason): $responseBody"))
            }

            val jsonResp = JSONObject(responseBody)
            val orderObj = jsonResp.optJSONObject("order") ?: jsonResp
            val orderId = orderObj.optString("order_id")
            val status = orderObj.optString("status", "resting")
            val filled = orderObj.optInt("filled_count", 0)
            val price = if (order.side == "yes") (order.yesPrice ?: 50) else (order.noPrice ?: 50)

            Result.success(
                KalshiOrderResponse(
                    orderId = orderId,
                    clientOrderId = order.clientOrderId,
                    ticker = order.ticker,
                    status = status,
                    action = order.action,
                    side = order.side,
                    count = order.count,
                    filledCount = filled,
                    price = price,
                    placeTimeMs = timestamp
                )
            )
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to submit order: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Cancels an open or resting order on Kalshi V2:
     * DELETE /portfolio/orders/{order_id}
     */
    suspend fun cancelOrder(orderId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val kid = keyId
        val pk = privateKey
        if (kid == null || pk == null) {
            return@withContext Result.failure(Exception("Account not authenticated with Kalshi credentials. Fail closed."))
        }

        if (orderId.isBlank()) {
            return@withContext Result.failure(Exception("Cannot cancel order: empty order ID"))
        }

        try {
            val endpoint = "/portfolio/orders/$orderId"
            val timestamp = System.currentTimeMillis()
            val signature = KalshiSigner.signMessage(timestamp, "DELETE", endpoint, pk)

            val request = Request.Builder()
                .url("$baseUrl$endpoint")
                .header("KALSHI-ACCESS-KEY", kid)
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp.toString())
                .header("KALSHI-ACCESS-SIGNATURE", signature)
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Cancel failure (HTTP ${response.code}): $body"))
            }
            Result.success(true)
        } catch (e: Exception) {
            SafeLog.e(TAG, "Cancel failure for order $orderId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Retrieves currently open/resting orders:
     * GET /portfolio/orders?status=resting
     */
    suspend fun getOpenOrders(): Result<List<KalshiOrderResponse>> = withContext(Dispatchers.IO) {
        val kid = keyId
        val pk = privateKey
        if (kid == null || pk == null) {
            return@withContext Result.failure(Exception("Account not authenticated with Kalshi credentials. Fail closed."))
        }

        try {
            val endpoint = "/portfolio/orders?status=resting"
            val timestamp = System.currentTimeMillis()
            val signature = KalshiSigner.signMessage(timestamp, "GET", endpoint, pk)

            val request = Request.Builder()
                .url("$baseUrl$endpoint")
                .header("KALSHI-ACCESS-KEY", kid)
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp.toString())
                .header("KALSHI-ACCESS-SIGNATURE", signature)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val json = JSONObject(body)
            val ordersArr = json.optJSONArray("orders") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<KalshiOrderResponse>()
            for (i in 0 until ordersArr.length()) {
                val o = ordersArr.getJSONObject(i)
                list.add(
                    KalshiOrderResponse(
                        orderId = o.optString("order_id"),
                        clientOrderId = o.optString("client_order_id"),
                        ticker = o.optString("ticker"),
                        status = o.optString("status", "resting"),
                        action = o.optString("action", "buy"),
                        side = o.optString("side", "yes"),
                        count = o.optInt("count", 1),
                        filledCount = o.optInt("filled_count", 0),
                        price = o.optInt("yes_price", o.optInt("no_price", 50)),
                        placeTimeMs = parseIsoTimeToMs(o.optString("created_time"))
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves market details and settlement information for a specific ticker:
     * GET /markets/{ticker}
     */
    suspend fun getMarketSettlement(ticker: String): Result<KalshiMarket> = withContext(Dispatchers.IO) {
        if (ticker.isBlank()) return@withContext Result.failure(IllegalArgumentException("Ticker cannot be blank"))
        try {
            val path = "/markets/$ticker"
            val request = Request.Builder().url("$baseUrl$path").get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val json = JSONObject(body)
            val m = json.optJSONObject("market") ?: json
            val floorStrike = m.optDouble("floor_strike", Double.NaN)
            val market = KalshiMarket(
                ticker = m.optString("ticker"),
                eventTicker = m.optString("event_ticker"),
                seriesTicker = m.optString("series_ticker", BTC_15M_SERIES),
                title = m.optString("title"),
                subtitle = m.optString("subtitle"),
                openTimeMs = parseIsoTimeToMs(m.optString("open_time")),
                closeTimeMs = parseIsoTimeToMs(m.optString("close_time")),
                expirationTimeMs = parseIsoTimeToMs(m.optString("expiration_time")),
                status = m.optString("status"),
                yesBid = m.optInt("yes_bid", 0),
                yesAsk = m.optInt("yes_ask", 0),
                noBid = m.optInt("no_bid", 0),
                noAsk = m.optInt("no_ask", 0),
                lastPrice = m.optInt("last_price", 0),
                strikePrice = if (floorStrike.isNaN()) null else floorStrike,
                strikeType = m.optString("strike_type", "greater")
            )
            Result.success(market)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseIsoTimeToMs(isoString: String): Long {
        if (isoString.isBlank()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val clean = if (isoString.contains(".")) isoString.substringBefore(".") else isoString.replace("Z", "")
            format.parse(clean)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
