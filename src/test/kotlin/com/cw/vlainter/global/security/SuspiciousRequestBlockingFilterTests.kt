package com.cw.vlainter.global.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@ExtendWith(MockitoExtension::class)
class SuspiciousRequestBlockingFilterTests {
    @Mock
    lateinit var suspiciousRequestBlockService: SuspiciousRequestBlockService

    @Mock
    lateinit var filterChain: FilterChain

    private val objectMapper = ObjectMapper()

    @Test
    fun `returns 429 when client is already blocked`() {
        val filter = SuspiciousRequestBlockingFilter(suspiciousRequestBlockService, objectMapper)
        val request = MockHttpServletRequest("GET", "/.env").apply { remoteAddr = "127.0.0.1" }
        val response = MockHttpServletResponse()
        given(suspiciousRequestBlockService.isBlocked("127.0.0.1")).willReturn(true)

        filter.doFilter(request, response, filterChain)

        assertEquals(429, response.status)
        assertTrue(response.contentAsString.contains("비정상 요청이 반복 감지되어 잠시 차단되었습니다."))
        then(filterChain).shouldHaveNoInteractions()
    }

    @Test
    fun `passes normal request through when client is not blocked`() {
        val filter = SuspiciousRequestBlockingFilter(suspiciousRequestBlockService, objectMapper)
        val request = MockHttpServletRequest("GET", "/api/interview/categories").apply { remoteAddr = "127.0.0.1" }
        val response = MockHttpServletResponse()
        given(suspiciousRequestBlockService.isBlocked("127.0.0.1")).willReturn(false)
        given(
            suspiciousRequestBlockService.recordSuspiciousRequest(
                "127.0.0.1",
                "GET",
                "/api/interview/categories"
            )
        ).willReturn(false)

        filter.doFilter(request, response, filterChain)

        then(filterChain).should().doFilter(request, response)
    }
}
