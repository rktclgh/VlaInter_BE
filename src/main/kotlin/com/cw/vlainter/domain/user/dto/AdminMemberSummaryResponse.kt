package com.cw.vlainter.domain.user.dto

import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import java.time.OffsetDateTime

data class AdminMemberSummaryResponse(
    val memberId: Long,
    val email: String,
    val name: String,
    val status: UserStatus,
    val role: UserRole,
    val createdAt: OffsetDateTime?
)
