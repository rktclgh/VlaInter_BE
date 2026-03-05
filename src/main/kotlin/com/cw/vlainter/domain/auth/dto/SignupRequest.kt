package com.cw.vlainter.domain.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SignupRequest(
    @field:NotBlank(message = "이름을 입력해 주세요.")
    @field:Size(max = 100, message = "이름은 100자를 초과할 수 없습니다.")
    val name: String,

    @field:NotBlank(message = "이메일을 입력해 주세요.")
    @field:Email(message = "유효한 이메일 형식이 아닙니다.")
    @field:Size(max = 320, message = "이메일 길이가 너무 깁니다.")
    val email: String,

    @field:NotBlank(message = "비밀번호를 입력해 주세요.")
    @field:Size(min = 8, max = 100, message = "비밀번호는 8~100자여야 합니다.")
    val password: String,
)
