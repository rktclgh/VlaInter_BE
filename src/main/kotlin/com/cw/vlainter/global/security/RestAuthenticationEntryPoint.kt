package com.cw.vlainter.global.security

import com.cw.vlainter.global.exception.ApiErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class RestAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException?
    ) {
        val requestPath = resolveRequestPath(request)
        if (!requestPath.startsWith("/api/")) {
            response.sendRedirect("${request.contextPath}/errors/403")
            return
        }

        val body = ApiErrorResponse(
            status = HttpStatus.UNAUTHORIZED.value(),
            code = HttpStatus.UNAUTHORIZED.name,
            message = "인증이 필요합니다. 다시 로그인해 주세요.",
            path = requestPath
        )
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(body))
    }

    private fun resolveRequestPath(request: HttpServletRequest): String {
        val contextPath = request.contextPath.orEmpty()
        val requestUri = request.requestURI.orEmpty()
        return if (contextPath.isNotBlank() && requestUri.startsWith(contextPath)) {
            requestUri.removePrefix(contextPath)
        } else {
            requestUri
        }
    }
}
