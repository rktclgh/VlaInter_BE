package com.cw.vlainter.domain.user.dto

import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import java.time.OffsetDateTime

data class AdminMemberDetailResponse(
    val memberId: Long,
    val email: String,
    val name: String,
    val status: UserStatus,
    val role: UserRole,
    val point: Long,
    val free: Int,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?
)
