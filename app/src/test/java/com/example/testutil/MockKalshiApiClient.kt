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
    val cancelledOrders = mutableListOf<String>()

    var cancelResult: Result<Boolean> = Result.success(true)
    var openOrdersResult: Result<List<KalshiOrderResponse>> = Result.success(emptyList())
    var getOrderResult: ((String) -> Result<KalshiOrderResponse>)? = null
    var balanceResult: Result<KalshiBalance> = Result.success(KalshiBalance(balanceCents = 10000))
    var positionsResult: Result<List<KalshiPosition>> = Result.success(emptyList())

    override fun isAuthenticated(): Boolean {
        return authenticated
    }

    override suspend fun submitOrder(order: KalshiOrderRequest): Result<KalshiOrderResponse> {
        submittedOrders.add(order)
        return submitResult
    }

    override suspend fun cancelOrder(orderId: String): Result<Boolean> {
        cancelledOrders.add(orderId)
        return cancelResult
    }

    override suspend fun getOpenOrders(): Result<List<KalshiOrderResponse>> {
        return openOrdersResult
    }

    override suspend fun getOrder(orderId: String): Result<KalshiOrderResponse> {
        return getOrderResult?.invoke(orderId) ?: Result.success(
            KalshiOrderResponse(
                orderId = orderId,
                clientOrderId = "cid_$orderId",
                ticker = "KXBTC15M-TEST",
                status = "executed",
                action = "buy",
                side = "bid",
                count = 1,
                filledCount = 1,
                price = 50,
                placeTimeMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun getPortfolioBalance(): Result<KalshiBalance> {
        return balanceResult
    }

    override suspend fun getPositions(): Result<List<KalshiPosition>> {
        return positionsResult
    }
}
