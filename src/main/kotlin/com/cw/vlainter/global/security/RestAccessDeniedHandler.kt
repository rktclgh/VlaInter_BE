package com.cw.vlainter.global.security

import com.cw.vlainter.global.exception.ApiErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

@Component
class RestAccessDeniedHandler(
    private val objectMapper: ObjectMapper
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException?
    ) {
        val requestPath = resolveRequestPath(request)
        if (!requestPath.startsWith("/api/")) {
            redirectToForbiddenPage(request, response)
            return
        }

        val body = ApiErrorResponse(
            status = HttpStatus.FORBIDDEN.value(),
            code = HttpStatus.FORBIDDEN.name,
            message = "접근 권한이 없습니다.",
            path = requestPath
        )
        response.status = HttpStatus.FORBIDDEN.value()
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

    private fun redirectToForbiddenPage(
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        response.status = HttpStatus.SEE_OTHER.value()
        response.setHeader("Location", "${request.contextPath}/errors/403")
    }
}
