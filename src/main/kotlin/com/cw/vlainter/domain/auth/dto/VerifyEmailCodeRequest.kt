package com.cw.vlainter.domain.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class VerifyEmailCodeRequest(
    @field:NotBlank(message = "이메일을 입력해 주세요.")
    @field:Email(message = "유효한 이메일 형식이 아닙니다.")
    @field:Size(max = 320, message = "이메일 길이가 너무 깁니다.")
    val email: String,

    @field:NotBlank(message = "인증 코드를 입력해 주세요.")
    @field:Pattern(regexp = "\\d{4,10}", message = "인증 코드는 4~10자리 숫자여야 합니다.")
    val code: String
)
