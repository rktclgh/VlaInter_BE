package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.RedirectProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI

/**
 * 외부 리다이렉트 주소 검증기.
 *
 * 모바일 딥링크/결제/소셜 로그인 콜백 등에서 open redirect 취약점을 막기 위해
 * 사전에 등록된 allowlist만 허용한다.
 */
@Component
class RedirectUriValidator(
    private val redirectProperties: RedirectProperties
) {
    /**
     * redirect_uri를 검증하고 안전한 경우 그대로 반환한다.
     * 값이 비어 있으면 null을 반환해 선택 파라미터로 취급한다.
     */
    fun validate(redirectUri: String?): String? {
        if (redirectUri.isNullOrBlank()) return null

        if (!isAllowed(redirectUri)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 redirect_uri 입니다.")
        }

        return redirectUri
    }

    /**
     * allowlist와 완전 일치하거나 origin 기준으로 일치하면 허용한다.
     */
    private fun isAllowed(candidate: String): Boolean {
        return redirectProperties.allowedOrigins.any { allowed ->
            candidate == allowed || matchesByOrigin(candidate, allowed)
        }
    }

    /**
     * scheme/host/port가 일치하는지 비교한다.
     */
    private fun matchesByOrigin(candidate: String, allowed: String): Boolean {
        return runCatching {
            val candidateUri = URI(candidate)
            val allowedUri = URI(allowed)
            candidateUri.scheme.equals(allowedUri.scheme, ignoreCase = true) &&
                candidateUri.host.equals(allowedUri.host, ignoreCase = true) &&
                normalizePort(candidateUri) == normalizePort(allowedUri)
        }.getOrDefault(false)
    }

    /**
     * URI에 포트가 생략된 경우 기본 포트를 사용해 비교를 일관화한다.
     */
    private fun normalizePort(uri: URI): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }
}
