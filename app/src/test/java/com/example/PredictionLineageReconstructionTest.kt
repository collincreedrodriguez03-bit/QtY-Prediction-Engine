package com.example

import com.example.data.EngineRepository
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

/**
 * P0 Mandate 1: Complete Prediction Data Lineage and Reconstruction Test.
 *
 * Verifies that every field of PredictionRecord is persisted without loss
 * into PredictionEntity and reconstructed identically.
 */
class PredictionLineageReconstructionTest {

    @Test
    fun testCompletePredictionLineageBidirectionalReconstruction() {
        val originalSnapshot = IndicatorSnapshot(
            ema9 = 91250.5,
            ema21 = 91100.2,
            rsi = 68.4,
            momentum = 145.2,
            velocity = 12.8,
            acceleration = 3.2,
            volatility = 42.1,
            volume = 1250.0,
            volumeChange = 1.45,
            buffer = 180.0,
            bidAskSpread = 0.50,
            exchangeAgreement = "STRONG_AGREEMENT",
            formulaDisplay = "S(t) = 0.25(EMA) + 0.20(RSI) + ..."
        )

        val origTimestamp = 1715000000000L
        val predictionId = "pred_" + UUID.randomUUID().toString()

        val originalRecord = PredictionRecord(
            predictionId = predictionId,
            timestamp = origTimestamp,
            inputs = originalSnapshot,
            decision = "UP",
            score = 0.742,
            strength = "STRONG",
            predictedPrice = 91380.0,
            currentPrice = 91250.0,
            settlementReference = 91245.0,
            settlementMethodology = "15M_ROLLING_WINDOW",
            predictionHorizon = 30,
            maturityTimestamp = origTimestamp + 30_000L,
            calibratedScore = 0.725,
            actualPrice = 91390.0,
            result = "CORRECT",
            projectedPrice90s = 91490.0,
            projectedDecision90s = "UP",
            actualPrice30s = 91390.0,
            result30s = "CORRECT",
            maturityTimestamp90s = origTimestamp + 90_000L,
            actualPrice90s = 91510.0,
            result90s = "CORRECT",
            sourceExchange = "COINBASE_PRO",
            marketTimestamp = origTimestamp - 50L,
            kalshiContractTicker = "KXBTCD-24MAY06-T91250",
            strikePrice = 91250.0,
            kalshiOrderId = "ord_kalshi_99812",
            kalshiOrderStatus = "filled",
            kalshiFilledCount = 3,
            kalshiOrderPrice = 62,
            executionPrice = 91252.0
        )

        // Convert to database entity
        val entity = EngineRepository.toEntity(originalRecord)

        // Verify entity fields
        assertEquals(originalRecord.predictionId, entity.predictionId)
        assertEquals(originalRecord.timestamp, entity.timestamp)
        assertEquals(originalRecord.decision, entity.decision)
        assertEquals(originalRecord.score, entity.score, 1e-6)
        assertEquals(originalRecord.raw_model_score, entity.score, 1e-6)
        assertEquals(originalRecord.strength, entity.strength)
        assertEquals(originalRecord.currentPrice, entity.currentPrice, 1e-6)
        assertEquals(originalRecord.predictedPrice, entity.predictedPrice, 1e-6)
        assertEquals(originalRecord.settlementReference, entity.settlementReference, 1e-6)
        assertEquals(originalRecord.settlementMethodology, entity.settlementMethodology)
        assertEquals(originalRecord.predictionHorizon, entity.predictionHorizon)
        assertEquals(originalRecord.maturityTimestamp, entity.maturityTimestamp)
        assertEquals(originalRecord.calibratedScore, entity.calibratedScore)
        assertEquals(originalRecord.actualPrice, entity.actualPrice)
        assertEquals(originalRecord.result, entity.result)
        assertEquals(originalRecord.projectedPrice90s, entity.projectedPrice90s, 1e-6)
        assertEquals(originalRecord.projectedDecision90s, entity.projectedDecision90s)
        assertEquals(originalRecord.maturityTimestamp90s, entity.maturityTimestamp90s)
        assertEquals(originalRecord.actualPrice90s, entity.actualPrice90s)
        assertEquals(originalRecord.result90s, entity.result90s)
        assertEquals(originalRecord.actualPrice30s, entity.actualPrice30s)
        assertEquals(originalRecord.result30s, entity.result30s)
        assertEquals(originalRecord.sourceExchange, entity.sourceExchange)
        assertEquals(originalRecord.marketTimestamp, entity.marketTimestamp)
        assertEquals(originalRecord.kalshiContractTicker, entity.kalshiContractTicker)
        assertEquals(originalRecord.strikePrice, entity.strikePrice)
        assertEquals(originalRecord.kalshiOrderId, entity.kalshiOrderId)
        assertEquals(originalRecord.kalshiOrderStatus, entity.kalshiOrderStatus)
        assertEquals(originalRecord.kalshiFilledCount, entity.kalshiFilledCount)
        assertEquals(originalRecord.kalshiOrderPrice, entity.kalshiOrderPrice)
        assertEquals(originalRecord.executionPrice, entity.executionPrice)

        // Snapshot fields
        assertEquals(originalSnapshot.ema9, entity.ema9, 1e-6)
        assertEquals(originalSnapshot.ema21, entity.ema21, 1e-6)
        assertEquals(originalSnapshot.rsi, entity.rsi, 1e-6)
        assertEquals(originalSnapshot.momentum, entity.momentum, 1e-6)
        assertEquals(originalSnapshot.velocity, entity.velocity, 1e-6)
        assertEquals(originalSnapshot.acceleration, entity.acceleration, 1e-6)
        assertEquals(originalSnapshot.volatility, entity.volatility, 1e-6)
        assertEquals(originalSnapshot.volume, entity.volume, 1e-6)
        assertEquals(originalSnapshot.volumeChange, entity.volumeChange, 1e-6)
        assertEquals(originalSnapshot.buffer, entity.buffer, 1e-6)
        assertEquals(originalSnapshot.bidAskSpread, entity.bidAskSpread, 1e-6)
        assertEquals(originalSnapshot.exchangeAgreement, entity.exchangeAgreement)
        assertEquals(originalSnapshot.formulaDisplay, entity.formulaDisplay)

        // Reconstruct back to in-memory model
        val reconstructed = EngineRepository.toRecord(entity)

        // Verify total identity between original and reconstructed record
        assertEquals(originalRecord.predictionId, reconstructed.predictionId)
        assertEquals(originalRecord.timestamp, reconstructed.timestamp)
        assertEquals(originalRecord.decision, reconstructed.decision)
        assertEquals(originalRecord.score, reconstructed.score, 1e-6)
        assertEquals(originalRecord.raw_model_score, reconstructed.raw_model_score, 1e-6)
        assertEquals(originalRecord.strength, reconstructed.strength)
        assertEquals(originalRecord.predictedPrice, reconstructed.predictedPrice, 1e-6)
        assertEquals(originalRecord.currentPrice, reconstructed.currentPrice, 1e-6)
        assertEquals(originalRecord.settlementReference, reconstructed.settlementReference, 1e-6)
        assertEquals(originalRecord.settlementMethodology, reconstructed.settlementMethodology)
        assertEquals(originalRecord.predictionHorizon, reconstructed.predictionHorizon)
        assertEquals(originalRecord.maturityTimestamp, reconstructed.maturityTimestamp)
        assertEquals(originalRecord.calibratedScore, reconstructed.calibratedScore)
        assertEquals(originalRecord.actualPrice, reconstructed.actualPrice)
        assertEquals(originalRecord.result, reconstructed.result)
        assertEquals(originalRecord.projectedPrice90s, reconstructed.projectedPrice90s, 1e-6)
        assertEquals(originalRecord.projectedDecision90s, reconstructed.projectedDecision90s)
        assertEquals(originalRecord.maturityTimestamp90s, reconstructed.maturityTimestamp90s)
        assertEquals(originalRecord.actualPrice90s, reconstructed.actualPrice90s)
        assertEquals(originalRecord.result90s, reconstructed.result90s)
        assertEquals(originalRecord.actualPrice30s, reconstructed.actualPrice30s)
        assertEquals(originalRecord.result30s, reconstructed.result30s)
        assertEquals(originalRecord.sourceExchange, reconstructed.sourceExchange)
        assertEquals(originalRecord.marketTimestamp, reconstructed.marketTimestamp)
        assertEquals(originalRecord.kalshiContractTicker, reconstructed.kalshiContractTicker)
        assertEquals(originalRecord.strikePrice, reconstructed.strikePrice)
        assertEquals(originalRecord.kalshiOrderId, reconstructed.kalshiOrderId)
        assertEquals(originalRecord.kalshiOrderStatus, reconstructed.kalshiOrderStatus)
        assertEquals(originalRecord.kalshiFilledCount, reconstructed.kalshiFilledCount)
        assertEquals(originalRecord.kalshiOrderPrice, reconstructed.kalshiOrderPrice)
        assertEquals(originalRecord.executionPrice, reconstructed.executionPrice)

        // Snapshot identity
        assertEquals(originalRecord.inputs, reconstructed.inputs)
    }
}
