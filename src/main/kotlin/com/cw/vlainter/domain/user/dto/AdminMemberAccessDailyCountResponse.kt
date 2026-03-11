package com.cw.vlainter.domain.user.dto

data class AdminMemberAccessDailyCountResponse(
    val date: String,
    val loginCount: Int
)
