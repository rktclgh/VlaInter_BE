package com.cw.vlainter.domain.payment.dto

import com.cw.vlainter.domain.payment.entity.PointChargeStatus
import java.time.OffsetDateTime

data class PointPaymentHistoryItemResponse(
    val chargeId: Long,
    val occurredAt: OffsetDateTime,
    val amount: Int,
    val pointDelta: Long,
    val paymentMethod: String,
    val status: PointChargeStatus,
    val paymentNumber: String,
    val refundable: Boolean
)
