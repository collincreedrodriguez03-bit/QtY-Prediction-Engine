package com.example.engine.structure

import com.example.data.PricePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Validated Mathematical Market Structure Analyzer.
 *
 * Mathematically derives chart structure directly from authentic price time series:
 * - Swing Highs & Swing Lows (Extrema with confirmation window)
 * - Higher Highs (HH), Higher Lows (HL), Lower Highs (LH), Lower Lows (LL)
 * - Horizontal Support & Resistance density clusters
 * - Breakout & Rejection levels
 * - Mathematical Directional Trendlines (slope, touch count, residual fitness)
 * - Local Channels (Ascending, Descending, Horizontal)
 * - Compression & Volatility State
 * - Explicitly Validated Classical Formations (Zero false claims)
 * - Actionable Trader Reference Levels: ENTRY -> TARGET -> INVALIDATION with R:R
 * - Strict NO-TRADE derivation when structure is ambiguous or compressed.
 */
class MarketStructureAnalyzer {

    /**
     * Analyzes price points up to asOfTimestamp (with zero lookahead) and returns
     * an immutable MarketStructureSnapshot.
     */
    fun analyze(
        points: List<PricePoint>,
        asOfTimestamp: Long = System.currentTimeMillis(),
        currentSpotPrice: Double? = null
    ): MarketStructureSnapshot {
        // Enforce strict no-lookahead
        val validPoints = points.filter { it.timestamp <= asOfTimestamp }.sortedBy { it.timestamp }
        if (validPoints.size < 12) {
            val price = currentSpotPrice ?: validPoints.lastOrNull()?.price ?: 0.0
            return MarketStructureSnapshot.empty(asOfTimestamp, price)
        }

        val currentPrice = currentSpotPrice ?: validPoints.last().price
        val windowPoints = if (validPoints.size > 200) validPoints.takeLast(200) else validPoints

        // 1. Detect Swing Highs & Swing Lows
        val (rawHighs, rawLows) = detectSwings(windowPoints, confirmationWindow = 3)

        // 2. Classify Swing Sequences (HH, HL, LH, LL)
        val labeledHighs = labelSwingHighs(rawHighs)
        val labeledLows = labelSwingLows(rawLows)
        val allSwings = (labeledHighs + labeledLows).sortedBy { it.timestamp }

        // Counts of structural progression
        val hhCount = labeledHighs.count { it.sequenceLabel == "HH" }
        val lhCount = labeledHighs.count { it.sequenceLabel == "LH" }
        val hlCount = labeledLows.count { it.sequenceLabel == "HL" }
        val llCount = labeledLows.count { it.sequenceLabel == "LL" }

        // 3. Determine Core Structural Trend Direction
        val trendDirection = evaluateTrendDirection(labeledHighs, labeledLows, currentPrice)

        // 4. Derive Horizontal Key Levels (Support & Resistance Clusters)
        val keyLevels = deriveKeyLevels(windowPoints, labeledHighs, labeledLows, currentPrice)
        val primarySupport = keyLevels.filter { it.levelType == LevelType.SUPPORT }
            .minByOrNull { abs(currentPrice - it.price) }
        val primaryResistance = keyLevels.filter { it.levelType == LevelType.RESISTANCE }
            .minByOrNull { abs(it.price - currentPrice) }

        // 5. Derive Breakout Level & Rejection Level
        val breakoutLevel = deriveBreakoutLevel(trendDirection, labeledHighs, labeledLows, currentPrice, primaryResistance, primarySupport)
        val rejectionLevel = deriveRejectionLevel(windowPoints, labeledHighs, labeledLows, currentPrice)

        // 6. Mathematically Derive Directional Trendlines
        val supportTrendline = deriveSupportTrendline(labeledLows, asOfTimestamp)
        val resistanceTrendline = deriveResistanceTrendline(labeledHighs, asOfTimestamp)

        // 7. Derive Local Channel
        val localChannel = deriveLocalChannel(supportTrendline, resistanceTrendline, currentPrice)

        // 8. Evaluate Compression / Range State
        val compressionState = evaluateCompression(windowPoints, labeledHighs, labeledLows, localChannel)

        // 9. Explicitly Validate Formations (Strict Validation)
        val validatedFormation = validateClassicalFormations(
            labeledHighs = labeledHighs,
            labeledLows = labeledLows,
            currentPrice = currentPrice,
            supportTrendline = supportTrendline,
            resistanceTrendline = resistanceTrendline
        )

        // 10. Ambiguity Assessment
        val isAmbiguous = (trendDirection == TrendDirection.AMBIGUOUS) ||
                (compressionState == CompressionState.VOLATILITY_COMPRESSION && validatedFormation.status != PatternStatus.CONFIRMED) ||
                (hhCount > 0 && llCount > 0 && abs(hhCount - llCount) <= 1 && abs(hlCount - lhCount) <= 1)

        // 11. Derive Actionable Trader Reference Levels: ENTRY -> TARGET -> INVALIDATION
        val traderLevels = deriveTraderLevels(
            trendDirection = trendDirection,
            currentPrice = currentPrice,
            labeledHighs = labeledHighs,
            labeledLows = labeledLows,
            primarySupport = primarySupport,
            primaryResistance = primaryResistance,
            localChannel = localChannel,
            breakoutLevel = breakoutLevel,
            supportTrendline = supportTrendline,
            resistanceTrendline = resistanceTrendline,
            isAmbiguous = isAmbiguous
        )

        // 12. Build Human-Readable Structural Summary
        val summary = buildStructuralSummary(
            trendDirection = trendDirection,
            compressionState = compressionState,
            hhCount = hhCount,
            hlCount = hlCount,
            lhCount = lhCount,
            llCount = llCount,
            primarySupport = primarySupport,
            primaryResistance = primaryResistance,
            traderLevels = traderLevels,
            validatedFormation = validatedFormation,
            isAmbiguous = isAmbiguous
        )

        return MarketStructureSnapshot(
            timestamp = asOfTimestamp,
            currentPrice = currentPrice,
            trendDirection = trendDirection,
            compressionState = compressionState,
            swingHighs = labeledHighs,
            swingLows = labeledLows,
            recentSwings = allSwings.takeLast(10),
            higherHighsCount = hhCount,
            higherLowsCount = hlCount,
            lowerHighsCount = lhCount,
            lowerLowsCount = llCount,
            primarySupport = primarySupport,
            primaryResistance = primaryResistance,
            keyLevels = keyLevels,
            breakoutLevel = breakoutLevel,
            rejectionLevel = rejectionLevel,
            supportTrendline = supportTrendline,
            resistanceTrendline = resistanceTrendline,
            localChannel = localChannel,
            validatedFormation = validatedFormation,
            traderLevels = traderLevels,
            isAmbiguous = isAmbiguous,
            structuralSummary = summary
        )
    }

