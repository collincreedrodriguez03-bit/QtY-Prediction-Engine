package com.example.kalshi

import com.example.engine.PredictionRecord

/**
 * Risk levels determining capital allocation fraction.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}

/**
 * Comprehensive risk evaluation and sizing result.
 * Audit trail tracks eligible capital, allocation fraction, and resulting contract sizing.
 */
data class RiskEvaluation(
    val riskLevel: RiskLevel,
    val allocationFraction: Double, // 0.0 to 1.0
    val eligibleCapitalDollars: Double,
    val allocatedCapitalDollars: Double,
    val contractCostDollars: Double,
    val calculatedContracts: Int,
    val actualOrderSize: Int, // Enforces MAX_CONTRACTS_PER_ORDER hard limit
    val isApproved: Boolean,
    val reason: String
)

/**
 * QtY Risk-Based Position Sizing and Profit-Only Capital Engine.
 *
 * Core Rules:
 * 1. Trade 1 uses configured starting capital.
 * 2. Trade 1 WIN: Only REALIZED profit becomes eligible capital for Trade 2.
 * 3. Trade 1 LOSS: Eligible capital for Trade 2 = $0.00.
 * 4. UNREALIZED P&L NEVER becomes eligible trading capital.
 * 5. Original capital is NEVER automatically recycled into subsequent trades.
 * 6. Risk determines permitted allocation:
 *    - Higher risk -> smaller allocation (e.g. 25% or 0 contracts).
 *    - Lower risk -> larger permitted allocation (up to 100% of eligible capital).
 * 7. Hard maximum contract limit remains enforced (max 5 contracts).
 * 8. No position may bypass the risk engine.
 */
