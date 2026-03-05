package com.cw.vlainter.domain.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class KakaoLoginRequest(
    @field:NotBlank(message = "인가 코드(code)를 입력해 주세요.")
    val code: String,

    @field:Size(max = 500, message = "redirectUri 길이가 너무 깁니다.")
    val redirectUri: String? = null,

    @field:Size(max = 200, message = "clientId 길이가 너무 깁니다.")
    val clientId: String? = null
)

