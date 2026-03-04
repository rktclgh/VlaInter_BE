package com.cw.vlainter.domain.user.dto

data class AdminMemberListResponse(
    val totalCount: Int,
    val members: List<AdminMemberSummaryResponse>
)
