package com.cw.vlainter.global.security

import com.cw.vlainter.domain.auth.service.AuthLogSanitizer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class SuspiciousRequestBlockServiceTests {
    @Mock
    lateinit var redisTemplate: StringRedisTemplate

    @Mock
    lateinit var valueOperations: ValueOperations<String, String>

    @Test
    fun `marks env probe as suspicious and increments counter`() {
        val service = SuspiciousRequestBlockService(redisTemplate, docsEnabled = false)
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.increment("security:probe:count:${AuthLogSanitizer.hash("127.0.0.1")}")).willReturn(1L)

        val blocked = service.recordSuspiciousRequest("127.0.0.1", "GET", "/.env")

        assertFalse(blocked)
        then(redisTemplate).should().expire(
            "security:probe:count:${AuthLogSanitizer.hash("127.0.0.1")}",
            Duration.ofMinutes(10)
        )
    }

    @Test
    fun `blocks client when suspicious threshold is exceeded`() {
        val service = SuspiciousRequestBlockService(redisTemplate, docsEnabled = false)
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.increment("security:probe:count:${AuthLogSanitizer.hash("127.0.0.1")}")).willReturn(5L)

        val blocked = service.recordSuspiciousRequest("127.0.0.1", "GET", "/swagger-ui/")

        assertTrue(blocked)
        then(valueOperations).should().set(
            "security:probe:block:${AuthLogSanitizer.hash("127.0.0.1")}",
            "1",
            Duration.ofMinutes(30)
        )
    }

    @Test
    fun `does not treat normal api path as suspicious`() {
        val service = SuspiciousRequestBlockService(redisTemplate, docsEnabled = false)

        val blocked = service.recordSuspiciousRequest("127.0.0.1", "GET", "/api/interview/categories")

        assertFalse(blocked)
        then(redisTemplate).shouldHaveNoInteractions()
    }

    @Test
    fun `does not treat docs path as suspicious when docs are enabled`() {
        val service = SuspiciousRequestBlockService(redisTemplate, docsEnabled = true)

        val blocked = service.recordSuspiciousRequest("127.0.0.1", "GET", "/swagger-ui/index.html")

        assertFalse(blocked)
        then(redisTemplate).shouldHaveNoInteractions()
    }
}