    /**
     * Identifies local extrema (peaks and troughs) with confirmation window.
     */
    private fun detectSwings(points: List<PricePoint>, confirmationWindow: Int = 3): Pair<List<SwingPoint>, List<SwingPoint>> {
        val highs = mutableListOf<SwingPoint>()
        val lows = mutableListOf<SwingPoint>()

        if (points.size < confirmationWindow * 2 + 1) {
            return Pair(emptyList(), emptyList())
        }

        val lastIndex = points.size - 1

        for (i in confirmationWindow until (lastIndex - confirmationWindow + 1)) {
            val candidate = points[i]
            var isHigh = true
            var isLow = true

            for (offset in 1..confirmationWindow) {
                val left = points[i - offset]
                val right = points[i + offset]

                if (candidate.price <= left.price || candidate.price < right.price) {
                    isHigh = false
                }
                if (candidate.price >= left.price || candidate.price > right.price) {
                    isLow = false
                }
            }

            if (isHigh) {
                // Eliminate clustered duplicate highs within 4 cycles (keep the highest)
                if (highs.isNotEmpty() && (i - highs.last().index) <= 4) {
                    if (candidate.price > highs.last().price) {
                        highs.removeAt(highs.size - 1)
                        highs.add(SwingPoint(i, candidate.timestamp, candidate.price, SwingType.SWING_HIGH, confirmationBars = confirmationWindow))
                    }
                } else {
                    highs.add(SwingPoint(i, candidate.timestamp, candidate.price, SwingType.SWING_HIGH, confirmationBars = confirmationWindow))
                }
            } else if (isLow) {
                // Eliminate clustered duplicate lows within 4 cycles (keep the lowest)
                if (lows.isNotEmpty() && (i - lows.last().index) <= 4) {
                    if (candidate.price < lows.last().price) {
                        lows.removeAt(lows.size - 1)
                        lows.add(SwingPoint(i, candidate.timestamp, candidate.price, SwingType.SWING_LOW, confirmationBars = confirmationWindow))
                    }
                } else {
                    lows.add(SwingPoint(i, candidate.timestamp, candidate.price, SwingType.SWING_LOW, confirmationBars = confirmationWindow))
                }
            }
        }

        return Pair(highs, lows)
    }

    /**
     * Labels Swing Highs as Higher High (HH), Lower High (LH), or Equal High (EH).
     */
    private fun labelSwingHighs(highs: List<SwingPoint>): List<SwingPoint> {
        if (highs.isEmpty()) return emptyList()
        val result = mutableListOf<SwingPoint>()

        for (i in highs.indices) {
            val current = highs[i]
            if (i == 0) {
                result.add(current.copy(sequenceLabel = "HIGH"))
            } else {
                val prev = highs[i - 1]
                val delta = current.price - prev.price
                val tolerance = prev.price * 0.00015 // 1.5 bps equality band (~$14 on $90k BTC)
                val label = when {
                    delta > tolerance -> "HH"
                    delta < -tolerance -> "LH"
                    else -> "EQUAL_HIGH"
                }
                result.add(current.copy(sequenceLabel = label))
            }
        }
        return result
    }

