package com.cw.vlainter.domain.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SendVerificationEmailRequest(
    @field:NotBlank(message = "이메일을 입력해 주세요.")
    @field:Email(message = "유효한 이메일 형식이 아닙니다.")
    @field:Size(max = 320, message = "이메일 길이가 너무 깁니다.")
    val email: String
)
