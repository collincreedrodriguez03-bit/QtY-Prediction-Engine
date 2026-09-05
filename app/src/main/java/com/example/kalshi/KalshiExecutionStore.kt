package com.example.kalshi

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interface for persistent order lifecycle and realized profit ledger storage.
 * Ensures duplicate prevention, lifecycle state, fill verification, and profit-only capital rule
 * survive restarts and avoid reliance on ephemeral in-memory state.
 */
interface KalshiExecutionStore {
    suspend fun recordOrder(order: KalshiOrderRecord)
    suspend fun updateOrder(order: KalshiOrderRecord)
    suspend fun getOrderByClientOrderId(clientOrderId: String): KalshiOrderRecord?
    suspend fun getOrderByOrderId(orderId: String): KalshiOrderRecord?
    suspend fun getAllClientOrderIds(): Set<String>
    suspend fun getActiveOrders(): List<KalshiOrderRecord>
    suspend fun getOrdersByContract(ticker: String): List<KalshiOrderRecord>
    suspend fun recordSettlement(entry: RealizedProfitLedgerEntry)
    suspend fun getAllLedgerEntries(): List<RealizedProfitLedgerEntry>
    suspend fun getLatestLedgerEntry(): RealizedProfitLedgerEntry?
    suspend fun getCumulativeLossDollars(): Double
}

/**
 * Thread-safe execution store implementation with optional file persistence.
 * Used for unit testing and as a durable file-backed store.
 */
