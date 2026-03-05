package com.cw.vlainter.domain.payment.model

data class PointChargePolicy(
    val productId: String,
    val amount: Int,
    val rewardPoint: Long,
    val sortOrder: Int
)
