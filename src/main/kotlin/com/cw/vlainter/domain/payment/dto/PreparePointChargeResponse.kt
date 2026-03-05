package com.cw.vlainter.domain.payment.dto

data class PreparePointChargeResponse(
    val productId: String,
    val merchantUid: String,
    val amount: Int,
    val rewardPoint: Long,
    val customerCode: String,
    val buyerEmail: String,
    val buyerName: String
)
