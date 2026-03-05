package com.cw.vlainter.domain.payment.dto

data class ConfirmPointChargeResponse(
    val merchantUid: String,
    val impUid: String,
    val paidAmount: Int,
    val chargedPoint: Long,
    val currentPoint: Long
)
