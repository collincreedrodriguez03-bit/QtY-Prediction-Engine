package com.example.data

import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe rolling price history window (15+ minutes).
 * At 2-second cycle intervals, 450-500 points = 15-17 minutes.
 */
class PriceHistory(
    private val maxCapacity: Int = 500
) {
    private val history = CopyOnWriteArrayList<PricePoint>()

    @Synchronized
    fun add(point: PricePoint) {
        history.add(point)
        while (history.size > maxCapacity) {
            history.removeAt(0)
        }
    }

    @Synchronized
    fun addAll(points: List<PricePoint>) {
        for (pt in points) {
            add(pt)
        }
    }

    /**
     * Returns a copy of all current price points in chronological order.
     */
    fun getAll(): List<PricePoint> {
        return history.toList()
    }

    /**
     * Returns the raw prices as a list of Doubles.
     */
    fun getPrices(): List<Double> {
        return history.map { it.price }
    }

    /**
     * Returns the last N prices, or all if size < N.
     */
    fun getLastNPrices(n: Int): List<Double> {
        val list = history.toList()
        return if (list.size <= n) {
            list.map { it.price }
        } else {
            list.takeLast(n).map { it.price }
        }
    }

    /**
     * Returns the defined settlement reference for the 15-minute Kalshi contract window.
     * Contracts start on clock-aligned 15-minute boundaries (00, 15, 30, 45).
     */
    fun get15mContractSettlementReference(timestamp: Long): Double? {
        val windowMs = 15 * 60 * 1000L
        val intervalStart = timestamp - (timestamp % windowMs)
        val list = history.toList()
        if (list.isEmpty()) return null
        val candidate = list.minByOrNull { kotlin.math.abs(it.timestamp - intervalStart) }
        return candidate?.price ?: list.first().price
    }

    /**
     * Returns the price observation nearest to the specified target timestamp.
     */
    fun getObservationAtTimestamp(targetTimestamp: Long): PricePoint? {
        val list = history.toList()
        if (list.isEmpty()) return null
        return list.minByOrNull { kotlin.math.abs(it.timestamp - targetTimestamp) }
    }

    /**
     * Returns the latest price point if available.
     */
    fun getLatest(): PricePoint? {
        return history.lastOrNull()
    }

    /**
     * Returns the number of points in history.
     */
    fun size(): Int = history.size

    fun isEmpty(): Boolean = history.isEmpty()

    @Synchronized
    fun clear() {
        history.clear()
    }
}
