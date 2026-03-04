package com.cw.vlainter.domain.user.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChangeMyPasswordRequest(
    @field:NotBlank(message = "현재 비밀번호를 입력해 주세요.")
    @field:Size(min = 8, max = 100, message = "현재 비밀번호 형식이 올바르지 않습니다.")
    val currentPassword: String,

    @field:NotBlank(message = "새 비밀번호를 입력해 주세요.")
    @field:Size(min = 8, max = 100, message = "새 비밀번호는 8자 이상 100자 이하여야 합니다.")
    val newPassword: String
)
