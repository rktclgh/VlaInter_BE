package com.cw.vlainter.domain.user.dto

import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserServiceMode
import com.cw.vlainter.domain.user.entity.UserStatus

data class UserProfileResponse(
    val email: String,
    val name: String,
    val role: UserRole,
    val status: UserStatus,
    val point: Long,
    val serviceMode: UserServiceMode?,
    val universityId: Long? = null,
    val universityName: String?,
    val departmentId: Long? = null,
    val departmentName: String?,
    val hasAcademicProfile: Boolean,
    val hasGeminiApiKey: Boolean,
    val hasProfileImage: Boolean
)
