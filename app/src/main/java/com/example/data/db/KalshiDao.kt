package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KalshiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateOrder(order: KalshiOrderRecordEntity)

    @Query("SELECT * FROM kalshi_orders WHERE clientOrderId = :clientOrderId LIMIT 1")
    suspend fun getOrderByClientOrderId(clientOrderId: String): KalshiOrderRecordEntity?

    @Query("SELECT * FROM kalshi_orders WHERE orderId = :orderId LIMIT 1")
    suspend fun getOrderByOrderId(orderId: String): KalshiOrderRecordEntity?

    @Query("SELECT clientOrderId FROM kalshi_orders")
    suspend fun getAllClientOrderIds(): List<String>

    @Query("SELECT * FROM kalshi_orders WHERE lifecycleState IN ('SUBMITTING', 'SUBMITTED', 'PARTIALLY_FILLED', 'CANCEL_PENDING')")
    suspend fun getActiveOrders(): List<KalshiOrderRecordEntity>

    @Query("SELECT * FROM kalshi_orders WHERE ticker = :ticker ORDER BY placedTimestamp DESC")
    suspend fun getOrdersByTicker(ticker: String): List<KalshiOrderRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntry(entry: RealizedProfitLedgerEntity)

    @Query("SELECT * FROM realized_profit_ledger ORDER BY timestamp ASC")
    suspend fun getAllLedgerEntries(): List<RealizedProfitLedgerEntity>

    @Query("SELECT * FROM realized_profit_ledger ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLedgerEntry(): RealizedProfitLedgerEntity?

    @Query("SELECT SUM(CASE WHEN realizedPnlDollars < 0 THEN -realizedPnlDollars ELSE 0 END) FROM realized_profit_ledger")
    suspend fun getCumulativeLoss(): Double?
}
