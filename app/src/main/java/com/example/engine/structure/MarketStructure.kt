package com.example.engine.structure

import java.util.Locale

/**
 * Classification of a market swing pivot point.
 */
enum class SwingType {
    SWING_HIGH,
    SWING_LOW
}

/**
 * An authentic swing pivot point identified from price history.
 *
 * @property index Index within the analyzed point series
 * @property timestamp Epoch millis of the authentic observation
 * @property price Spot price at the swing pivot
 * @property type SWING_HIGH or SWING_LOW
 * @property sequenceLabel Progression classification: "HH" (Higher High), "HL" (Higher Low),
 *                         "LH" (Lower High), "LL" (Lower Low), or "EQUAL"
 * @property confirmationBars Number of subsequent bars confirming the local extremum
 */
data class SwingPoint(
    val index: Int,
    val timestamp: Long,
    val price: Double,
    val type: SwingType,
    val sequenceLabel: String? = null,
    val confirmationBars: Int = 3
) {
    val formattedPrice: String get() = String.format(Locale.US, "$%,.2f", price)
}

/**
 * Core structural trend direction derived from swing progression.
 */
enum class TrendDirection {
    BULLISH,       // Sustained Higher Highs (HH) and Higher Lows (HL)
    BEARISH,       // Sustained Lower Highs (LH) and Lower Lows (LL)
    NEUTRAL_RANGE, // Horizontal containment between bounded highs and lows
    AMBIGUOUS      // Conflicting or chaotic swing progression -> Triggers NO-TRADE
}

/**
 * Compression and volatility regime of the market structure.
 */
enum class CompressionState {
    UNCOMPRESSED,          // Normal trending or fluctuating market structure
    RANGE_BOUND,           // Contained inside a recognizable horizontal box
    VOLATILITY_COMPRESSION, // Converging swings with decreasing ATR / range compression
    EXPANDING_VOLATILITY   // Diverging swings / widening distribution
}

/**
 * Type of data-derived key level.
 */
enum class LevelType {
    SUPPORT,
    RESISTANCE,
    BREAKOUT_TRIGGER,
    REJECTION_ZONE,
    PIVOT_MEDIAN
}

/**
 * Mathematically derived key price level based on cluster density, touches, and historical reaction.
 */
data class KeyLevel(
    val price: Double,
    val levelType: LevelType,
    val touchCount: Int,
    val strengthScore: Double, // 0.0 to 1.0 based on touches, volume, and recency
    val distanceDollars: Double, // currentPrice - price
    val distancePercent: Double, // distance / currentPrice * 100
    val description: String
) {
    val formattedPrice: String get() = String.format(Locale.US, "$%,.2f", price)
}

/**
 * Type of data-derived trendline.
 */
enum class TrendlineType {
    ASCENDING_SUPPORT,
    DESCENDING_RESISTANCE,
    HORIZONTAL_SUPPORT,
    HORIZONTAL_RESISTANCE,
    CHANNEL_MEDIAN
}

/**
 * Mathematically calculated directional trendline derived strictly from authentic swing points.
 * Equation: Price(t) = interceptPrice + slopePerSecond * (t - anchorPoint1.timestamp) / 1000
 *
 * @property anchorPoint1 Initial anchor swing point
 * @property anchorPoint2 Secondary anchor swing point
 * @property slopePerSecond Price change per second ($/s)
 * @property slopePerMinute Price change per minute ($/min)
 * @property interceptPrice Price at anchorPoint1
 * @property lineType Type classification
 * @property touchCount Number of swing points touching or respecting this line within tolerance
 * @property meanAbsoluteError Average deviation ($) of intermediate swings from the line
 * @property currentProjectedPrice Projected price value of the trendline at current timestamp t
 * @property isValid True if mathematically defensible with at least 2 distinct confirmed anchors
 */
data class DerivedTrendline(
    val anchorPoint1: SwingPoint,
    val anchorPoint2: SwingPoint,
    val slopePerSecond: Double,
    val slopePerMinute: Double,
    val interceptPrice: Double,
    val lineType: TrendlineType,
    val touchCount: Int,
    val meanAbsoluteError: Double,
    val currentProjectedPrice: Double,
    val isValid: Boolean = true
) {
    val formattedSlope: String get() = String.format(
        Locale.US,
        "%+.2f $/min",
        slopePerMinute
    )
}

/**
 * Type of local channel.
 */
enum class ChannelType {
    ASCENDING_CHANNEL,
    DESCENDING_CHANNEL,
    HORIZONTAL_RECTANGLE,
    CONVERGING_RANGE,
    NONE
}

/**
 * Mathematically derived local channel bounding recent price action.
 */
