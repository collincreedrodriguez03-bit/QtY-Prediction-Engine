package com.example.engine

/**
 * Thrown when historical evaluation or backtesting attempts to access market data
 * after the prediction's as-of timestamp.
 *
 * Strict Rule:
 * Historical evaluation must never access data after the prediction's as-of timestamp.
 * Any request for timestamp > asOfTimestamp must fail loudly rather than silently substituting future data.
 */
class LookaheadViolationException(
    val requestedTimestamp: Long,
    val asOfTimestamp: Long,
    override val message: String = "No-lookahead violation: requested timestamp $requestedTimestamp exceeds asOfTimestamp $asOfTimestamp (Δt = +${requestedTimestamp - asOfTimestamp}ms)"
) : IllegalStateException(message) {

    companion object {
        /**
         * Validates that a requested observation timestamp does not exceed the allowed as-of cutoff.
         * Throws [LookaheadViolationException] if lookahead is detected.
         */
        fun assertNoLookahead(requestedTimestamp: Long, asOfTimestamp: Long) {
            if (requestedTimestamp > asOfTimestamp) {
                throw LookaheadViolationException(
                    requestedTimestamp = requestedTimestamp,
                    asOfTimestamp = asOfTimestamp
                )
            }
        }
    }
}