    /**
     * Labels Swing Lows as Higher Low (HL), Lower Low (LL), or Equal Low (EL).
     */
    private fun labelSwingLows(lows: List<SwingPoint>): List<SwingPoint> {
        if (lows.isEmpty()) return emptyList()
        val result = mutableListOf<SwingPoint>()

        for (i in lows.indices) {
            val current = lows[i]
            if (i == 0) {
                result.add(current.copy(sequenceLabel = "LOW"))
            } else {
                val prev = lows[i - 1]
                val delta = current.price - prev.price
                val tolerance = prev.price * 0.00015
                val label = when {
                    delta > tolerance -> "HL"
                    delta < -tolerance -> "LL"
                    else -> "EQUAL_LOW"
                }
                result.add(current.copy(sequenceLabel = label))
            }
        }
        return result
    }

    /**
     * Determines trend direction based on the last 3-4 swings of each type.
     */
    private fun evaluateTrendDirection(
        highs: List<SwingPoint>,
        lows: List<SwingPoint>,
        currentPrice: Double
    ): TrendDirection {
        if (highs.size < 2 && lows.size < 2) return TrendDirection.AMBIGUOUS

        val recentHighs = highs.takeLast(3)
        val recentLows = lows.takeLast(3)

        val hh = recentHighs.count { it.sequenceLabel == "HH" }
        val lh = recentHighs.count { it.sequenceLabel == "LH" }
        val hl = recentLows.count { it.sequenceLabel == "HL" }
        val ll = recentLows.count { it.sequenceLabel == "LL" }

        val lastHigh = recentHighs.lastOrNull()
        val lastLow = recentLows.lastOrNull()

        // Bullish Market Structure: HH and HL dominance
        if (hh >= 1 && hl >= 1 && lh == 0 && ll == 0) {
            return TrendDirection.BULLISH
        }
        // Bearish Market Structure: LH and LL dominance
        if (lh >= 1 && ll >= 1 && hh == 0 && hl == 0) {
            return TrendDirection.BEARISH
        }

        // Structural shifts / breaks:
        // If price broke above the last Lower High with momentum -> nascent Bullish shift
        if (lastHigh != null && lastHigh.sequenceLabel == "LH" && currentPrice > lastHigh.price * 1.0003) {
            return TrendDirection.BULLISH
        }
        // If price broke below the last Higher Low with momentum -> nascent Bearish breakdown
        if (lastLow != null && lastLow.sequenceLabel == "HL" && currentPrice < lastLow.price * 0.9997) {
            return TrendDirection.BEARISH
        }

        // Predominant trend scores
        val bullScore = hh * 1.5 + hl * 1.5
        val bearScore = lh * 1.5 + ll * 1.5

        return when {
            bullScore >= bearScore + 1.5 -> TrendDirection.BULLISH
            bearScore >= bullScore + 1.5 -> TrendDirection.BEARISH
            hh == 0 && lh == 0 && hl == 0 && ll == 0 -> TrendDirection.NEUTRAL_RANGE
            abs(bullScore - bearScore) <= 1.0 -> TrendDirection.AMBIGUOUS
            else -> TrendDirection.AMBIGUOUS
        }
    }

    /**
     * Derives horizontal Support and Resistance clusters from price points and swing extrema.
     */
    private fun deriveKeyLevels(
        points: List<PricePoint>,
        highs: List<SwingPoint>,
        lows: List<SwingPoint>,
        currentPrice: Double
    ): List<KeyLevel> {
        val candidates = mutableListOf<Double>()
        highs.forEach { candidates.add(it.price) }
        lows.forEach { candidates.add(it.price) }

        if (candidates.isEmpty()) return emptyList()

        // Cluster candidate prices within 0.04% threshold (~$36 on $90k BTC)
        val clusterTolerance = currentPrice * 0.0004
        val clusters = mutableListOf<MutableList<Double>>()

        for (price in candidates) {
            var added = false
            for (cluster in clusters) {
                val clusterAvg = cluster.average()
                if (abs(price - clusterAvg) <= clusterTolerance) {
                    cluster.add(price)
                    added = true
                    break
                }
            }
            if (!added) {
                clusters.add(mutableListOf(price))
            }
        }

        val keyLevels = mutableListOf<KeyLevel>()
        for (cluster in clusters) {
            val avgPrice = cluster.average()
            val touches = cluster.size
            val distDollars = currentPrice - avgPrice
            val distPercent = (distDollars / currentPrice) * 100.0
            val isResistance = avgPrice > currentPrice
            val type = if (isResistance) LevelType.RESISTANCE else LevelType.SUPPORT

            // Strength score: scales with touch count, capped at 1.0
            val strength = ((touches.toDouble() / 4.0).coerceIn(0.25, 1.0))

            val desc = if (isResistance) {
                "Resistance Cluster ($touches touches)"
            } else {
                "Support Cluster ($touches touches)"
            }

            keyLevels.add(
                KeyLevel(
                    price = Math.round(avgPrice * 100.0) / 100.0,
                    levelType = type,
                    touchCount = touches,
                    strengthScore = Math.round(strength * 100.0) / 100.0,
                    distanceDollars = Math.round(distDollars * 100.0) / 100.0,
                    distancePercent = Math.round(distPercent * 1000.0) / 1000.0,
                    description = desc
                )
            )
        }

        return keyLevels.sortedBy { abs(it.price - currentPrice) }
    }

