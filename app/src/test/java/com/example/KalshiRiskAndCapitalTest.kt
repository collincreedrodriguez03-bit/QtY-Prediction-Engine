package com.example

import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.kalshi.KalshiRiskEngine
import com.example.kalshi.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0 Mandate 4 & 5: Risk-Based Position Sizing and Profit-Only Capital Rule Test.
 *
 * Requirements:
 * - Trade 1: uses configured starting capital.
 * - Trade 1 WIN: only REALIZED profit becomes eligible capital for Trade 2.
 * - Trade 1 LOSS: eligible capital for Trade 2 = $0.
 * - UNREALIZED P&L: NEVER becomes eligible trading capital.
 * - Original capital: NEVER automatically recycled into subsequent trades.
 * - Higher risk -> smaller allocation.
 * - Lower risk -> larger permitted allocation.
 * - Hard maximum contract limit remains enforced (5 contracts).
 * - No position may bypass the risk engine.
 */
class KalshiRiskAndCapitalTest {

    private lateinit var riskEngine: KalshiRiskEngine

    @Before
    fun setUp() {
        // Initialize with $10 starting capital and max 5 contracts hard cap
        riskEngine = KalshiRiskEngine(startingCapitalDollars = 10.0, maxContractsHardCap = 5)
    }

    private fun createSamplePrediction(strength: String = "STRONG"): PredictionRecord {
        return PredictionRecord(
            inputs = IndicatorSnapshot(volatility = 20.0),
            decision = "UP",
            score = if (strength == "STRONG") 0.85 else if (strength == "MEDIUM") 0.72 else 0.58,
            strength = strength,
            predictedPrice = 90100.0,
            currentPrice = 90000.0
        )
    }

    @Test
    fun testTrade1UsesConfiguredStartingCapital() {
        assertEquals(0, riskEngine.getTradeCount())

        val predStrong = createSamplePrediction("STRONG")
        val contractCostCents = 50 // $0.50 per contract

        val eval = riskEngine.evaluateOrderSizing(predStrong, contractCostCents)

        assertTrue(eval.isApproved)
        assertEquals(10.0, eval.eligibleCapitalDollars, 1e-4)
        assertEquals(RiskLevel.LOW, eval.riskLevel)
        assertEquals(1.0, eval.allocationFraction, 1e-4)
        assertEquals(10.0, eval.allocatedCapitalDollars, 1e-4)
        // $10 / $0.50 = 20, but capped by hard limit 5
        assertEquals(5, eval.actualOrderSize)
        assertEquals(5, riskEngine.maxContractsHardCap)
    }

    @Test
    fun testTrade1WinAllowsOnlyRealizedProfitForTrade2() {
        val contractCostCents = 50

        // Simulate Trade 1 WIN: Realized profit = $3.50 (e.g. bought contracts at 50c, settled at $1.00)
        riskEngine.recordSettlement(isWin = true, realizedProfit = 3.50)
        assertEquals(1, riskEngine.getTradeCount())
        assertEquals(3.50, riskEngine.getRealizedProfitDollars(), 1e-4)

        // Trade 2: Only realized profit ($3.50) is eligible capital. Original $10 is NOT recycled.
        val predStrong = createSamplePrediction("STRONG")
        val evalTrade2 = riskEngine.evaluateOrderSizing(predStrong, contractCostCents)

        assertTrue(evalTrade2.isApproved)
        assertEquals(3.50, evalTrade2.eligibleCapitalDollars, 1e-4)
        // Under STRONG risk (LOW risk -> 100% allocation of eligible capital = $3.50)
        assertEquals(3.50, evalTrade2.allocatedCapitalDollars, 1e-4)
        // $3.50 / $0.50 = 7 contracts, capped at hard cap 5
        assertEquals(5, evalTrade2.actualOrderSize)
    }

