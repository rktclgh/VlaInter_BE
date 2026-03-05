package com.cw.vlainter.domain.user.dto

data class UpdateMyProfileRequest(
    val name: String? = null,
    val currentPassword: String? = null,
    val newPassword: String? = null
)