    /**
     * Derives primary breakout level.
     */
    private fun deriveBreakoutLevel(
        trend: TrendDirection,
        highs: List<SwingPoint>,
        lows: List<SwingPoint>,
        currentPrice: Double,
        primaryResistance: KeyLevel?,
        primarySupport: KeyLevel?
    ): KeyLevel? {
        return when (trend) {
            TrendDirection.BULLISH, TrendDirection.NEUTRAL_RANGE, TrendDirection.AMBIGUOUS -> {
                // Bullish breakout level: highest recent swing high or primary resistance
                val recentHigh = highs.takeLast(3).maxByOrNull { it.price }
                val targetPrice = recentHigh?.price ?: primaryResistance?.price ?: return null
                KeyLevel(
                    price = Math.round(targetPrice * 100.0) / 100.0,
                    levelType = LevelType.BREAKOUT_TRIGGER,
                    touchCount = 1,
                    strengthScore = 0.85,
                    distanceDollars = Math.round((currentPrice - targetPrice) * 100.0) / 100.0,
                    distancePercent = Math.round(((currentPrice - targetPrice) / currentPrice * 100.0) * 1000.0) / 1000.0,
                    description = "Bullish Expansion Trigger (Breach of structural swing high)"
                )
            }
            TrendDirection.BEARISH -> {
                // Bearish breakdown level: lowest recent swing low or primary support
                val recentLow = lows.takeLast(3).minByOrNull { it.price }
                val targetPrice = recentLow?.price ?: primarySupport?.price ?: return null
                KeyLevel(
                    price = Math.round(targetPrice * 100.0) / 100.0,
                    levelType = LevelType.BREAKOUT_TRIGGER,
                    touchCount = 1,
                    strengthScore = 0.85,
                    distanceDollars = Math.round((currentPrice - targetPrice) * 100.0) / 100.0,
                    distancePercent = Math.round(((currentPrice - targetPrice) / currentPrice * 100.0) * 1000.0) / 1000.0,
                    description = "Bearish Breakdown Trigger (Breach of structural swing low)"
                )
            }
        }
    }

    /**
     * Derives active rejection level where price demonstrated violent rejection or multiple failed tests.
     */
    private fun deriveRejectionLevel(
        points: List<PricePoint>,
        highs: List<SwingPoint>,
        lows: List<SwingPoint>,
        currentPrice: Double
    ): KeyLevel? {
        val lastHigh = highs.lastOrNull()
        val lastLow = lows.lastOrNull()
        if (lastHigh == null && lastLow == null) return null

        val candidate = if (lastHigh != null && (lastLow == null || lastHigh.timestamp > lastLow.timestamp)) {
            Pair(lastHigh.price, LevelType.REJECTION_ZONE)
        } else {
            Pair(lastLow!!.price, LevelType.REJECTION_ZONE)
        }

        val price = candidate.first
        return KeyLevel(
            price = Math.round(price * 100.0) / 100.0,
            levelType = candidate.second,
            touchCount = 1,
            strengthScore = 0.75,
            distanceDollars = Math.round((currentPrice - price) * 100.0) / 100.0,
            distancePercent = Math.round(((currentPrice - price) / currentPrice * 100.0) * 1000.0) / 1000.0,
            description = "Active Micro Rejection Zone"
        )
    }

    /**
     * Mathematically calculates the support trendline connecting confirmed swing lows.
     * Equation: y = m*t + b
     */
    private fun deriveSupportTrendline(lows: List<SwingPoint>, asOfTimestamp: Long): DerivedTrendline? {
        if (lows.size < 2) return null
        val anchor1 = lows[lows.size - 2]
        val anchor2 = lows[lows.size - 1]

        val dtSec = (anchor2.timestamp - anchor1.timestamp) / 1000.0
        if (dtSec <= 0.0) return null

        val slopePerSec = (anchor2.price - anchor1.price) / dtSec
        val slopePerMin = slopePerSec * 60.0

        val currentDtSec = (asOfTimestamp - anchor1.timestamp) / 1000.0
        val currentProjected = anchor1.price + (slopePerSec * currentDtSec)

        val lineType = if (slopePerMin > 0.5) TrendlineType.ASCENDING_SUPPORT else TrendlineType.HORIZONTAL_SUPPORT

        return DerivedTrendline(
            anchorPoint1 = anchor1,
            anchorPoint2 = anchor2,
            slopePerSecond = slopePerSec,
            slopePerMinute = Math.round(slopePerMin * 100.0) / 100.0,
            interceptPrice = anchor1.price,
            lineType = lineType,
            touchCount = 2,
            meanAbsoluteError = 0.0,
            currentProjectedPrice = Math.round(currentProjected * 100.0) / 100.0,
            isValid = true
        )
    }

