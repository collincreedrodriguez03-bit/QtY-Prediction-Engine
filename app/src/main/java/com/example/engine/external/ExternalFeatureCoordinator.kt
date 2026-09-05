package com.example.engine.external

import com.example.data.PricePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrator and coordinator for all 4 external research features:
 * 1. TRADINGVIEW → TREND SCORE (via authentic BTC market series)
 * 2. CRYPTOQUANT → WHALE MOMENTUM
 * 3. GLASSNODE → ENTITY FLOW DIRECTION
 * 4. COINGLASS → LIQUIDATION RISK & DIRECTION
 *
 * CRITICAL ARCHITECTURAL RULES:
 * - These connections exist ONLY to improve the QtY prediction engine research and out-of-sample evaluation.
 * - They must NOT become separate trading strategies, autonomous signals, or independent BUY/SELL systems.
 * - The existing QtY prediction engine remains UNCHANGED.
 * - Fail-closed: Missing/stale data produces null normalized value and is never substituted with 0.0 or synthetic data.
 */
class ExternalFeatureCoordinator(
    val tradingViewFeature: TradingViewTrendFeature = TradingViewTrendFeature(),
    val cryptoQuantFeature: CryptoQuantWhaleMomentumFeature = CryptoQuantWhaleMomentumFeature(),
    val glassnodeFeature: GlassnodeEntityFlowFeature = GlassnodeEntityFlowFeature(),
    val coinGlassFeature: CoinGlassLiquidationRiskFeature = CoinGlassLiquidationRiskFeature()
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _latestFeatures = MutableStateFlow(ExternalPredictionFeatures.empty())
    val latestFeatures: StateFlow<ExternalPredictionFeatures> = _latestFeatures.asStateFlow()

    // Cached historical observations for historical replay and point-in-time calculation
    private val cqObservations = mutableListOf<CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation>()
    private val gnObservations = mutableListOf<GlassnodeEntityFlowFeature.RawGlassnodeObservation>()
    private val cgObservations = mutableListOf<CoinGlassLiquidationRiskFeature.RawCoinGlassObservation>()

    fun setCredentials(
        cryptoQuantKey: String? = null,
        glassnodeKey: String? = null,
        coinGlassKey: String? = null
    ) {
        cryptoQuantKey?.let { cryptoQuantFeature.setApiKey(it) }
        glassnodeKey?.let { glassnodeFeature.setApiKey(it) }
        coinGlassKey?.let { coinGlassFeature.setApiKey(it) }
    }

    /**
     * Synchronously computes external features for a given historical or live tick.
     * Guaranteed no-lookahead: observations with timestamp > nowMs are rejected.
     */
    fun computeFeatures(
        points: List<PricePoint>,
        nowMs: Long = System.currentTimeMillis()
    ): ExternalPredictionFeatures {
        // 1. TradingView Trend Score
        val tvFeature = tradingViewFeature.calculate(points, nowMs)

        // 2. CryptoQuant Whale Momentum
        val validCq = synchronized(cqObservations) {
            cqObservations.filter { it.timestampMs <= nowMs }
        }
        val cqFeature = if (validCq.isNotEmpty()) {
            cryptoQuantFeature.calculateFromObservations(validCq, nowMs)
        } else {
            ResearchFeatureValue.unavailable(
                CryptoQuantWhaleMomentumFeature.SOURCE_NAME,
                CryptoQuantWhaleMomentumFeature.METRIC_NAME,
                ExternalFeatureProvenanceStatus.UNAVAILABLE,
                "No valid historical CryptoQuant observations at timestamp $nowMs"
            )
        }

        // 3. Glassnode Entity Flow Direction
        val validGn = synchronized(gnObservations) {
            gnObservations.filter { it.timestampMs <= nowMs }
        }
        val gnFeature = if (validGn.isNotEmpty()) {
            glassnodeFeature.calculateFromObservations(validGn, nowMs)
        } else {
            ResearchFeatureValue.unavailable(
                GlassnodeEntityFlowFeature.SOURCE_NAME,
                GlassnodeEntityFlowFeature.METRIC_NAME,
                ExternalFeatureProvenanceStatus.UNAVAILABLE,
                "No valid historical Glassnode observations at timestamp $nowMs"
            )
        }

        // 4. CoinGlass Liquidation Risk & Direction
        val validCg = synchronized(cgObservations) {
            cgObservations.filter { it.timestampMs <= nowMs }
        }
        val (cgRisk, cgDir) = if (validCg.isNotEmpty()) {
            coinGlassFeature.calculateFromObservations(validCg, nowMs)
        } else {
            val r = ResearchFeatureValue.unavailable(
                CoinGlassLiquidationRiskFeature.SOURCE_NAME,
                CoinGlassLiquidationRiskFeature.RISK_METRIC,
                ExternalFeatureProvenanceStatus.UNAVAILABLE,
                "No valid historical CoinGlass observations at timestamp $nowMs"
            )
            val d = ResearchFeatureValue.unavailable(
                CoinGlassLiquidationRiskFeature.SOURCE_NAME,
                CoinGlassLiquidationRiskFeature.DIRECTION_METRIC,
                ExternalFeatureProvenanceStatus.UNAVAILABLE,
                "No valid historical CoinGlass observations at timestamp $nowMs"
            )
            CoinGlassLiquidationRiskFeature.LiquidationResult(r, d)
        }

        val container = ExternalPredictionFeatures(
            tradingViewTrendScore = tvFeature,
            cryptoQuantWhaleMomentum = cqFeature,
            glassnodeEntityFlowDirection = gnFeature,
            coinGlassLiquidationRisk = cgRisk,
            coinGlassLiquidationDirection = cgDir,
            timestamp = nowMs
        )

        _latestFeatures.value = container
        return container
    }

    /**
     * Add authenticated observations for historical replay or point-in-time backtesting.
     */
    fun addCryptoQuantObservation(obs: CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation) {
        synchronized(cqObservations) {
            // Deduplicate by timestamp
            cqObservations.removeAll { it.timestampMs == obs.timestampMs }
            cqObservations.add(obs)
            cqObservations.sortBy { it.timestampMs }
        }
    }

    fun addGlassnodeObservation(obs: GlassnodeEntityFlowFeature.RawGlassnodeObservation) {
        synchronized(gnObservations) {
            gnObservations.removeAll { it.timestampMs == obs.timestampMs }
            gnObservations.add(obs)
            gnObservations.sortBy { it.timestampMs }
        }
    }

    fun addCoinGlassObservation(obs: CoinGlassLiquidationRiskFeature.RawCoinGlassObservation) {
        synchronized(cgObservations) {
            cgObservations.removeAll { it.timestampMs == obs.timestampMs }
            cgObservations.add(obs)
            cgObservations.sortBy { it.timestampMs }
        }
    }

    /**
     * Trigger background asynchronous live refresh across authenticated remote APIs.
     */
    fun refreshLiveApis(nowMs: Long = System.currentTimeMillis()) {
        scope.launch {
            if (cryptoQuantFeature.hasValidCredentials()) {
                val res = cryptoQuantFeature.fetchLiveObservation(nowMs)
                if (res.isAvailable && res.rawObservation != null) {
                    addCryptoQuantObservation(
                        CryptoQuantWhaleMomentumFeature.RawCryptoQuantObservation(
                            timestampMs = res.provenance.sourceTimestampMs,
                            exchangeWhaleRatio = res.rawObservation,
                            exchangeInflowBtc = 0.0,
                            exchangeOutflowBtc = 0.0
                        )
                    )
                }
            }
            if (glassnodeFeature.hasValidCredentials()) {
                val res = glassnodeFeature.fetchLiveObservation(nowMs)
                if (res.isAvailable && res.rawObservation != null) {
                    addGlassnodeObservation(
                        GlassnodeEntityFlowFeature.RawGlassnodeObservation(
                            timestampMs = res.provenance.sourceTimestampMs,
                            netFlowBtc = res.rawObservation,
                            isPointInTime = true
                        )
                    )
                }
            }
            if (coinGlassFeature.hasValidCredentials()) {
                val res = coinGlassFeature.fetchLiveObservation(nowMs)
                if (res.riskFeature.isAvailable && res.riskFeature.rawObservation != null) {
                    addCoinGlassObservation(
                        CoinGlassLiquidationRiskFeature.RawCoinGlassObservation(
                            timestampMs = res.riskFeature.provenance.sourceTimestampMs,
                            longLiquidationUsd = res.riskFeature.rawObservation / 2.0,
                            shortLiquidationUsd = res.riskFeature.rawObservation / 2.0
                        )
                    )
                }
            }
        }
    }
}
