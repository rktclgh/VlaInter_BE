package com.cw.vlainter.domain.auth.controller

import com.cw.vlainter.domain.auth.dto.LoginRequest
import com.cw.vlainter.domain.auth.dto.LoginResponse
import com.cw.vlainter.domain.auth.dto.KakaoLoginRequest
import com.cw.vlainter.domain.auth.dto.SignupRequest
import com.cw.vlainter.domain.auth.service.AuthService
import com.cw.vlainter.domain.auth.service.KakaoAuthService
import com.cw.vlainter.global.security.AuthPrincipal
import com.cw.vlainter.global.security.AuthCookieManager
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.LinkedHashMap

/**
 * 인증 관련 HTTP API를 제공한다.
 *
 * 토큰은 응답 본문이 아니라 HttpOnly 쿠키로만 내려주며,
 * 클라이언트는 쿠키 기반으로 인증 상태를 유지한다.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val kakaoAuthService: KakaoAuthService,
    private val authCookieManager: AuthCookieManager
) {
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    @PostMapping("/signup")
    fun signup(
        @Valid
        @RequestBody request: SignupRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Map<String, Any>> {
        val email = request.email.trim().lowercase()
        val clientIp = extractClientIp(servletRequest)
        logger.info(
            "Auth signup attempt email={} name={} passwordLength={} ip={}",
            email,
            request.name,
            request.password.length,
            clientIp
        )

        try {
            val createdUser = authService.signup(request)
            logger.info("Auth signup success userId={} email={} ip={}", createdUser.id, createdUser.email, clientIp)
            return ResponseEntity.ok(
                mapOf(
                    "message" to "회원가입이 완료되었습니다.",
                    "userId" to createdUser.id,
                    "email" to createdUser.email,
                    "name" to createdUser.name
                )
            )
        } catch (ex: ResponseStatusException) {
            logger.warn(
                "Auth signup failed email={} status={} reason={} ip={}",
                email,
                ex.statusCode.value(),
                ex.reason,
                clientIp
            )
            throw ex
        }
    }

    /**
     * 현재 인증된 사용자 정보를 조회한다.
     *
     * JwtAuthenticationFilter에서 Access Token + Redis 세션 검증을 통과한 경우에만
     * AuthenticationPrincipal이 채워진다.
     */
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(
            mapOf(
                "userId" to principal.userId,
                "email" to principal.email
            )
        )
    }

    /**
     * 로그인 처리.
     *
     * 1) 이메일/비밀번호 검증
     * 2) JWT Access/Refresh 토큰 발급
     * 3) Redis에 로그인 세션 저장
     * 4) HttpOnly 쿠키로 토큰 전달
     */
    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        response: HttpServletResponse,
        servletRequest: HttpServletRequest
    ): ResponseEntity<LoginResponse> {
        val email = request.email.trim().lowercase()
        val clientIp = extractClientIp(servletRequest)
        logger.info(
            "Auth login attempt email={} hasRedirectUri={} passwordLength={} ip={}",
            email,
            !request.redirectUri.isNullOrBlank(),
            request.password.length,
            clientIp
        )

        try {
            invalidateExistingSession(servletRequest, response)
            val result = authService.login(request)
            addAuthCookies(response, result.accessToken, result.refreshToken)
            logger.info("Auth login success userId={} email={} ip={}", result.userId, result.email, clientIp)

            return ResponseEntity.ok(
                LoginResponse(
                    userId = result.userId,
                    email = result.email,
                    name = result.name,
                    role = result.role,
                    redirectUri = result.redirectUri
                )
            )
        } catch (ex: ResponseStatusException) {
            logger.warn(
                "Auth login failed email={} status={} reason={} ip={}",
                email,
                ex.statusCode.value(),
                ex.reason,
                clientIp
            )
            throw ex
        }
    }

    @PostMapping("/kakao/login")
    fun kakaoLogin(
        @Valid
        @RequestBody request: KakaoLoginRequest,
        response: HttpServletResponse,
        servletRequest: HttpServletRequest
    ): ResponseEntity<LoginResponse> {
        val clientIp = extractClientIp(servletRequest)
        logger.info(
            "Auth kakao login attempt hasRedirectUri={} hasClientId={} ip={}",
            !request.redirectUri.isNullOrBlank(),
            !request.clientId.isNullOrBlank(),
            clientIp
        )

        try {
            invalidateExistingSession(servletRequest, response)
            val result = kakaoAuthService.loginOrSignupWithKakao(
                code = request.code,
                redirectUri = request.redirectUri,
                clientIdFromClient = request.clientId
            )
            addAuthCookies(response, result.accessToken, result.refreshToken)
            logger.info("Auth kakao login success userId={} email={} ip={}", result.userId, result.email, clientIp)

            return ResponseEntity.ok(
                LoginResponse(
                    userId = result.userId,
                    email = result.email,
                    name = result.name,
                    role = result.role,
                    redirectUri = result.redirectUri
                )
            )
        } catch (ex: ResponseStatusException) {
            logger.warn(
                "Auth kakao login failed status={} reason={} ip={}",
                ex.statusCode.value(),
                ex.reason,
                clientIp
            )
            throw ex
        }
    }

    private fun extractClientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.split(",").first().trim()
        }
        return request.remoteAddr ?: "unknown"
    }

    /**
     * Refresh Token 기반 토큰 재발급.
     *
     * 쿠키에서 Refresh Token을 읽어 서비스에서 검증/회전 후
     * 새 Access/Refresh 토큰 쿠키를 다시 내려준다.
     */
    @PostMapping("/refresh")
    fun refresh(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, String>> {
        val refreshToken = authCookieManager.extractRefreshToken(request)
        val tokenPair = authService.refresh(refreshToken ?: "")

        addAuthCookies(response, tokenPair.accessToken, tokenPair.refreshToken)
        return ResponseEntity.ok(mapOf("message" to "토큰이 재발급되었습니다."))
    }

    /**
     * 로그아웃 처리.
     *
     * Redis 로그인 세션을 제거하고 Access/Refresh 쿠키를 즉시 만료시킨다.
     */
    @PostMapping("/logout")
    fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<Map<String, String>> {
        val accessToken = authCookieManager.extractAccessToken(request)
        val refreshToken = authCookieManager.extractRefreshToken(request)
        authService.logoutByTokens(accessToken, refreshToken)

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearAccessTokenCookie().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearRefreshTokenCookie().toString())

        val body = LinkedHashMap<String, String>()
        body["message"] = "로그아웃되었습니다."
        return ResponseEntity.ok(body)
    }

    /**
     * 인증 쿠키를 응답 헤더(Set-Cookie)로 추가한다.
     */
    private fun addAuthCookies(response: HttpServletResponse, accessToken: String, refreshToken: String) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.createAccessTokenCookie(accessToken).toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.createRefreshTokenCookie(refreshToken).toString())
    }

    private fun invalidateExistingSession(request: HttpServletRequest, response: HttpServletResponse) {
        val existingAccessToken = authCookieManager.extractAccessToken(request)
        val existingRefreshToken = authCookieManager.extractRefreshToken(request)
        if (!existingAccessToken.isNullOrBlank() || !existingRefreshToken.isNullOrBlank()) {
            authService.logoutByTokens(existingAccessToken, existingRefreshToken)
        }
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearAccessTokenCookie().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.clearRefreshTokenCookie().toString())
    }
}
