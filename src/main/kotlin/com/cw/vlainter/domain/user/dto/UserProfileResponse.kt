package com.cw.vlainter.domain.user.dto

import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus

data class UserProfileResponse(
    val userId: Long,
    val email: String,
    val name: String,
    val role: UserRole,
    val status: UserStatus,
    val point: Long,
    val hasGeminiApiKey: Boolean
)
