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
    fun updateResolvedRecord(predictionId: String, actualPrice: Double, result: String) {
        val index = memoryLog.indexOfFirst { it.predictionId == predictionId }
        if (index != -1) {
            val rec = memoryLog[index]
            rec.actualPrice = actualPrice
            rec.result = result
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
        obj.put("predictedPrice", record.predictedPrice)
        obj.put("decision", record.decision)
        obj.put("score", record.score)
        obj.put("strength", record.strength)
        obj.put("predictionHorizon", record.predictionHorizon)
        obj.put("maturityTimestamp", record.maturityTimestamp)
        obj.put("actualPrice", record.actualPrice ?: JSONObject.NULL)
        obj.put("result", record.result ?: "PENDING")

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
