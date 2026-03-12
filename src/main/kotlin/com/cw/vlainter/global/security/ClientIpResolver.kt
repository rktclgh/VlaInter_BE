package com.cw.vlainter.global.security

import jakarta.servlet.http.HttpServletRequest

object ClientIpResolver {
    fun resolve(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.split(",").first().trim()
        }
        return request.remoteAddr ?: "unknown"
    }
}
