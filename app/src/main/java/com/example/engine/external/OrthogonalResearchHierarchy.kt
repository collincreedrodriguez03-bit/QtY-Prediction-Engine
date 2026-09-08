package com.example.engine.external

/**
 * Mandate 8: Prioritize Orthogonal Information Hierarchy.
 *
 * Strict information tiering:
 * Priority 1: Kaiko / CoinAPI / Amberdata (Order-book depth & trade-flow microstructure)
 * Priority 2: Deribit/DVOL + CME (Derivatives implied volatility & institutional basis)
 * Priority 3: CoinGlass (Forced liquidations & liquidation clusters)
 * Priority 4: CryptoQuant / Glassnode / Nansen / Arkham (Net entity exchange flows & whale reserves)
 *
 * ARCHITECTURAL RULE:
 * Strictly FORBIDDEN from adding duplicate EMA/RSI/Oscillator feeds as external votes.
 * All external data remains in the research pipeline and DOES NOT alter production model
 * scores until empirical ablation and Out-Of-Sample (OOS) testing prove incremental edge.
 */
enum class InformationPriorityTier(val priorityRank: Int, val description: String) {
    PRIORITY_1_MICROSTRUCTURE(1, "Kaiko/CoinAPI/Amberdata - Order-book depth and trade-flow microstructure"),
    PRIORITY_2_DERIVATIVES(2, "Deribit/DVOL/CME - Options volatility and institutional futures basis"),
    PRIORITY_3_LIQUIDATIONS(3, "CoinGlass - Forced liquidation clusters and cascade risk"),
    PRIORITY_4_ONCHAIN_ENTITY(4, "CryptoQuant/Glassnode/Nansen/Arkham - Net entity exchange flows and whale reserves")
}

data class OrthogonalFeatureMetadata(
    val featureName: String,
    val sourceProvider: String,
    val tier: InformationPriorityTier,
    val isRedundantOscillator: Boolean = false,
    val isApprovedForProduction: Boolean = false // MUST REMAIN FALSE until empirical OOS ablation
) {
    init {
        require(!isRedundantOscillator) {
            "Redundant technical oscillators (e.g. secondary EMA/RSI feeds) are strictly forbidden under Mandate 8"
        }
    }
}

object OrthogonalResearchRegistry {
    val REGISTERED_RESEARCH_FEATURES = listOf(
        OrthogonalFeatureMetadata("ORDER_BOOK_IMBALANCE", "KAIKO_COINAPI_AMBERDATA", InformationPriorityTier.PRIORITY_1_MICROSTRUCTURE),
        OrthogonalFeatureMetadata("TRADE_FLOW_AGGRESSION", "KAIKO_COINAPI_AMBERDATA", InformationPriorityTier.PRIORITY_1_MICROSTRUCTURE),
        OrthogonalFeatureMetadata("DERIBIT_DVOL_INDEX", "DERIBIT", InformationPriorityTier.PRIORITY_2_DERIVATIVES),
        OrthogonalFeatureMetadata("CME_FUTURES_BASIS", "CME", InformationPriorityTier.PRIORITY_2_DERIVATIVES),
        OrthogonalFeatureMetadata("COINGLASS_LIQUIDATION_RISK", "COINGLASS", InformationPriorityTier.PRIORITY_3_LIQUIDATIONS),
        OrthogonalFeatureMetadata("CRYPTOQUANT_WHALE_MOMENTUM", "CRYPTOQUANT", InformationPriorityTier.PRIORITY_4_ONCHAIN_ENTITY),
        OrthogonalFeatureMetadata("GLASSNODE_EXCHANGE_NETFLOW", "GLASSNODE", InformationPriorityTier.PRIORITY_4_ONCHAIN_ENTITY)
    )
}
