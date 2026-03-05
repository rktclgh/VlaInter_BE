package com.cw.vlainter.domain.payment.dto

import com.cw.vlainter.domain.payment.entity.PointChargeStatus

data class RefundPointChargeResponse(
    val chargeId: Long,
    val status: PointChargeStatus,
    val refundedPoint: Long,
    val currentPoint: Long
)