class InMemoryKalshiExecutionStore(
    private val persistenceFile: File? = null
) : KalshiExecutionStore {

    private val orders = ConcurrentHashMap<String, KalshiOrderRecord>()
    private val ledger = CopyOnWriteArrayList<RealizedProfitLedgerEntry>()

    init {
        loadFromPersistence()
    }

    @Synchronized
    private fun loadFromPersistence() {
        if (persistenceFile == null || !persistenceFile.exists()) return
        try {
            val text = persistenceFile.readText()
            if (text.isBlank()) return
            val json = JSONObject(text)

            val ordersArray = json.optJSONArray("orders") ?: JSONArray()
            for (i in 0 until ordersArray.length()) {
                val o = ordersArray.getJSONObject(i)
                val record = KalshiOrderRecord(
                    clientOrderId = o.getString("clientOrderId"),
                    orderId = o.optString("orderId").takeIf { it.isNotBlank() },
                    ticker = o.getString("ticker"),
                    side = o.optString("side", "bid"),
                    action = o.optString("action", "buy"),
                    requestedCount = o.optInt("requestedCount", 1),
                    filledCount = o.optInt("filledCount", 0),
                    remainingCount = o.optInt("remainingCount", 1),
                    limitPriceCents = o.optInt("limitPriceCents", 0),
                    averageFillPriceCents = if (o.has("averageFillPriceCents")) o.optDouble("averageFillPriceCents") else null,
                    feesCents = o.optDouble("feesCents", 0.0),
                    lifecycleState = try {
                        OrderLifecycleState.valueOf(o.getString("lifecycleState"))
                    } catch (e: Exception) {
                        OrderLifecycleState.FAILED
                    },
                    placedTimestamp = o.optLong("placedTimestamp", 0L),
                    updatedTimestamp = o.optLong("updatedTimestamp", 0L),
                    failureReason = o.optString("failureReason").takeIf { it.isNotBlank() }
                )
                orders[record.clientOrderId] = record
            }

            val ledgerArray = json.optJSONArray("ledger") ?: JSONArray()
            for (i in 0 until ledgerArray.length()) {
                val l = ledgerArray.getJSONObject(i)
                val entry = RealizedProfitLedgerEntry(
                    tradeId = l.getString("tradeId"),
                    contractTicker = l.getString("contractTicker"),
                    orderId = l.getString("orderId"),
                    clientOrderId = l.getString("clientOrderId"),
                    entryCostDollars = l.getDouble("entryCostDollars"),
                    settlementPriceDollars = l.getDouble("settlementPriceDollars"),
                    feesDollars = l.getDouble("feesDollars"),
                    realizedPnlDollars = l.getDouble("realizedPnlDollars"),
                    timestamp = l.getLong("timestamp"),
                    capitalSource = l.getString("capitalSource"),
                    eligibleNextTradeCapitalDollars = l.getDouble("eligibleNextTradeCapitalDollars"),
                    isWin = l.getBoolean("isWin")
                )
                ledger.add(entry)
            }
        } catch (e: Exception) {
            // Log or fallback
        }
    }

    @Synchronized
    private fun saveToPersistence() {
        if (persistenceFile == null) return
        try {
            val json = JSONObject()
            val ordersArray = JSONArray()
            for (order in orders.values) {
                val o = JSONObject()
                o.put("clientOrderId", order.clientOrderId)
                o.put("orderId", order.orderId ?: "")
                o.put("ticker", order.ticker)
                o.put("side", order.side)
                o.put("action", order.action)
                o.put("requestedCount", order.requestedCount)
                o.put("filledCount", order.filledCount)
                o.put("remainingCount", order.remainingCount)
                o.put("limitPriceCents", order.limitPriceCents)
                order.averageFillPriceCents?.let { o.put("averageFillPriceCents", it) }
                o.put("feesCents", order.feesCents)
                o.put("lifecycleState", order.lifecycleState.name)
                o.put("placedTimestamp", order.placedTimestamp)
                o.put("updatedTimestamp", order.updatedTimestamp)
                o.put("failureReason", order.failureReason ?: "")
                ordersArray.put(o)
            }
            json.put("orders", ordersArray)

            val ledgerArray = JSONArray()
            for (entry in ledger) {
                val l = JSONObject()
                l.put("tradeId", entry.tradeId)
                l.put("contractTicker", entry.contractTicker)
                l.put("orderId", entry.orderId)
                l.put("clientOrderId", entry.clientOrderId)
                l.put("entryCostDollars", entry.entryCostDollars)
                l.put("settlementPriceDollars", entry.settlementPriceDollars)
                l.put("feesDollars", entry.feesDollars)
                l.put("realizedPnlDollars", entry.realizedPnlDollars)
                l.put("timestamp", entry.timestamp)
                l.put("capitalSource", entry.capitalSource)
                l.put("eligibleNextTradeCapitalDollars", entry.eligibleNextTradeCapitalDollars)
                l.put("isWin", entry.isWin)
                ledgerArray.put(l)
            }
            json.put("ledger", ledgerArray)

            persistenceFile.parentFile?.mkdirs()
            persistenceFile.writeText(json.toString())
        } catch (e: Exception) {
            // Safety
        }
    }

    override suspend fun recordOrder(order: KalshiOrderRecord) {
        orders[order.clientOrderId] = order
        saveToPersistence()
    }

    override suspend fun updateOrder(order: KalshiOrderRecord) {
        orders[order.clientOrderId] = order
        saveToPersistence()
    }

    override suspend fun getOrderByClientOrderId(clientOrderId: String): KalshiOrderRecord? {
        return orders[clientOrderId]
    }

    override suspend fun getOrderByOrderId(orderId: String): KalshiOrderRecord? {
        return orders.values.find { it.orderId == orderId }
    }

    override suspend fun getAllClientOrderIds(): Set<String> {
        return orders.keys.toSet()
    }

    override suspend fun getActiveOrders(): List<KalshiOrderRecord> {
        return orders.values.filter {
            it.lifecycleState == OrderLifecycleState.SUBMITTING ||
                it.lifecycleState == OrderLifecycleState.SUBMITTED ||
                it.lifecycleState == OrderLifecycleState.PARTIALLY_FILLED ||
                it.lifecycleState == OrderLifecycleState.CANCEL_PENDING
        }
    }

    override suspend fun getOrdersByContract(ticker: String): List<KalshiOrderRecord> {
        return orders.values.filter { it.ticker == ticker }
    }

    override suspend fun recordSettlement(entry: RealizedProfitLedgerEntry) {
        ledger.add(entry)
        saveToPersistence()
    }

    override suspend fun getAllLedgerEntries(): List<RealizedProfitLedgerEntry> {
        return ledger.toList()
    }

    override suspend fun getLatestLedgerEntry(): RealizedProfitLedgerEntry? {
        return ledger.lastOrNull()
    }

    override suspend fun getCumulativeLossDollars(): Double {
        return ledger.filter { it.realizedPnlDollars < 0.0 }
            .sumOf { -it.realizedPnlDollars }
    }

    fun clear() {
        orders.clear()
        ledger.clear()
        persistenceFile?.delete()
    }
}
