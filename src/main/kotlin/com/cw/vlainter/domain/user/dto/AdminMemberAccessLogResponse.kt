package com.cw.vlainter.domain.user.dto

import java.time.OffsetDateTime

data class AdminMemberAccessLogResponse(
    val sessionIdPrefix: String,
    val authProvider: String,
    val loginAt: OffsetDateTime,
    val lastActivityAt: OffsetDateTime?,
    val logoutAt: OffsetDateTime?,
    val actionCount: Long,
    val ipAddress: String?,
    val browser: String,
    val deviceType: String,
    val active: Boolean
)
