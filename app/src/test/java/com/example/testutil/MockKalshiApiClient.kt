package com.example.testutil

import com.example.kalshi.KalshiApiClient
import com.example.kalshi.KalshiBalance
import com.example.kalshi.KalshiOrderRequest
import com.example.kalshi.KalshiOrderResponse
import com.example.kalshi.KalshiPosition

class MockKalshiApiClient(
    var submitResult: Result<KalshiOrderResponse> = Result.success(
        KalshiOrderResponse(
            orderId = "mock_ord_1",
            clientOrderId = "cid_mock_1",
            ticker = "KXBTC15M-TEST",
            status = "executed",
            action = "buy",
            side = "yes",
            count = 1,
            filledCount = 1,
            price = 50,
            placeTimeMs = System.currentTimeMillis()
        )
    ),
    var authenticated: Boolean = true
) : KalshiApiClient() {

    val submittedOrders = mutableListOf<KalshiOrderRequest>()

    override fun isAuthenticated(): Boolean {
        return authenticated
    }

    override suspend fun submitOrder(order: KalshiOrderRequest): Result<KalshiOrderResponse> {
        submittedOrders.add(order)
        return submitResult
    }

    override suspend fun getPortfolioBalance(): Result<KalshiBalance> {
        return Result.success(KalshiBalance(balanceCents = 10000))
    }

    override suspend fun getPositions(): Result<List<KalshiPosition>> {
        return Result.success(emptyList())
    }
}
