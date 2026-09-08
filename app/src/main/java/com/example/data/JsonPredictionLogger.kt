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

        // 30s evaluation tracking
        obj.put("actualPrice30s", record.actualPrice30s ?: JSONObject.NULL)
        obj.put("result30s", record.result30s ?: "PENDING")

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

        // Multi-Horizon Research Forecasts (5s, 10s, 30s, 60s, 90s, 120s, 180s, 240s, 300s, 600s, 900s, 1200s)
        if (record.horizonForecasts.isNotEmpty()) {
            val horizonsArray = JSONArray()
            for (h in record.horizonForecasts) {
                val hObj = JSONObject()
                hObj.put("horizonSeconds", h.horizonSeconds)
                hObj.put("inputTimestamp", h.inputTimestamp)
                hObj.put("maturityTimestamp", h.maturityTimestamp)
                hObj.put("modelVersion", h.modelVersion)
                hObj.put("score", h.score)
                hObj.put("decision", h.decision)
                hObj.put("strength", h.strength)
                hObj.put("predictedPrice", h.predictedPrice)
                hObj.put("currentPrice", h.currentPrice)
                hObj.put("settlementReference", h.settlementReference)
                hObj.put("actualPrice", h.actualPrice ?: JSONObject.NULL)
                hObj.put("result", h.result ?: "PENDING")
                hObj.put("resolvedTimestamp", h.resolvedTimestamp ?: JSONObject.NULL)

                val provObj = JSONObject()
                provObj.put("sourceExchange", h.provenance.sourceExchange)
                provObj.put("marketTimestamp", h.provenance.marketTimestamp)
                provObj.put("localReceiptTimestamp", h.provenance.localReceiptTimestamp)
                provObj.put("isResearchAdvisory", h.provenance.isResearchAdvisory)
                provObj.put("formulaDisplay", h.provenance.formulaDisplay)
                hObj.put("provenance", provObj)

                horizonsArray.put(hObj)
            }
            obj.put("multiHorizonForecasts", horizonsArray)
        }

        // Research External Features
        if (record.researchExternalFeatures != null) {
            val ext = record.researchExternalFeatures
            val extObj = JSONObject()
            extObj.put("isAnyAvailable", ext.isAnyAvailable)
            extObj.put("activeAvailableCount", ext.activeAvailableCount)

            val tvObj = JSONObject()
            tvObj.put("isAvailable", ext.tradingViewTrendScore.isAvailable)
            tvObj.put("normalizedValue", ext.tradingViewTrendScore.normalizedValue ?: JSONObject.NULL)
            tvObj.put("provenanceStatus", ext.tradingViewTrendScore.provenance.provenanceStatus.name)
            extObj.put("tradingViewTrendScore", tvObj)

            val cqObj = JSONObject()
            cqObj.put("isAvailable", ext.cryptoQuantWhaleMomentum.isAvailable)
            cqObj.put("normalizedValue", ext.cryptoQuantWhaleMomentum.normalizedValue ?: JSONObject.NULL)
            cqObj.put("provenanceStatus", ext.cryptoQuantWhaleMomentum.provenance.provenanceStatus.name)
            extObj.put("cryptoQuantWhaleMomentum", cqObj)

            val gnObj = JSONObject()
            gnObj.put("isAvailable", ext.glassnodeEntityFlowDirection.isAvailable)
            gnObj.put("normalizedValue", ext.glassnodeEntityFlowDirection.normalizedValue ?: JSONObject.NULL)
            gnObj.put("provenanceStatus", ext.glassnodeEntityFlowDirection.provenance.provenanceStatus.name)
            extObj.put("glassnodeEntityFlowDirection", gnObj)

            val cgObj = JSONObject()
            cgObj.put("isAvailable", ext.coinGlassLiquidationRisk.isAvailable)
            cgObj.put("normalizedValue", ext.coinGlassLiquidationRisk.normalizedValue ?: JSONObject.NULL)
            cgObj.put("provenanceStatus", ext.coinGlassLiquidationRisk.provenance.provenanceStatus.name)
            extObj.put("coinGlassLiquidationRisk", cgObj)

            val cgdObj = JSONObject()
            cgdObj.put("isAvailable", ext.coinGlassLiquidationDirection.isAvailable)
            cgdObj.put("normalizedValue", ext.coinGlassLiquidationDirection.normalizedValue ?: JSONObject.NULL)
            cgdObj.put("provenanceStatus", ext.coinGlassLiquidationDirection.provenance.provenanceStatus.name)
            extObj.put("coinGlassLiquidationDirection", cgdObj)

            obj.put("researchExternalFeatures", extObj)
        }

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
