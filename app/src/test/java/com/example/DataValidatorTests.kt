package com.example

import com.example.data.DataValidator
import com.example.data.ExchangeAgreementStatus
import com.example.data.PricePoint
import com.example.data.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataValidatorTests {

    private lateinit var validator: DataValidator

    @Before
    fun setup() {
        validator = DataValidator(maxAgeMillis = 5000L)
    }

    @Test
    fun testRejectStalePrice() {
        val now = 1000000L
        val staleTimestamp = now - 6000L // 6 seconds old (> 5 seconds)
        val stalePoint = PricePoint(price = 65000.0, timestamp = staleTimestamp)

        val result = validator.validate(stalePoint, currentTimestamp = now)
        assertTrue("Should reject stale price", result is ValidationResult.Invalid)
    }

    @Test
    fun testAcceptFreshPrice() {
        val now = 1000000L
        val freshTimestamp = now - 1500L // 1.5 seconds old
        val freshPoint = PricePoint(price = 65000.0, timestamp = freshTimestamp)

        val result = validator.validate(freshPoint, currentTimestamp = now)
        assertTrue("Should accept fresh price", result is ValidationResult.Valid)
    }

    @Test
    fun testRejectNullOrZeroPrice() {
        val nullResult = validator.validate(null)
        assertTrue(nullResult is ValidationResult.Invalid)

        val zeroPoint = PricePoint(price = 0.0, timestamp = System.currentTimeMillis())
        val zeroResult = validator.validate(zeroPoint)
        assertTrue(zeroResult is ValidationResult.Invalid)

        val negPoint = PricePoint(price = -100.0, timestamp = System.currentTimeMillis())
        val negResult = validator.validate(negPoint)
        assertTrue(negResult is ValidationResult.Invalid)
    }

    @Test
    fun testRejectInvalidTimestamp() {
        val invalidTimePoint = PricePoint(price = 65000.0, timestamp = -1L)
        val result = validator.validate(invalidTimePoint)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun testCrossExchangeAgreementLogic() {
        val now = System.currentTimeMillis()
        val binance = PricePoint(price = 65000.0, timestamp = now, exchange = "BINANCE")
        val krakenClose = PricePoint(price = 65020.0, timestamp = now, exchange = "KRAKEN") // 0.03% difference (<0.1%)

        val strongComp = validator.validateCrossExchange(binance, krakenClose)
        assertEquals(ExchangeAgreementStatus.STRONG_AGREEMENT, strongComp.agreementStatus)
        assertEquals(0.05, strongComp.confidenceAdjustment, 0.001)

        val krakenDivergent = PricePoint(price = 65500.0, timestamp = now, exchange = "KRAKEN") // 0.76% difference (>0.5%)
        val divComp = validator.validateCrossExchange(binance, krakenDivergent)
        assertEquals(ExchangeAgreementStatus.DISAGREEMENT, divComp.agreementStatus)
        assertEquals(-0.10, divComp.confidenceAdjustment, 0.001)
    }
}
