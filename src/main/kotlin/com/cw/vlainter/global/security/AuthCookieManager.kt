package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.CookieProperties
import com.cw.vlainter.global.config.properties.JwtProperties
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 인증 쿠키 생성/삭제/추출을 담당한다.
 *
 * 이 프로젝트에서 Access/Refresh 토큰은 로컬스토리지가 아니라
 * HttpOnly 쿠키로만 전달한다.
 */
@Component
class AuthCookieManager(
    private val cookieProperties: CookieProperties,
    private val jwtProperties: JwtProperties
) {
    /**
     * Access Token 쿠키 생성.
     */
    fun createAccessTokenCookie(accessToken: String): ResponseCookie {
        return buildCookie(
            name = cookieProperties.accessTokenName,
            value = accessToken,
            maxAgeSeconds = jwtProperties.accessTokenExpSeconds
        )
    }

    /**
     * Refresh Token 쿠키 생성.
     */
    fun createRefreshTokenCookie(refreshToken: String): ResponseCookie {
        return buildCookie(
            name = cookieProperties.refreshTokenName,
            value = refreshToken,
            maxAgeSeconds = jwtProperties.refreshTokenExpSeconds
        )
    }

    /**
     * Access Token 쿠키 만료(삭제)용 쿠키 생성.
     */
    fun clearAccessTokenCookie(): ResponseCookie {
        return buildCookie(
            name = cookieProperties.accessTokenName,
            value = "",
            maxAgeSeconds = 0
        )
    }

    /**
     * Refresh Token 쿠키 만료(삭제)용 쿠키 생성.
     */
    fun clearRefreshTokenCookie(): ResponseCookie {
        return buildCookie(
            name = cookieProperties.refreshTokenName,
            value = "",
            maxAgeSeconds = 0
        )
    }

    /**
     * 요청 쿠키에서 Refresh Token 값을 읽는다.
     */
    fun extractRefreshToken(request: HttpServletRequest): String? {
        return findCookie(request.cookies, cookieProperties.refreshTokenName)
    }

    /**
     * 요청 쿠키에서 Access Token 값을 읽는다.
     */
    fun extractAccessToken(request: HttpServletRequest): String? {
        return findCookie(request.cookies, cookieProperties.accessTokenName)
    }

    /**
     * 이름으로 쿠키 값을 찾는다.
     */
    private fun findCookie(cookies: Array<Cookie>?, targetName: String): String? {
        if (cookies == null) return null
        return cookies.firstOrNull { it.name == targetName }?.value
    }

    /**
     * 공통 옵션으로 인증 쿠키를 빌드한다.
     *
     * localhost에서는 브라우저 제약 때문에 domain을 강제로 넣지 않는다.
     */
    private fun buildCookie(name: String, value: String, maxAgeSeconds: Long): ResponseCookie {
        val builder = ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookieProperties.secure)
            .path("/")
            .maxAge(Duration.ofSeconds(maxAgeSeconds))
            .sameSite(cookieProperties.sameSite)

        val domain = cookieProperties.domain
        if (domain.isNotBlank() && !domain.equals("localhost", ignoreCase = true)) {
            builder.domain(domain)
        }

        return builder.build()
    }
}
