package com.cw.vlainter.global.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTests {
    @Test
    fun `returns remote address when request is not from trusted proxy`() {
        val resolver = ClientIpResolver("127.0.0.1/32", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            addHeader("X-Internal-Client-IP", "198.51.100.24")
        }

        assertEquals("203.0.113.10", resolver.resolve(request))
    }

    @Test
    fun `uses trusted internal client ip header for trusted proxy requests`() {
        val resolver = ClientIpResolver("172.16.0.0/12", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "172.18.0.5"
            addHeader("X-Internal-Client-IP", "198.51.100.24")
        }

        assertEquals("198.51.100.24", resolver.resolve(request))
    }

    @Test
    fun `uses first ip token from trusted internal header`() {
        val resolver = ClientIpResolver("10.0.0.0/8", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "10.0.3.12"
            addHeader("X-Internal-Client-IP", "198.51.100.25, 10.0.3.12")
        }

        assertEquals("198.51.100.25", resolver.resolve(request))
    }

    @Test
    fun `falls back to remote address when trusted proxy header is absent`() {
        val resolver = ClientIpResolver("192.168.0.0/16", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "192.168.1.20"
        }

        assertEquals("192.168.1.20", resolver.resolve(request))
    }

    @Test
    fun `falls back to remote address when trusted proxy header is empty`() {
        val resolver = ClientIpResolver("192.168.0.0/16", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "192.168.1.20"
            addHeader("X-Internal-Client-IP", "")
        }

        assertEquals("192.168.1.20", resolver.resolve(request))
    }

    @Test
    fun `falls back to remote address when trusted proxy header contains malformed ip`() {
        val resolver = ClientIpResolver("192.168.0.0/16", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "192.168.1.20"
            addHeader("X-Internal-Client-IP", "not-an-ip")
        }

        assertEquals("192.168.1.20", resolver.resolve(request))
    }

    @Test
    fun `ignores spoofed headers from untrusted proxy`() {
        val resolver = ClientIpResolver("10.0.0.0/8", "X-Internal-Client-IP")
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            addHeader("X-Internal-Client-IP", "198.51.100.24")
            addHeader("CF-Connecting-IP", "198.51.100.25")
            addHeader("X-Real-IP", "198.51.100.26")
            addHeader("X-Forwarded-For", "198.51.100.27, 203.0.113.10")
        }

        assertEquals("203.0.113.10", resolver.resolve(request))
    }
}
