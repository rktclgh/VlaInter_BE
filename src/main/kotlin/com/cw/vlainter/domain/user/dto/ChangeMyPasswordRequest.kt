package com.cw.vlainter.domain.user.dto

data class ChangeMyPasswordRequest(
    val currentPassword: String,
    val newPassword: String
)
