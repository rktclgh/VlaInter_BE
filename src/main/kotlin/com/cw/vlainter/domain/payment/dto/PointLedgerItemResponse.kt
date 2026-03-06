package com.cw.vlainter.domain.payment.dto

import java.time.OffsetDateTime

data class PointLedgerItemResponse(
    val occurredAt: OffsetDateTime,
    val pointDelta: Long,
    val description: String
)
