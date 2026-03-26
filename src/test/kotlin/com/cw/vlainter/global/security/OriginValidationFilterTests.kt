package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.CookieProperties
import com.cw.vlainter.global.config.properties.CorsProperties
import com.cw.vlainter.global.config.properties.JwtProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class OriginValidationFilterTests {
    private val authCookieManager = AuthCookieManager(
        CookieProperties(
            domain = "localhost",
            secure = false,
            sameSite = "Lax",
            accessTokenName = "vlainter_at",
            refreshTokenName = "vlainter_rt"
        ),
        JwtProperties(
            issuer = "vlainter-test",
            accessTokenExpSeconds = 7200,
            refreshTokenExpSeconds = 120,
            accessSecret = "12345678901234567890123456789012",
            refreshSecret = "abcdefghijklmnopqrstuvwxyz123456"
        )
    )

    @Test
    fun `same-origin authenticated post is allowed`() {
        val filter = OriginValidationFilter(
            CorsProperties(listOf("http://localhost:5173")),
            authCookieManager,
            jacksonObjectMapper()
        )
        val request = MockHttpServletRequest("POST", "/api/users/me/service-mode").apply {
            addHeader("Origin", "https://vlainter.online")
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `cross-origin authenticated post is blocked`() {
        val filter = OriginValidationFilter(
            CorsProperties(listOf("http://localhost:5173")),
            authCookieManager,
            jacksonObjectMapper()
        )
        val request = MockHttpServletRequest("POST", "/api/users/me/service-mode").apply {
            addHeader("Origin", "https://evil.example")
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("허용되지 않은 요청 출처"))
    }

    @Test
    fun `configured cross-origin frontend is allowed`() {
        val filter = OriginValidationFilter(
            CorsProperties(listOf("http://localhost:5173")),
            authCookieManager,
            jacksonObjectMapper()
        )
        val request = MockHttpServletRequest("POST", "/api/auth/logout").apply {
            addHeader("Origin", "http://localhost:5173")
            addHeader("Host", "localhost:8080")
            addHeader("X-Forwarded-Host", "localhost:8080")
            setCookies(Cookie("vlainter_rt", "refresh-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `protected request without origin or referer is blocked`() {
        val filter = OriginValidationFilter(
            CorsProperties(listOf("http://localhost:5173")),
            authCookieManager,
            jacksonObjectMapper()
        )
        val request = MockHttpServletRequest("POST", "/api/auth/refresh").apply {
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            setCookies(Cookie("vlainter_rt", "refresh-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }

    @Test
    fun `public webhook without auth cookies is not protected`() {
        val filter = OriginValidationFilter(
            CorsProperties(listOf("http://localhost:5173")),
            authCookieManager,
            jacksonObjectMapper()
        )
        val request = MockHttpServletRequest("POST", "/api/payments/portone/webhook")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }
}