    @Test
    fun testTrade1LossResultsInZeroEligibleCapitalForTrade2() {
        val contractCostCents = 50

        // Simulate Trade 1 LOSS
        riskEngine.recordSettlement(isWin = false, realizedProfit = 0.0)
        assertEquals(1, riskEngine.getTradeCount())
        assertEquals("LOSS", riskEngine.getLastTradeOutcome())
        assertEquals(0.0, riskEngine.getRealizedProfitDollars(), 1e-4)

        // Trade 2: Eligible capital is $0.00
        val predStrong = createSamplePrediction("STRONG")
        val evalTrade2 = riskEngine.evaluateOrderSizing(predStrong, contractCostCents)

        assertFalse("Trade 2 must be rejected after Trade 1 LOSS", evalTrade2.isApproved)
        assertEquals(0.0, evalTrade2.eligibleCapitalDollars, 1e-4)
        assertEquals(0.0, evalTrade2.allocatedCapitalDollars, 1e-4)
        assertEquals(0, evalTrade2.actualOrderSize)
        assertTrue(evalTrade2.reason.contains("Eligible capital is $0.00"))
    }

    @Test
    fun testHigherRiskResultsInSmallerAllocation() {
        // Trade 1 setup
        val contractCostCents = 50 // $0.50

        // 1. Low risk (STRONG strength): 100% allocation
        val evalLowRisk = riskEngine.evaluateOrderSizing(createSamplePrediction("STRONG"), contractCostCents)
        assertEquals(RiskLevel.LOW, evalLowRisk.riskLevel)
        assertEquals(1.00, evalLowRisk.allocationFraction, 1e-4)
        assertEquals(10.0, evalLowRisk.allocatedCapitalDollars, 1e-4)

        // 2. Medium risk (MEDIUM strength): 50% allocation
        val evalMedRisk = riskEngine.evaluateOrderSizing(createSamplePrediction("MEDIUM"), contractCostCents)
        assertEquals(RiskLevel.MEDIUM, evalMedRisk.riskLevel)
        assertEquals(0.50, evalMedRisk.allocationFraction, 1e-4)
        assertEquals(5.0, evalMedRisk.allocatedCapitalDollars, 1e-4)
        // $5.0 / $0.50 = 10 contracts, capped at 5
        assertEquals(5, evalMedRisk.actualOrderSize)

        // 3. High risk (WEAK strength): 25% allocation
        val evalHighRisk = riskEngine.evaluateOrderSizing(createSamplePrediction("WEAK"), contractCostCents)
        assertEquals(RiskLevel.HIGH, evalHighRisk.riskLevel)
        assertEquals(0.25, evalHighRisk.allocationFraction, 1e-4)
        assertEquals(2.50, evalHighRisk.allocatedCapitalDollars, 1e-4)
        // $2.50 / $0.50 = 5 contracts
        assertEquals(5, evalHighRisk.actualOrderSize)
    }

    @Test
    fun testHardMaximumContractCapIsEnforced() {
        // Even with huge starting capital ($1000), actualOrderSize NEVER exceeds maxContractsHardCap (5)
        val largeCapitalEngine = KalshiRiskEngine(startingCapitalDollars = 1000.0, maxContractsHardCap = 5)
        val eval = largeCapitalEngine.evaluateOrderSizing(createSamplePrediction("STRONG"), 10) // $0.10 contract
        assertTrue(eval.isApproved)
        assertEquals(5, eval.actualOrderSize)
    }

    @Test
    fun testExtremeRiskBlocksOrder() {
        // High volatility (> 15 bps) on a WEAK prediction elevates risk to EXTREME
        val predWeak = createSamplePrediction("WEAK")
        val eval = riskEngine.evaluateOrderSizing(predWeak, 50, volatilityBps = 25.0)

        assertEquals(RiskLevel.EXTREME, eval.riskLevel)
        assertFalse(eval.isApproved)
        assertEquals(0, eval.actualOrderSize)
        assertTrue(eval.reason.contains("EXTREME"))
    }
}
