package com.cw.vlainter.domain.auth.dto

/**
 * 로그인 성공 응답 DTO.
 *
 * 실제 인증 토큰은 본문이 아닌 HttpOnly 쿠키로 전달되며,
 * 본문에는 화면 전환/표시 용도의 최소 사용자 정보만 포함한다.
 */
data class LoginResponse(
    val userId: Long,
    val email: String,
    val name: String,
    val redirectUri: String? = null
)
