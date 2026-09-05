package com.example.kalshi

import com.example.data.db.KalshiDao
import com.example.data.db.KalshiOrderRecordEntity
import com.example.data.db.RealizedProfitLedgerEntity

/**
 * Room-backed persistent KalshiExecutionStore for production Android operation.
 */
class RoomKalshiExecutionStore(
    private val dao: KalshiDao
) : KalshiExecutionStore {

    override suspend fun recordOrder(order: KalshiOrderRecord) {
        dao.insertOrUpdateOrder(toEntity(order))
    }

    override suspend fun updateOrder(order: KalshiOrderRecord) {
        dao.insertOrUpdateOrder(toEntity(order))
    }

    override suspend fun getOrderByClientOrderId(clientOrderId: String): KalshiOrderRecord? {
        return dao.getOrderByClientOrderId(clientOrderId)?.let { fromEntity(it) }
    }

    override suspend fun getOrderByOrderId(orderId: String): KalshiOrderRecord? {
        return dao.getOrderByOrderId(orderId)?.let { fromEntity(it) }
    }

    override suspend fun getAllClientOrderIds(): Set<String> {
        return dao.getAllClientOrderIds().toSet()
    }

    override suspend fun getActiveOrders(): List<KalshiOrderRecord> {
        return dao.getActiveOrders().map { fromEntity(it) }
    }

    override suspend fun getOrdersByContract(ticker: String): List<KalshiOrderRecord> {
        return dao.getOrdersByTicker(ticker).map { fromEntity(it) }
    }

    override suspend fun recordSettlement(entry: RealizedProfitLedgerEntry) {
        dao.insertLedgerEntry(toLedgerEntity(entry))
    }

    override suspend fun getAllLedgerEntries(): List<RealizedProfitLedgerEntry> {
        return dao.getAllLedgerEntries().map { fromLedgerEntity(it) }
    }

    override suspend fun getLatestLedgerEntry(): RealizedProfitLedgerEntry? {
        return dao.getLatestLedgerEntry()?.let { fromLedgerEntity(it) }
    }

    override suspend fun getCumulativeLossDollars(): Double {
        return dao.getCumulativeLoss() ?: 0.0
    }

    private fun toEntity(order: KalshiOrderRecord): KalshiOrderRecordEntity {
        return KalshiOrderRecordEntity(
            clientOrderId = order.clientOrderId,
            orderId = order.orderId,
            ticker = order.ticker,
            side = order.side,
            action = order.action,
            requestedCount = order.requestedCount,
            filledCount = order.filledCount,
            remainingCount = order.remainingCount,
            limitPriceCents = order.limitPriceCents,
            averageFillPriceCents = order.averageFillPriceCents,
            feesCents = order.feesCents,
            lifecycleState = order.lifecycleState.name,
            placedTimestamp = order.placedTimestamp,
            updatedTimestamp = order.updatedTimestamp,
            failureReason = order.failureReason
        )
    }

    private fun fromEntity(entity: KalshiOrderRecordEntity): KalshiOrderRecord {
        return KalshiOrderRecord(
            clientOrderId = entity.clientOrderId,
            orderId = entity.orderId,
            ticker = entity.ticker,
            side = entity.side,
            action = entity.action,
            requestedCount = entity.requestedCount,
            filledCount = entity.filledCount,
            remainingCount = entity.remainingCount,
            limitPriceCents = entity.limitPriceCents,
            averageFillPriceCents = entity.averageFillPriceCents,
            feesCents = entity.feesCents,
            lifecycleState = try {
                OrderLifecycleState.valueOf(entity.lifecycleState)
            } catch (e: Exception) {
                OrderLifecycleState.FAILED
            },
            placedTimestamp = entity.placedTimestamp,
            updatedTimestamp = entity.updatedTimestamp,
            failureReason = entity.failureReason
        )
    }

    private fun toLedgerEntity(entry: RealizedProfitLedgerEntry): RealizedProfitLedgerEntity {
        return RealizedProfitLedgerEntity(
            tradeId = entry.tradeId,
            contractTicker = entry.contractTicker,
            orderId = entry.orderId,
            clientOrderId = entry.clientOrderId,
            entryCostDollars = entry.entryCostDollars,
            settlementPriceDollars = entry.settlementPriceDollars,
            feesDollars = entry.feesDollars,
            realizedPnlDollars = entry.realizedPnlDollars,
            timestamp = entry.timestamp,
            capitalSource = entry.capitalSource,
            eligibleNextTradeCapitalDollars = entry.eligibleNextTradeCapitalDollars,
            isWin = entry.isWin
        )
    }

    private fun fromLedgerEntity(entity: RealizedProfitLedgerEntity): RealizedProfitLedgerEntry {
        return RealizedProfitLedgerEntry(
            tradeId = entity.tradeId,
            contractTicker = entity.contractTicker,
            orderId = entity.orderId,
            clientOrderId = entity.clientOrderId,
            entryCostDollars = entity.entryCostDollars,
            settlementPriceDollars = entity.settlementPriceDollars,
            feesDollars = entity.feesDollars,
            realizedPnlDollars = entity.realizedPnlDollars,
            timestamp = entity.timestamp,
            capitalSource = entity.capitalSource,
            eligibleNextTradeCapitalDollars = entity.eligibleNextTradeCapitalDollars,
            isWin = entity.isWin
        )
    }
}
