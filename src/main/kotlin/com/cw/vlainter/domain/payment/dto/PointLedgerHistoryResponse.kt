package com.cw.vlainter.domain.payment.dto

data class PointLedgerHistoryResponse(
    val currentPoint: Long,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
    val items: List<PointLedgerItemResponse>
)
