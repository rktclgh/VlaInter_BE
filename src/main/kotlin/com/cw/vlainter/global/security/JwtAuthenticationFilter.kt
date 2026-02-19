package com.cw.vlainter.global.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청마다 Access Token 쿠키를 읽어 인증 컨텍스트를 구성한다.
 *
 * 검증 순서:
 * 1) Access Token 파싱/서명/만료 검증
 * 2) sid + userId 기준 Redis 로그인 세션 활성 여부 검증
 * 3) 통과 시 SecurityContext에 AuthPrincipal 저장
 */
@Component
class JwtAuthenticationFilter(
    private val authCookieManager: AuthCookieManager,
    private val jwtTokenProvider: JwtTokenProvider,
    private val loginSessionStore: LoginSessionStore
) : OncePerRequestFilter() {
    /**
     * 인증이 필요한 요청에 대해 SecurityContext를 채운다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val accessToken = authCookieManager.extractAccessToken(request)
        if (accessToken.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        val claims = runCatching { jwtTokenProvider.parseAccessToken(accessToken) }.getOrNull()
        if (claims == null) {
            filterChain.doFilter(request, response)
            return
        }

        if (!loginSessionStore.isActive(claims.sessionId, claims.userId)) {
            filterChain.doFilter(request, response)
            return
        }

        val principal = AuthPrincipal(
            userId = claims.userId,
            email = claims.email,
            sessionId = claims.sessionId
        )
        val authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        SecurityContextHolder.getContext().authentication = authentication
        filterChain.doFilter(request, response)
    }

    /**
     * 로그인/재발급/로그아웃 및 문서 경로는 필터 적용을 건너뛴다.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == "/api/auth/login" ||
            path == "/api/auth/refresh" ||
            path == "/api/auth/logout" ||
            path.startsWith("/swagger-ui/") ||
            path.startsWith("/v3/api-docs")
    }
}

/**
 * 인증 성공 후 애플리케이션에서 참조하는 사용자 컨텍스트.
 */
data class AuthPrincipal(
    val userId: Long,
    val email: String,
    val sessionId: String
)
