package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.auth.dto.LoginRequest
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.JwtTokenProvider
import com.cw.vlainter.global.security.LoginSessionStore
import com.cw.vlainter.global.security.RedirectUriValidator
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 인증 도메인 핵심 로직을 담당한다.
 *
 * 설계 원칙:
 * - 인증 상태의 최종 진실원천은 Redis 세션(LoginSessionStore)
 * - JWT는 세션 식별자(sid)를 운반하는 서명 토큰
 * - Access/Refresh 모두 쿠키로 전달하며 Refresh는 회전(rotating)한다.
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val loginSessionStore: LoginSessionStore,
    private val redirectUriValidator: RedirectUriValidator
) {
    /**
     * 로그인 처리.
     *
     * - 이메일/비밀번호 검증
     * - 계정 상태 검증(ACTIVE만 허용)
     * - 세션 ID 발급 후 Access/Refresh 발급
     * - Redis에 세션 상태와 Refresh 해시 저장
     */
    fun login(request: LoginRequest): LoginResult {
        val user = userRepository.findByEmail(request.email)
            .orElseThrow { unauthorizedException() }

        validateUserForLogin(user)

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw unauthorizedException()
        }

        val sessionId = UUID.randomUUID().toString()
        val accessToken = jwtTokenProvider.createAccessToken(user.id, user.email, sessionId)
        val refreshToken = jwtTokenProvider.createRefreshToken(user.id, sessionId)
        loginSessionStore.create(sessionId, user.id, refreshToken)

        return LoginResult(
            userId = user.id,
            email = user.email,
            name = user.name,
            accessToken = accessToken,
            refreshToken = refreshToken,
            redirectUri = redirectUriValidator.validate(request.redirectUri)
        )
    }

    /**
     * Refresh Token 재발급 처리.
     *
     * - 토큰 서명/만료 검증
     * - sid + userId + refresh 해시 일치 여부를 Redis에서 검증
     * - 동일 sid로 새 토큰 발급 후 Refresh 해시를 교체
     */
    fun refresh(refreshToken: String): TokenPair {
        if (!jwtTokenProvider.isValidRefreshToken(refreshToken)) {
            throw unauthorizedException()
        }

        val userId = jwtTokenProvider.extractUserIdFromRefreshToken(refreshToken)
        val sessionId = jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)
        val validSession = loginSessionStore.validateRefreshToken(sessionId, userId, refreshToken)
        if (!validSession) {
            loginSessionStore.delete(sessionId)
            throw unauthorizedException()
        }

        val user = userRepository.findById(userId).orElseThrow { unauthorizedException() }
        validateUserForLogin(user)

        val newAccessToken = jwtTokenProvider.createAccessToken(user.id, user.email, sessionId)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(user.id, sessionId)
        loginSessionStore.rotateRefreshToken(sessionId, newRefreshToken)

        return TokenPair(newAccessToken, newRefreshToken)
    }

    /**
     * 로그아웃 처리.
     *
     * Refresh Token에서 sid를 추출해 Redis 세션을 삭제한다.
     * Access Token은 짧은 수명을 가지며, 세션 삭제 이후 필터 단계에서 차단된다.
     */
    fun logout(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) return
        if (!jwtTokenProvider.isValidRefreshToken(refreshToken)) return

        val sessionId = jwtTokenProvider.extractSessionIdFromRefreshToken(refreshToken)
        loginSessionStore.delete(sessionId)
    }

    /**
     * 로그인 가능한 계정 상태인지 검증한다.
     */
    private fun validateUserForLogin(user: User) {
        if (user.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "사용할 수 없는 계정입니다.")
        }
    }

    /**
     * 인증 실패 공통 예외.
     *
     * 계정 존재 여부를 노출하지 않기 위해 동일 메시지로 응답한다.
     */
    private fun unauthorizedException(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
    }
}

/**
 * 로그인 성공 시 컨트롤러로 전달되는 내부 결과 객체.
 */
data class LoginResult(
    val userId: Long,
    val email: String,
    val name: String,
    val accessToken: String,
    val refreshToken: String,
    val redirectUri: String?
)

/**
 * Refresh 성공 시 새 토큰 쌍을 전달한다.
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String
)
