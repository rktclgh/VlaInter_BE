package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.CorsProperties
import com.cw.vlainter.global.exception.ApiErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * 쿠키 기반 인증 요청의 교차 출처 남용을 줄이기 위해 Origin/Referer를 검증한다.
 *
 * - GET/HEAD/OPTIONS 등 safe method는 제외
 * - 인증 쿠키가 실린 요청 또는 refresh/logout 요청만 검증
 * - same-origin 또는 명시적 allowlist origin만 허용
 */
@Component
class OriginValidationFilter(
    corsProperties: CorsProperties,
    private val authCookieManager: AuthCookieManager,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {
    private val normalizedAllowedOrigins = corsProperties.allowedOrigins
        .mapNotNull(::normalizeOrigin)
        .toSet()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (!shouldProtect(request)) {
            filterChain.doFilter(request, response)
            return
        }

        val requestAuthority = resolveRequestAuthority(request)
        val candidateOrigin = extractRequestOrigin(request)

        if (candidateOrigin == null || !isAllowedOrigin(candidateOrigin, requestAuthority)) {
            writeForbiddenResponse(response, request.requestURI)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun shouldProtect(request: HttpServletRequest): Boolean {
        if (request.method.uppercase() !in UNSAFE_METHODS) {
            return false
        }
        if (!request.requestURI.startsWith("/api/")) {
            return false
        }
        if (request.requestURI in ORIGIN_EXEMPT_PATHS) {
            return false
        }

        val hasAccessCookie = !authCookieManager.extractAccessToken(request).isNullOrBlank()
        val hasRefreshCookie = !authCookieManager.extractRefreshToken(request).isNullOrBlank()
        if (hasAccessCookie || hasRefreshCookie) {
            return true
        }

        return request.requestURI in ALWAYS_PROTECTED_PUBLIC_PATHS
    }

    private fun extractRequestOrigin(request: HttpServletRequest): String? {
        val origin = request.getHeader("Origin")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let(::normalizeOrigin)
        if (origin != null) {
            return origin
        }

        val referer = request.getHeader("Referer")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            val uri = URI(referer)
            normalizeOrigin("${uri.scheme}://${uri.authority}")
        }.getOrNull()
    }

    private fun resolveRequestAuthority(request: HttpServletRequest): String? {
        val forwardedHost = request.getHeader("X-Forwarded-Host")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val host = forwardedHost
            ?: request.getHeader("Host")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: request.serverName
                ?.takeIf { it.isNotBlank() }
                ?.let { serverName ->
                    if (request.serverPort > 0) "$serverName:${request.serverPort}" else serverName
                }
            ?: return null

        return normalizeAuthority(host)
    }

    private fun isAllowedOrigin(origin: String, requestAuthority: String?): Boolean {
        if (origin in normalizedAllowedOrigins) {
            return true
        }

        val originAuthority = extractAuthority(origin) ?: return false
        return requestAuthority != null && originAuthority == requestAuthority
    }

    private fun normalizeOrigin(raw: String): String? {
        return runCatching {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val port = normalizePort(scheme, uri.port)
            if (port == defaultPort(scheme)) {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        }.getOrNull()
    }

    private fun extractAuthority(origin: String): String? {
        return runCatching {
            val uri = URI(origin)
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val port = normalizePort(scheme, uri.port)
            if (port == defaultPort(scheme)) {
                host
            } else {
                "$host:$port"
            }
        }.getOrNull()
    }

    private fun normalizeAuthority(raw: String): String? {
        return runCatching {
            val uri = URI("http://${raw.trim()}")
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port
            if (port == -1 || port == 80 || port == 443) {
                host
            } else {
                "$host:$port"
            }
        }.getOrNull()
    }

    private fun normalizePort(scheme: String, rawPort: Int): Int {
        if (rawPort > 0) return rawPort
        return defaultPort(scheme)
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun writeForbiddenResponse(response: HttpServletResponse, requestUri: String) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.writer,
            ApiErrorResponse(
                status = HttpStatus.FORBIDDEN.value(),
                code = HttpStatus.FORBIDDEN.name,
                message = "허용되지 않은 요청 출처입니다.",
                path = requestUri
            )
        )
        response.writer.flush()
    }

    private companion object {
        val UNSAFE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val ALWAYS_PROTECTED_PUBLIC_PATHS = setOf(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout"
        )
        val ORIGIN_EXEMPT_PATHS = setOf(
            "/api/payments/portone/webhook"
        )
    }
}
