package com.example.data

import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-performance JSON file logger for QtY Prediction Records.
 * Saves structured JSON records to disk and supports in-memory caching and retrieval.
 */
class JsonPredictionLogger(
    private val logDirectory: File? = null
) {
    private val memoryLog = mutableListOf<PredictionRecord>()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun getLogFile(): File? {
        if (logDirectory == null) return null
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
        return File(logDirectory, "qty_predictions.jsonl")
    }

    @Synchronized
    fun log(record: PredictionRecord) {
        memoryLog.add(record)
        if (memoryLog.size > 1000) {
            memoryLog.removeAt(0)
        }

        val file = getLogFile() ?: return
        try {
            val jsonStr = toJson(record).toString()
            FileWriter(file, true).use { writer ->
                writer.append(jsonStr).append("\n")
            }
        } catch (e: Exception) {
            SafeLog.e("QtY_JsonLogger", "Failed to write prediction to JSON: ${e.message}")
        }
    }

    @Synchronized
    fun updateResolvedRecord(
        predictionId: String,
        actualPrice: Double?,
        result: String,
        actualPrice90s: Double? = null,
        result90s: String? = null,
        kalshiTicker: String? = null,
        kalshiOrderId: String? = null,
        kalshiOrderStatus: String? = null,
        kalshiFilledCount: Int? = null,
        kalshiOrderPrice: Int? = null
    ) {
        val index = memoryLog.indexOfFirst { it.predictionId == predictionId }
        if (index != -1) {
            val rec = memoryLog[index]
            rec.actualPrice = actualPrice
            rec.actualPrice30s = actualPrice
            rec.result = result
            rec.result30s = result
            if (actualPrice90s != null) rec.actualPrice90s = actualPrice90s
            if (result90s != null) rec.result90s = result90s
            if (kalshiTicker != null) rec.kalshiContractTicker = kalshiTicker
            if (kalshiOrderId != null) rec.kalshiOrderId = kalshiOrderId
            if (kalshiOrderStatus != null) rec.kalshiOrderStatus = kalshiOrderStatus
            if (kalshiFilledCount != null) rec.kalshiFilledCount = kalshiFilledCount
            if (kalshiOrderPrice != null) rec.kalshiOrderPrice = kalshiOrderPrice
        }
    }

    @Synchronized
    fun getRecentPredictions(limit: Int = 10): List<PredictionRecord> {
        return memoryLog.takeLast(limit)
    }

    @Synchronized
    fun getAllPredictions(): List<PredictionRecord> {
        return memoryLog.toList()
    }

    fun toJson(record: PredictionRecord): JSONObject {
        val obj = JSONObject()
        obj.put("predictionId", record.predictionId)
        obj.put("timestamp", record.timestamp)
        obj.put("isoTime", isoFormat.format(Date(record.timestamp)))
        obj.put("currentPrice", record.currentPrice)
        obj.put("settlementReference", record.settlementReference)
        obj.put("predictedPrice", record.predictedPrice)
        obj.put("decision", record.decision)
        obj.put("score", record.score)
        obj.put("strength", record.strength)
        obj.put("predictionHorizon", record.predictionHorizon)
        obj.put("maturityTimestamp", record.maturityTimestamp)
        obj.put("actualPrice", record.actualPrice ?: JSONObject.NULL)
        obj.put("result", record.result ?: "PENDING")

        // 30s & 90s dual horizon tracking
        obj.put("actualPrice30s", record.actualPrice30s ?: JSONObject.NULL)
        obj.put("result30s", record.result30s ?: "PENDING")
        obj.put("projectedPrice90s", record.projectedPrice90s)
        obj.put("projectedDecision90s", record.projectedDecision90s)
        obj.put("maturityTimestamp90s", record.maturityTimestamp90s)
        obj.put("actualPrice90s", record.actualPrice90s ?: JSONObject.NULL)
        obj.put("result90s", record.result90s ?: "PENDING")

        // Kalshi order & execution details
        obj.put("kalshiContractTicker", record.kalshiContractTicker ?: JSONObject.NULL)
        obj.put("kalshiOrderId", record.kalshiOrderId ?: JSONObject.NULL)
        obj.put("kalshiOrderStatus", record.kalshiOrderStatus ?: JSONObject.NULL)
        obj.put("kalshiFilledCount", record.kalshiFilledCount ?: JSONObject.NULL)
        obj.put("kalshiOrderPrice", record.kalshiOrderPrice ?: JSONObject.NULL)

        val inputsObj = JSONObject()
        val inp = record.inputs
        inputsObj.put("ema9", inp.ema9)
        inputsObj.put("ema21", inp.ema21)
        inputsObj.put("rsi", inp.rsi)
        inputsObj.put("momentum", inp.momentum)
        inputsObj.put("velocity", inp.velocity)
        inputsObj.put("acceleration", inp.acceleration)
        inputsObj.put("volatility", inp.volatility)
        inputsObj.put("volume", inp.volume)
        inputsObj.put("volumeChange", inp.volumeChange)
        inputsObj.put("buffer", inp.buffer)
        inputsObj.put("bidAskSpread", inp.bidAskSpread)
        inputsObj.put("exchangeAgreement", inp.exchangeAgreement)
        inputsObj.put("formulaDisplay", inp.formulaDisplay)

        obj.put("inputs", inputsObj)
        return obj
    }

    fun exportFormattedJson(limit: Int = 10): String {
        val array = JSONArray()
        for (rec in getRecentPredictions(limit)) {
            array.put(toJson(rec))
        }
        return array.toString(2)
    }
}