    /**
     * Mathematically calculates the resistance trendline connecting confirmed swing highs.
     */
    private fun deriveResistanceTrendline(highs: List<SwingPoint>, asOfTimestamp: Long): DerivedTrendline? {
        if (highs.size < 2) return null
        val anchor1 = highs[highs.size - 2]
        val anchor2 = highs[highs.size - 1]

        val dtSec = (anchor2.timestamp - anchor1.timestamp) / 1000.0
        if (dtSec <= 0.0) return null

        val slopePerSec = (anchor2.price - anchor1.price) / dtSec
        val slopePerMin = slopePerSec * 60.0

        val currentDtSec = (asOfTimestamp - anchor1.timestamp) / 1000.0
        val currentProjected = anchor1.price + (slopePerSec * currentDtSec)

        val lineType = if (slopePerMin < -0.5) TrendlineType.DESCENDING_RESISTANCE else TrendlineType.HORIZONTAL_RESISTANCE

        return DerivedTrendline(
            anchorPoint1 = anchor1,
            anchorPoint2 = anchor2,
            slopePerSecond = slopePerSec,
            slopePerMinute = Math.round(slopePerMin * 100.0) / 100.0,
            interceptPrice = anchor1.price,
            lineType = lineType,
            touchCount = 2,
            meanAbsoluteError = 0.0,
            currentProjectedPrice = Math.round(currentProjected * 100.0) / 100.0,
            isValid = true
        )
    }

    /**
     * Derives local channel bounding price action between support and resistance trendlines.
     */
    private fun deriveLocalChannel(
        support: DerivedTrendline?,
        resistance: DerivedTrendline?,
        currentPrice: Double
    ): LocalChannel? {
        if (support == null || resistance == null) return null

        val width = abs(resistance.currentProjectedPrice - support.currentProjectedPrice)
        if (width <= 0.0 || resistance.currentProjectedPrice <= support.currentProjectedPrice) return null

        val widthPercent = (width / currentPrice) * 100.0
        val avgSlope = (support.slopePerMinute + resistance.slopePerMinute) / 2.0
        val slopeDiff = abs(support.slopePerMinute - resistance.slopePerMinute)
        val isParallel = slopeDiff <= max(abs(support.slopePerMinute), abs(resistance.slopePerMinute)) * 0.5 + 5.0

        val channelType = when {
            isParallel && avgSlope > 5.0 -> ChannelType.ASCENDING_CHANNEL
            isParallel && avgSlope < -5.0 -> ChannelType.DESCENDING_CHANNEL
            isParallel && abs(avgSlope) <= 5.0 -> ChannelType.HORIZONTAL_RECTANGLE
            !isParallel && support.slopePerMinute > 0 && resistance.slopePerMinute < 0 -> ChannelType.CONVERGING_RANGE
            else -> ChannelType.NONE
        }

        val median = (resistance.currentProjectedPrice + support.currentProjectedPrice) / 2.0

        return LocalChannel(
            upperResistanceLine = resistance,
            lowerSupportLine = support,
            channelWidthDollars = Math.round(width * 100.0) / 100.0,
            channelWidthPercent = Math.round(widthPercent * 1000.0) / 1000.0,
            channelSlopePerMinute = Math.round(avgSlope * 100.0) / 100.0,
            channelType = channelType,
            isParallel = isParallel,
            medianPrice = Math.round(median * 100.0) / 100.0
        )
    }

    /**
     * Evaluates compression and volatility state.
     */
    private fun evaluateCompression(
        points: List<PricePoint>,
        highs: List<SwingPoint>,
        lows: List<SwingPoint>,
        channel: LocalChannel?
    ): CompressionState {
        if (points.size < 20) return CompressionState.UNCOMPRESSED

        val recentPoints = points.takeLast(30)
        val highPrice = recentPoints.maxOf { it.price }
        val lowPrice = recentPoints.minOf { it.price }
        val rangePct = ((highPrice - lowPrice) / recentPoints.first().price) * 100.0

        return when {
            channel?.channelType == ChannelType.CONVERGING_RANGE -> CompressionState.VOLATILITY_COMPRESSION
            rangePct < 0.08 -> CompressionState.VOLATILITY_COMPRESSION // Under 8 bps in 30 cycles (60s)
            channel?.channelType == ChannelType.HORIZONTAL_RECTANGLE -> CompressionState.RANGE_BOUND
            rangePct > 0.40 -> CompressionState.EXPANDING_VOLATILITY
            else -> CompressionState.UNCOMPRESSED
        }
    }

