package com.cw.vlainter.domain.auth.dto

/**
 * 로그인 요청 DTO.
 *
 * redirectUri는 모바일/결제/외부 콜백으로 돌아갈 경로가 필요한 경우에만 전달한다.
 */
data class LoginRequest(
    val email: String,
    val password: String,
    val redirectUri: String? = null
)
