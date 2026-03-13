package com.cw.vlainter.global.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTests {
    @Test
    fun `returns remote address when request is not from trusted proxy`() {
        val resolver = ClientIpResolver("127.0.0.1/32")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            addHeader("CF-Connecting-IP", "198.51.100.24")
        }

        assertEquals("203.0.113.10", resolver.resolve(request))
    }

    @Test
    fun `prefers cf connecting ip for trusted proxy requests`() {
        val resolver = ClientIpResolver("172.16.0.0/12")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "172.18.0.5"
            addHeader("CF-Connecting-IP", "198.51.100.24")
            addHeader("X-Real-IP", "198.51.100.25")
            addHeader("X-Forwarded-For", "198.51.100.26, 172.18.0.5")
        }

        assertEquals("198.51.100.24", resolver.resolve(request))
    }

    @Test
    fun `falls back to x real ip and x forwarded for when cf header is absent`() {
        val resolver = ClientIpResolver("10.0.0.0/8")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "10.0.3.12"
            addHeader("X-Real-IP", "198.51.100.25")
            addHeader("X-Forwarded-For", "198.51.100.26, 10.0.3.12")
        }

        assertEquals("198.51.100.25", resolver.resolve(request))
    }

    @Test
    fun `falls back to x forwarded for when real ip header is absent`() {
        val resolver = ClientIpResolver("192.168.0.0/16")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "192.168.1.20"
            addHeader("X-Forwarded-For", "198.51.100.26, 192.168.1.20")
        }

        assertEquals("198.51.100.26", resolver.resolve(request))
    }
}