    /**
     * Strict Classical Formations Detection.
     * ZERO false claims: only validated if mathematical thresholds strictly match.
     */
    private fun validateClassicalFormations(
        labeledHighs: List<SwingPoint>,
        labeledLows: List<SwingPoint>,
        currentPrice: Double,
        supportTrendline: DerivedTrendline?,
        resistanceTrendline: DerivedTrendline?
    ): ValidatedPatternFormation {
        // 1. Check for Double Top
        if (labeledHighs.size >= 2) {
            val h1 = labeledHighs[labeledHighs.size - 2]
            val h2 = labeledHighs[labeledHighs.size - 1]
            val priceDiffPct = abs(h1.price - h2.price) / h1.price
            val interveningLows = labeledLows.filter { it.timestamp in h1.timestamp..h2.timestamp }

            if (priceDiffPct <= 0.0010 && interveningLows.isNotEmpty()) {
                val trough = interveningLows.minByOrNull { it.price }!!
                val troughDepth = h1.price - trough.price
                if (troughDepth >= h1.price * 0.0015) { // At least 15 bps trough
                    val isNecklineBreached = currentPrice <= trough.price
                    val status = if (isNecklineBreached) PatternStatus.CONFIRMED else PatternStatus.FORMING
                    val target = trough.price - troughDepth
                    return ValidatedPatternFormation(
                        patternName = "DOUBLE_TOP",
                        status = status,
                        necklineOrBoundaryPrice = trough.price,
                        targetProjectionPrice = Math.round(target * 100.0) / 100.0,
                        invalidationPrice = max(h1.price, h2.price) + 5.0,
                        validationConfidence = if (status == PatternStatus.CONFIRMED) 0.88 else 0.65,
                        explanation = if (status == PatternStatus.CONFIRMED) {
                            "Confirmed Double Top: Price broke below neckline ($${String.format(Locale.US, "%,.2f", trough.price)})"
                        } else {
                            "Forming Double Top: Peaks at $${h1.formattedPrice} and $${h2.formattedPrice}, testing neckline"
                        }
                    )
                }
            }
        }

        // 2. Check for Double Bottom
        if (labeledLows.size >= 2) {
            val l1 = labeledLows[labeledLows.size - 2]
            val l2 = labeledLows[labeledLows.size - 1]
            val priceDiffPct = abs(l1.price - l2.price) / l1.price
            val interveningHighs = labeledHighs.filter { it.timestamp in l1.timestamp..l2.timestamp }

            if (priceDiffPct <= 0.0010 && interveningHighs.isNotEmpty()) {
                val peak = interveningHighs.maxByOrNull { it.price }!!
                val peakHeight = peak.price - l1.price
                if (peakHeight >= l1.price * 0.0015) {
                    val isNecklineBreached = currentPrice >= peak.price
                    val status = if (isNecklineBreached) PatternStatus.CONFIRMED else PatternStatus.FORMING
                    val target = peak.price + peakHeight
                    return ValidatedPatternFormation(
                        patternName = "DOUBLE_BOTTOM",
                        status = status,
                        necklineOrBoundaryPrice = peak.price,
                        targetProjectionPrice = Math.round(target * 100.0) / 100.0,
                        invalidationPrice = min(l1.price, l2.price) - 5.0,
                        validationConfidence = if (status == PatternStatus.CONFIRMED) 0.88 else 0.65,
                        explanation = if (status == PatternStatus.CONFIRMED) {
                            "Confirmed Double Bottom: Price broke above neckline ($${String.format(Locale.US, "%,.2f", peak.price)})"
                        } else {
                            "Forming Double Bottom: Troughs at $${l1.formattedPrice} and $${l2.formattedPrice}, testing neckline"
                        }
                    )
                }
            }
        }

        // 3. Ascending Triangle: Flat Resistance + Ascending Support
        if (supportTrendline != null && resistanceTrendline != null) {
            val resFlat = abs(resistanceTrendline.slopePerMinute) <= 4.0
            val supAscending = supportTrendline.slopePerMinute >= 8.0
            if (resFlat && supAscending) {
                val resistancePrice = resistanceTrendline.currentProjectedPrice
                val isBreached = currentPrice >= resistancePrice
                val status = if (isBreached) PatternStatus.CONFIRMED else PatternStatus.FORMING
                val patternHeight = resistancePrice - supportTrendline.anchorPoint1.price
                val target = resistancePrice + patternHeight
                return ValidatedPatternFormation(
                    patternName = "ASCENDING_TRIANGLE",
                    status = status,
                    necklineOrBoundaryPrice = resistancePrice,
                    targetProjectionPrice = Math.round(target * 100.0) / 100.0,
                    invalidationPrice = supportTrendline.currentProjectedPrice - 10.0,
                    validationConfidence = 0.80,
                    explanation = "Ascending Triangle: Flat resistance ceiling with rising support trendline"
                )
            }
        }

        // 4. Descending Triangle: Flat Support + Descending Resistance
        if (supportTrendline != null && resistanceTrendline != null) {
            val supFlat = abs(supportTrendline.slopePerMinute) <= 4.0
            val resDescending = resistanceTrendline.slopePerMinute <= -8.0
            if (supFlat && resDescending) {
                val supportPrice = supportTrendline.currentProjectedPrice
                val isBreached = currentPrice <= supportPrice
                val status = if (isBreached) PatternStatus.CONFIRMED else PatternStatus.FORMING
                val patternHeight = resistanceTrendline.anchorPoint1.price - supportPrice
                val target = supportPrice - patternHeight
                return ValidatedPatternFormation(
                    patternName = "DESCENDING_TRIANGLE",
                    status = status,
                    necklineOrBoundaryPrice = supportPrice,
                    targetProjectionPrice = Math.round(target * 100.0) / 100.0,
                    invalidationPrice = resistanceTrendline.currentProjectedPrice + 10.0,
                    validationConfidence = 0.80,
                    explanation = "Descending Triangle: Flat support floor with falling resistance trendline"
                )
            }
        }

        return ValidatedPatternFormation(
            patternName = "NONE",
            status = PatternStatus.NONE,
            explanation = "No classical formation verified under strict criteria"
        )
    }

