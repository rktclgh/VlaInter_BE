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
    private val clientIpResolver = ClientIpResolver("127.0.0.1/32,::1/128", "X-Internal-Client-IP")

    private fun filter(): OriginValidationFilter {
        return OriginValidationFilter(
            CorsProperties(listOf("http://localhost:5173")),
            authCookieManager,
            clientIpResolver,
            jacksonObjectMapper()
        )
    }

    @Test
    fun `same-origin authenticated post is allowed`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/users/me/service-mode").apply {
            addHeader("Origin", "https://vlainter.online")
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            remoteAddr = "127.0.0.1"
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `cross-origin authenticated post is blocked`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/users/me/service-mode").apply {
            addHeader("Origin", "https://evil.example")
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            remoteAddr = "203.0.113.10"
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("허용되지 않은 요청 출처"))
    }

    @Test
    fun `configured cross-origin frontend is allowed`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/auth/logout").apply {
            addHeader("Origin", "http://localhost:5173")
            addHeader("Host", "localhost:8080")
            addHeader("X-Forwarded-Host", "localhost:8080")
            remoteAddr = "127.0.0.1"
            setCookies(Cookie("vlainter_rt", "refresh-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `protected request without origin or referer is blocked`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/auth/refresh").apply {
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            remoteAddr = "203.0.113.10"
            setCookies(Cookie("vlainter_rt", "refresh-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }

    @Test
    fun `referer is used when origin header is missing`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/auth/logout").apply {
            addHeader("Referer", "http://localhost:5173/content/mypage")
            addHeader("Host", "localhost:8080")
            addHeader("X-Forwarded-Host", "localhost:8080")
            remoteAddr = "127.0.0.1"
            setCookies(Cookie("vlainter_rt", "refresh-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `safe methods are not protected even with auth cookie`() {
        val filter = filter()

        listOf("GET", "HEAD", "OPTIONS").forEach { method ->
            val request = MockHttpServletRequest(method, "/api/users/me/service-mode").apply {
                setCookies(Cookie("vlainter_at", "access-token"))
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, MockFilterChain())

            assertEquals(200, response.status)
        }
    }

    @Test
    fun `non api path is not protected`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/content/interview").apply {
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `login endpoint is protected even without auth cookies`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/auth/login").apply {
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "vlainter.online")
            remoteAddr = "203.0.113.10"
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }

    @Test
    fun `public webhook without auth cookies is not protected`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/payments/portone/webhook")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `forwarded host from trusted proxy is honored`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/users/me/service-mode").apply {
            addHeader("Origin", "https://vlainter.online")
            addHeader("Host", "internal-backend.local:8080")
            addHeader("X-Forwarded-Host", "vlainter.online")
            remoteAddr = "127.0.0.1"
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    @Test
    fun `spoofed forwarded host from untrusted client is ignored`() {
        val filter = filter()
        val request = MockHttpServletRequest("POST", "/api/users/me/service-mode").apply {
            addHeader("Origin", "https://evil.example")
            addHeader("Host", "vlainter.online")
            addHeader("X-Forwarded-Host", "evil.example")
            remoteAddr = "203.0.113.10"
            setCookies(Cookie("vlainter_at", "access-token"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }
}