data class LocalChannel(
    val upperResistanceLine: DerivedTrendline,
    val lowerSupportLine: DerivedTrendline,
    val channelWidthDollars: Double,
    val channelWidthPercent: Double,
    val channelSlopePerMinute: Double,
    val channelType: ChannelType,
    val isParallel: Boolean,
    val medianPrice: Double
)

/**
 * Status of classical pattern validation.
 */
enum class PatternStatus {
    CONFIRMED,   // Validated and currently active with trigger break
    FORMING,     // Structural prerequisites met, awaiting breakout/breakdown
    INVALIDATED, // Structure breached opposite to pattern expectations
    NONE         // No pattern verified
}

/**
 * Explicitly validated classical chart formation.
 * QtY STRICT RULE: Never claim a formation without explicit mathematical detection logic.
 */
data class ValidatedPatternFormation(
    val patternName: String, // "DOUBLE_TOP", "DOUBLE_BOTTOM", "ASCENDING_TRIANGLE", "DESCENDING_TRIANGLE", "HORIZONTAL_RANGE_BOX", "NONE"
    val status: PatternStatus,
    val necklineOrBoundaryPrice: Double? = null,
    val targetProjectionPrice: Double? = null,
    val invalidationPrice: Double? = null,
    val validationConfidence: Double = 0.0,
    val explanation: String = ""
)

/**
 * Model-derived Trader Actionable Reference Levels.
 *
 * Explicitly connects:
 * ENTRY/REFERENCE LEVEL -> TARGET LEVEL -> INVALIDATION LEVEL
 */
data class TraderLevels(
    val entryReferenceLevel: Double,
    val targetLevel: Double,
    val invalidationLevel: Double,
    val riskDollars: Double,       // |Entry - Invalidation|
    val rewardDollars: Double,     // |Target - Entry|
    val riskRewardRatio: Double,   // Reward / Risk
    val tradeDirection: String,    // "UP", "DOWN", or "NO-TRADE"
    val isViable: Boolean,         // True if R:R >= 1.2 and structure is unambiguous
    val reason: String             // Justification or explanation for NO-TRADE
) {
    val formattedEntry: String get() = String.format(Locale.US, "$%,.2f", entryReferenceLevel)
    val formattedTarget: String get() = String.format(Locale.US, "$%,.2f", targetLevel)
    val formattedInvalidation: String get() = String.format(Locale.US, "$%,.2f", invalidationLevel)
    val formattedRr: String get() = String.format(Locale.US, "1:%.2f", riskRewardRatio)
}

/**
 * Complete immutable market-structure snapshot emitted per cycle.
 */
data class MarketStructureSnapshot(
    val timestamp: Long,
    val currentPrice: Double,
    val trendDirection: TrendDirection,
    val compressionState: CompressionState,
    val swingHighs: List<SwingPoint>,
    val swingLows: List<SwingPoint>,
    val recentSwings: List<SwingPoint>, // Chronological list of confirmed swings
    val higherHighsCount: Int,
    val higherLowsCount: Int,
    val lowerHighsCount: Int,
    val lowerLowsCount: Int,
    val primarySupport: KeyLevel?,
    val primaryResistance: KeyLevel?,
    val keyLevels: List<KeyLevel>,
    val breakoutLevel: KeyLevel?,
    val rejectionLevel: KeyLevel?,
    val supportTrendline: DerivedTrendline?,
    val resistanceTrendline: DerivedTrendline?,
    val localChannel: LocalChannel?,
    val validatedFormation: ValidatedPatternFormation,
    val traderLevels: TraderLevels?,
    val isAmbiguous: Boolean,
    val structuralSummary: String
) {
    companion object {
        fun empty(timestamp: Long = System.currentTimeMillis(), price: Double = 0.0): MarketStructureSnapshot {
            return MarketStructureSnapshot(
                timestamp = timestamp,
                currentPrice = price,
                trendDirection = TrendDirection.AMBIGUOUS,
                compressionState = CompressionState.UNCOMPRESSED,
                swingHighs = emptyList(),
                swingLows = emptyList(),
                recentSwings = emptyList(),
                higherHighsCount = 0,
                higherLowsCount = 0,
                lowerHighsCount = 0,
                lowerLowsCount = 0,
                primarySupport = null,
                primaryResistance = null,
                keyLevels = emptyList(),
                breakoutLevel = null,
                rejectionLevel = null,
                supportTrendline = null,
                resistanceTrendline = null,
                localChannel = null,
                validatedFormation = ValidatedPatternFormation(
                    patternName = "NONE",
                    status = PatternStatus.NONE,
                    explanation = "Insufficient price history for structural derivation"
                ),
                traderLevels = null,
                isAmbiguous = true,
                structuralSummary = "Awaiting sufficient authentic price observations..."
            )
        }
    }
}