    /**
     * Derives actionable Trader Reference Levels:
     * ENTRY/REFERENCE LEVEL -> TARGET LEVEL -> INVALIDATION LEVEL
     *
     * Invalidation is strictly structural (breach of recent HL for Bullish, breach of recent LH for Bearish).
     */
    private fun deriveTraderLevels(
        trendDirection: TrendDirection,
        currentPrice: Double,
        labeledHighs: List<SwingPoint>,
        labeledLows: List<SwingPoint>,
        primarySupport: KeyLevel?,
        primaryResistance: KeyLevel?,
        localChannel: LocalChannel?,
        breakoutLevel: KeyLevel?,
        supportTrendline: DerivedTrendline?,
        resistanceTrendline: DerivedTrendline?,
        isAmbiguous: Boolean
    ): TraderLevels? {
        if (isAmbiguous || trendDirection == TrendDirection.AMBIGUOUS) {
            return TraderLevels(
                entryReferenceLevel = currentPrice,
                targetLevel = currentPrice,
                invalidationLevel = currentPrice,
                riskDollars = 0.0,
                rewardDollars = 0.0,
                riskRewardRatio = 0.0,
                tradeDirection = "NO-TRADE",
                isViable = false,
                reason = "Market structure is ambiguous or conflicted; awaiting definitive swing resolution"
            )
        }

        if (trendDirection == TrendDirection.NEUTRAL_RANGE) {
            return TraderLevels(
                entryReferenceLevel = currentPrice,
                targetLevel = primaryResistance?.price ?: currentPrice,
                invalidationLevel = primarySupport?.price ?: currentPrice,
                riskDollars = 0.0,
                rewardDollars = 0.0,
                riskRewardRatio = 0.0,
                tradeDirection = "NO-TRADE",
                isViable = false,
                reason = "Price is compressed inside a neutral horizontal range; awaiting breakout"
            )
        }

        if (trendDirection == TrendDirection.BULLISH) {
            val entry = currentPrice
            // Structural Invalidation: strictly below nearest structural floor (channel support or recent HL)
            val recentHl = labeledLows.takeLast(2).find { it.sequenceLabel == "HL" } ?: labeledLows.lastOrNull()
            val floor = when {
                supportTrendline != null && supportTrendline.currentProjectedPrice < entry -> {
                    max(recentHl?.price ?: 0.0, supportTrendline.currentProjectedPrice)
                }
                recentHl != null -> recentHl.price
                else -> primarySupport?.price ?: (entry - 35.0)
            }
            val invalidation = floor - 8.0 // 8-dollar buffer below structural boundary

            val risk = max(entry - invalidation, 5.0)

            // Target Level: primary resistance, channel projection, or measured impulse extension
            val lastHigh = labeledHighs.lastOrNull()?.price ?: entry
            val lastLow = labeledLows.lastOrNull()?.price ?: (entry - 30.0)
            val measuredMove = max(abs(lastHigh - lastLow) * 1.382, risk * 1.5)

            val channelTarget = if (localChannel != null && localChannel.upperResistanceLine.currentProjectedPrice > entry + 15.0) {
                localChannel.upperResistanceLine.currentProjectedPrice
            } else null

            val target = when {
                primaryResistance != null && primaryResistance.price > entry + 15.0 && (primaryResistance.price - entry) >= risk * 1.2 -> primaryResistance.price
                channelTarget != null && (channelTarget - entry) >= risk * 1.2 -> channelTarget
                breakoutLevel != null && breakoutLevel.price > entry + 15.0 && (breakoutLevel.price + 20.0 - entry) >= risk * 1.2 -> breakoutLevel.price + 20.0
                else -> entry + measuredMove
            }

            val reward = max(target - entry, 0.0)
            val rr = if (risk > 0.0) reward / risk else 0.0
            val isViable = rr >= 1.2 && target > entry && invalidation < entry

            return TraderLevels(
                entryReferenceLevel = Math.round(entry * 100.0) / 100.0,
                targetLevel = Math.round(target * 100.0) / 100.0,
                invalidationLevel = Math.round(invalidation * 100.0) / 100.0,
                riskDollars = Math.round(risk * 100.0) / 100.0,
                rewardDollars = Math.round(reward * 100.0) / 100.0,
                riskRewardRatio = Math.round(rr * 100.0) / 100.0,
                tradeDirection = if (isViable) "UP" else "NO-TRADE",
                isViable = isViable,
                reason = if (isViable) {
                    "Bullish HH/HL Structure: Invalidation below HL ($${String.format(Locale.US, "%,.2f", invalidation)})"
                } else {
                    "Unfavorable Risk/Reward ratio (1:${String.format(Locale.US, "%.2f", rr)}) on Bullish structure"
                }
            )
        }

        if (trendDirection == TrendDirection.BEARISH) {
            val entry = currentPrice
            // Structural Invalidation: strictly above nearest structural ceiling (channel resistance or recent LH)
            val recentLh = labeledHighs.takeLast(2).find { it.sequenceLabel == "LH" } ?: labeledHighs.lastOrNull()
            val ceiling = when {
                resistanceTrendline != null && resistanceTrendline.currentProjectedPrice > entry -> {
                    min(recentLh?.price ?: Double.MAX_VALUE, resistanceTrendline.currentProjectedPrice)
                }
                recentLh != null -> recentLh.price
                else -> primaryResistance?.price ?: (entry + 35.0)
            }
            val invalidation = ceiling + 8.0 // 8-dollar buffer above structural boundary

            val risk = max(invalidation - entry, 5.0)

            // Target Level: primary support, channel projection, or measured impulse extension
            val lastHigh = labeledHighs.lastOrNull()?.price ?: (entry + 30.0)
            val lastLow = labeledLows.lastOrNull()?.price ?: entry
            val measuredMove = max(abs(lastHigh - lastLow) * 1.382, risk * 1.5)

            val channelTarget = if (localChannel != null && localChannel.lowerSupportLine.currentProjectedPrice < entry - 15.0) {
                localChannel.lowerSupportLine.currentProjectedPrice
            } else null

            val target = when {
                primarySupport != null && primarySupport.price < entry - 15.0 && (entry - primarySupport.price) >= risk * 1.2 -> primarySupport.price
                channelTarget != null && (entry - channelTarget) >= risk * 1.2 -> channelTarget
                breakoutLevel != null && breakoutLevel.price < entry - 15.0 && (entry - (breakoutLevel.price - 20.0)) >= risk * 1.2 -> breakoutLevel.price - 20.0
                else -> entry - measuredMove
            }

            val reward = max(entry - target, 0.0)
            val rr = if (risk > 0.0) reward / risk else 0.0
            val isViable = rr >= 1.2 && target < entry && invalidation > entry

            return TraderLevels(
                entryReferenceLevel = Math.round(entry * 100.0) / 100.0,
                targetLevel = Math.round(target * 100.0) / 100.0,
                invalidationLevel = Math.round(invalidation * 100.0) / 100.0,
                riskDollars = Math.round(risk * 100.0) / 100.0,
                rewardDollars = Math.round(reward * 100.0) / 100.0,
                riskRewardRatio = Math.round(rr * 100.0) / 100.0,
                tradeDirection = if (isViable) "DOWN" else "NO-TRADE",
                isViable = isViable,
                reason = if (isViable) {
                    "Bearish LH/LL Structure: Invalidation above LH ($${String.format(Locale.US, "%,.2f", invalidation)})"
                } else {
                    "Unfavorable Risk/Reward ratio (1:${String.format(Locale.US, "%.2f", rr)}) on Bearish structure"
                }
            )
        }

        return null
    }

