package com.cw.vlainter.domain.payment.dto

data class PointChargeProductResponse(
    val productId: String,
    val amount: Int,
    val rewardPoint: Long
)