class KalshiRiskEngine(
    val startingCapitalDollars: Double = 10.0,
    val maxContractsHardCap: Int = 5
) {
    private var tradeCount: Int = 0
    private var realizedProfitDollars: Double = 0.0
    private var lastTradeOutcome: String? = null // "WIN", "LOSS", null

    @Synchronized
    fun getTradeCount(): Int = tradeCount

    @Synchronized
    fun getRealizedProfitDollars(): Double = realizedProfitDollars

    @Synchronized
    fun getLastTradeOutcome(): String? = lastTradeOutcome

    /**
     * Record trade settlement.
     * Only REALIZED profit from settled winning trades becomes eligible capital for subsequent trades.
     * Unrealized P&L never becomes eligible capital.
     * Original capital is NEVER automatically recycled.
     */
    @Synchronized
    fun recordSettlement(isWin: Boolean, realizedProfit: Double) {
        tradeCount++
        lastTradeOutcome = if (isWin) "WIN" else "LOSS"
        if (isWin) {
            realizedProfitDollars = maxOf(0.0, realizedProfit)
        } else {
            realizedProfitDollars = 0.0
        }
    }

    /**
     * Resets risk engine state (e.g. on new session or manual reset).
     */
    @Synchronized
    fun reset() {
        tradeCount = 0
        realizedProfitDollars = 0.0
        lastTradeOutcome = null
    }

    /**
     * Evaluates risk and determines position sizing.
     * Every trade must pass through this calculation.
     */
    @Synchronized
    fun evaluateOrderSizing(
        prediction: PredictionRecord,
        contractPriceCents: Int,
        volatilityBps: Double = 0.0
    ): RiskEvaluation {
        val contractCostDollars = (contractPriceCents.coerceIn(1, 99)) / 100.0

        // 1. Profit-Only Capital Accounting
        val eligibleCapital: Double = if (tradeCount == 0) {
            // Trade 1: uses configured starting capital
            startingCapitalDollars
        } else {
            // Subsequent trades: ONLY realized profit is eligible. Original capital NEVER recycled.
            if (lastTradeOutcome == "LOSS" || realizedProfitDollars <= 0.0) {
                0.0
            } else {
                realizedProfitDollars
            }
        }

        if (eligibleCapital <= 0.0) {
            return RiskEvaluation(
                riskLevel = RiskLevel.HIGH,
                allocationFraction = 0.0,
                eligibleCapitalDollars = 0.0,
                allocatedCapitalDollars = 0.0,
                contractCostDollars = contractCostDollars,
                calculatedContracts = 0,
                actualOrderSize = 0,
                isApproved = false,
                reason = "Eligible capital is $0.00 (Trade 1 Loss or Zero Realized Profit). Original capital is not recycled."
            )
        }

        // 2. Risk Evaluation
        // Base risk from prediction strength / model confidence
        val baseRiskLevel = when (prediction.strength) {
            "STRONG" -> RiskLevel.LOW
            "MEDIUM" -> RiskLevel.MEDIUM
            "WEAK" -> RiskLevel.HIGH
            else -> RiskLevel.EXTREME
        }

        // Elevate risk if high volatility (> 15 bps on 20s window)
        val effectiveRiskLevel = if (volatilityBps > 15.0 && baseRiskLevel != RiskLevel.EXTREME) {
            when (baseRiskLevel) {
                RiskLevel.LOW -> RiskLevel.MEDIUM
                RiskLevel.MEDIUM -> RiskLevel.HIGH
                else -> RiskLevel.EXTREME
            }
        } else {
            baseRiskLevel
        }

        if (effectiveRiskLevel == RiskLevel.EXTREME) {
            return RiskEvaluation(
                riskLevel = RiskLevel.EXTREME,
                allocationFraction = 0.0,
                eligibleCapitalDollars = eligibleCapital,
                allocatedCapitalDollars = 0.0,
                contractCostDollars = contractCostDollars,
                calculatedContracts = 0,
                actualOrderSize = 0,
                isApproved = false,
                reason = "Risk level is EXTREME. Order blocked by risk engine."
            )
        }

        // 3. Risk-Based Allocation: Higher risk -> smaller allocation, Lower risk -> larger permitted allocation
        val allocationFraction = when (effectiveRiskLevel) {
            RiskLevel.LOW -> 1.00    // 100% of eligible capital permitted
            RiskLevel.MEDIUM -> 0.50 // 50% of eligible capital permitted
            RiskLevel.HIGH -> 0.25   // 25% of eligible capital permitted
            RiskLevel.EXTREME -> 0.00
        }

        val allocatedCapital = eligibleCapital * allocationFraction
        val permittedContracts = (allocatedCapital / contractCostDollars).toInt()
        val finalContracts = permittedContracts.coerceIn(0, maxContractsHardCap)

        if (finalContracts <= 0) {
            return RiskEvaluation(
                riskLevel = effectiveRiskLevel,
                allocationFraction = allocationFraction,
                eligibleCapitalDollars = eligibleCapital,
                allocatedCapitalDollars = allocatedCapital,
                contractCostDollars = contractCostDollars,
                calculatedContracts = permittedContracts,
                actualOrderSize = 0,
                isApproved = false,
                reason = "Calculated order size is 0 contract(s) under allocated capital ($${String.format(java.util.Locale.US, "%.2f", allocatedCapital)})."
            )
        }

        return RiskEvaluation(
            riskLevel = effectiveRiskLevel,
            allocationFraction = allocationFraction,
            eligibleCapitalDollars = eligibleCapital,
            allocatedCapitalDollars = allocatedCapital,
            contractCostDollars = contractCostDollars,
            calculatedContracts = permittedContracts,
            actualOrderSize = finalContracts,
            isApproved = true,
            reason = "Approved: Risk=${effectiveRiskLevel.name}, Eligible=$${String.format(java.util.Locale.US, "%.2f", eligibleCapital)}, Alloc=$${String.format(java.util.Locale.US, "%.2f", allocatedCapital)} -> $finalContracts contract(s)"
        )
    }
}
