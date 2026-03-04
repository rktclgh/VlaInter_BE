package com.cw.vlainter.domain.user.dto

import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus

data class UpdateMemberByAdminRequest(
    val name: String? = null,
    val status: UserStatus? = null,
    val role: UserRole? = null
)