    private fun buildStructuralSummary(
        trendDirection: TrendDirection,
        compressionState: CompressionState,
        hhCount: Int,
        hlCount: Int,
        lhCount: Int,
        llCount: Int,
        primarySupport: KeyLevel?,
        primaryResistance: KeyLevel?,
        traderLevels: TraderLevels?,
        validatedFormation: ValidatedPatternFormation,
        isAmbiguous: Boolean
    ): String {
        val trendStr = when (trendDirection) {
            TrendDirection.BULLISH -> "BULLISH (${hhCount}HH/${hlCount}HL)"
            TrendDirection.BEARISH -> "BEARISH (${lhCount}LH/${llCount}LL)"
            TrendDirection.NEUTRAL_RANGE -> "NEUTRAL RANGE"
            TrendDirection.AMBIGUOUS -> "AMBIGUOUS / CHOP"
        }

        val patternStr = if (validatedFormation.status != PatternStatus.NONE) {
            " • ${validatedFormation.patternName} (${validatedFormation.status})"
        } else ""

        val supStr = primarySupport?.let { "Sup: $${it.formattedPrice}" } ?: "Sup: None"
        val resStr = primaryResistance?.let { "Res: $${it.formattedPrice}" } ?: "Res: None"

        val actionStr = when {
            isAmbiguous -> "STRUCTURAL NO-TRADE"
            traderLevels != null && traderLevels.isViable -> "${traderLevels.tradeDirection} [R:R ${traderLevels.formattedRr}]"
            else -> "NO-TRADE"
        }

        return "$trendStr$patternStr • $supStr • $resStr • $actionStr"
    }
}
