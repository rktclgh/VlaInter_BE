package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT 생성/파싱을 담당한다.
 *
 * - Access Token: user 식별 + sid 포함
 * - Refresh Token: 재발급용 + sid 포함
 * - sid를 통해 Redis 로그인 세션과 JWT를 연결한다.
 */
@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties
) {
    private val accessKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.accessSecret.toByteArray(StandardCharsets.UTF_8))
    }

    private val refreshKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.refreshSecret.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Access Token 생성.
     */
    fun createAccessToken(userId: Long, email: String, sessionId: String): String {
        val now = Instant.now()
        val expiry = now.plusSeconds(jwtProperties.accessTokenExpSeconds)

        return Jwts.builder()
            .subject(userId.toString())
            .issuer(jwtProperties.issuer)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .claim("email", email)
            .claim("sid", sessionId)
            .signWith(accessKey)
            .compact()
    }

    /**
     * Refresh Token 생성.
     */
    fun createRefreshToken(userId: Long, sessionId: String): String {
        val now = Instant.now()
        val expiry = now.plusSeconds(jwtProperties.refreshTokenExpSeconds)

        return Jwts.builder()
            .subject(userId.toString())
            .issuer(jwtProperties.issuer)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .claim("sid", sessionId)
            .signWith(refreshKey)
            .compact()
    }

    /**
     * Refresh Token의 서명/만료 유효성만 검증한다.
     * 세션 상태 검증은 LoginSessionStore에서 별도로 수행한다.
     */
    fun isValidRefreshToken(token: String): Boolean = runCatching { parseRefreshClaims(token) }.isSuccess

    /**
     * Refresh Token에서 사용자 ID(subject)를 추출한다.
     */
    fun extractUserIdFromRefreshToken(token: String): Long = parseRefreshClaims(token).subject.toLong()

    /**
     * Refresh Token에서 세션 ID(sid)를 추출한다.
     */
    fun extractSessionIdFromRefreshToken(token: String): String {
        return parseRefreshClaims(token).get("sid", String::class.java)
    }

    /**
     * Access Token 파싱 후 필터에서 사용하기 쉬운 형태로 변환한다.
     */
    fun parseAccessToken(token: String): AccessTokenClaims {
        val claims = parseAccessClaims(token)
        return AccessTokenClaims(
            userId = claims.subject.toLong(),
            email = claims.get("email", String::class.java),
            sessionId = claims.get("sid", String::class.java)
        )
    }

    /**
     * Refresh Token Claims 파싱.
     */
    private fun parseRefreshClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(refreshKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }

    /**
     * Access Token Claims 파싱.
     */
    private fun parseAccessClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(accessKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}

/**
 * Access Token에서 추출한 인증 컨텍스트.
 */
data class AccessTokenClaims(
    val userId: Long,
    val email: String,
    val sessionId: String
)
