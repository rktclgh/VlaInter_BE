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
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {
    private val auditLogger = LoggerFactory.getLogger(SuspiciousRequestBlockingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val clientIp = ClientIpResolver.resolve(request)
        if (suspiciousRequestBlockService.isBlocked(clientIp)) {
            writeBlockedResponse(response, request.requestURI)
            auditLogger.warn(
                "Blocked request from suspicious client ipHash={} method={} path={}",
                com.cw.vlainter.domain.auth.service.AuthLogSanitizer.hash(clientIp),
                request.method,
                request.requestURI
            )
            return
        }

        if (suspiciousRequestBlockService.recordSuspiciousRequest(clientIp, request.method, request.requestURI)) {
            writeBlockedResponse(response, request.requestURI)
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
    }
}
