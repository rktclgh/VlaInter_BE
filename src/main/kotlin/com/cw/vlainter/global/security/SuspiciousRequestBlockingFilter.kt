package com.cw.vlainter.global.security

import com.cw.vlainter.global.exception.ApiErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SuspiciousRequestBlockingFilter(
    private val suspiciousRequestBlockService: SuspiciousRequestBlockService,
    private val clientIpResolver: ClientIpResolver,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {
    private val auditLogger = LoggerFactory.getLogger(SuspiciousRequestBlockingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestMethod = request.method
        val requestUri = request.requestURI
        val userAgent = request.getHeader("User-Agent")
        if (isAlwaysAllowedPublicAsset(requestUri) || isPreviewBotAllowedRequest(requestMethod, requestUri, userAgent)) {
            filterChain.doFilter(request, response)
            return
        }
        val suspiciousRequest = suspiciousRequestBlockService.isSuspiciousRequest(requestMethod, requestUri)
        val resolution = clientIpResolver.resolveDetail(request)
        val clientIp = resolution.clientIp
        if (!resolution.isReliableForSecurity) {
            if (suspiciousRequest && suspiciousRequestBlockService.shouldLogUnresolvedClientIp(clientIp, requestUri)) {
                auditLogger.warn(
                    "Skipping suspicious request blocking due to unresolved client IP source={} ipHash={} method={} path={}",
                    resolution.source,
                    SensitiveValueSanitizer.hash(clientIp),
                    requestMethod,
                    requestUri
                )
            }
            filterChain.doFilter(request, response)
            return
        }

        if (suspiciousRequestBlockService.isBlocked(clientIp)) {
            writeBlockedResponse(response, requestUri)
            if (suspiciousRequestBlockService.shouldLogBlockedRequest(clientIp)) {
                auditLogger.warn(
                    "Blocked request from suspicious client ipHash={} method={} path={}",
                    SensitiveValueSanitizer.hash(clientIp),
                    requestMethod,
                    requestUri
                )
            }
            return
        }

        if (!suspiciousRequest) {
            filterChain.doFilter(request, response)
            return
        }

        if (suspiciousRequestBlockService.recordSuspiciousRequest(clientIp, requestMethod, requestUri)) {
            writeBlockedResponse(response, requestUri)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun writeBlockedResponse(response: HttpServletResponse, requestUri: String) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                ApiErrorResponse(
                    status = HttpStatus.TOO_MANY_REQUESTS.value(),
                    code = HttpStatus.TOO_MANY_REQUESTS.name,
                    message = "비정상 요청이 반복 감지되어 잠시 차단되었습니다.",
                    path = requestUri
                )
            )
        )
        response.writer.flush()
    }

    private fun isAlwaysAllowedPublicAsset(requestUri: String): Boolean {
        val normalized = requestUri.lowercase()
        return normalized == "/favicon.ico" ||
            normalized == "/favicon.png" ||
            normalized == "/social-preview.png" ||
            normalized == "/robots.txt" ||
            normalized == "/manifest.webmanifest" ||
            normalized == "/site.webmanifest" ||
            APPLE_TOUCH_ICON_REGEX.matches(normalized)
    }

    private fun isPreviewBotAllowedRequest(method: String, requestUri: String, userAgent: String?): Boolean {
        if (!method.equals("GET", ignoreCase = true) && !method.equals("HEAD", ignoreCase = true)) {
            return false
        }
        val agent = userAgent?.lowercase()?.trim().orEmpty()
        if (agent.isBlank() || PREVIEW_BOT_MARKERS.none { it in agent }) {
            return false
        }
        val normalized = requestUri.lowercase()
        return normalized == "/" || isAlwaysAllowedPublicAsset(normalized)
    }

    private companion object {
        private val APPLE_TOUCH_ICON_REGEX = Regex("^/apple-touch-icon(?:-precomposed)?(?:-\\d+x\\d+)?\\.png$")
        val PREVIEW_BOT_MARKERS = listOf(
            "facebookexternalhit",
            "facebot",
            "kakaotalk",
            "slackbot",
            "discordbot",
            "twitterbot"
        )
    }
}
