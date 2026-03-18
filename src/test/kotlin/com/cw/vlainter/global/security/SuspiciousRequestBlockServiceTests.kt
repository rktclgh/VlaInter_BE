package com.cw.vlainter.global.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class SuspiciousRequestBlockServiceTests {
    @Mock
    lateinit var redisTemplate: StringRedisTemplate

    @Mock
    lateinit var redisWindowCounterService: RedisWindowCounterService

    @Mock
    lateinit var valueOperations: ValueOperations<String, String>

    private fun service(docsEnabled: Boolean = false): SuspiciousRequestBlockService =
        SuspiciousRequestBlockService(redisTemplate, redisWindowCounterService, docsEnabled)

    @Test
    fun `marks env probe as suspicious and increments counter`() {
        given(redisWindowCounterService.incrementWithWindow("security:probe:count:${SensitiveValueSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(10)))
            .willReturn(1L)

        val blocked = service().recordSuspiciousRequest("127.0.0.1", "GET", "/.env")

        assertFalse(blocked)
        verify(redisWindowCounterService).incrementWithWindow(
            "security:probe:count:${SensitiveValueSanitizer.hash("127.0.0.1")}",
            Duration.ofMinutes(10)
        )
    }

    @Test
    fun `blocks client when suspicious threshold is exceeded`() {
        given(redisWindowCounterService.incrementWithWindow("security:probe:count:${SensitiveValueSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(10)))
            .willReturn(5L)
        given(redisTemplate.opsForValue()).willReturn(valueOperations)

        val blocked = service().recordSuspiciousRequest("127.0.0.1", "GET", "/swagger-ui/")

        assertTrue(blocked)
        verify(redisWindowCounterService).incrementWithWindow(
            "security:probe:count:${SensitiveValueSanitizer.hash("127.0.0.1")}",
            Duration.ofMinutes(10)
        )
        verify(valueOperations).set(
            "security:probe:block:${SensitiveValueSanitizer.hash("127.0.0.1")}",
            "1",
            Duration.ofMinutes(30)
        )
        given(redisTemplate.hasKey("security:probe:block:${SensitiveValueSanitizer.hash("127.0.0.1")}")).willReturn(true)
        assertTrue(service().isBlocked("127.0.0.1"))
    }

    @Test
    fun `does not treat normal api path as suspicious`() {
        val blocked = service().recordSuspiciousRequest("127.0.0.1", "GET", "/api/interview/categories")

        assertFalse(blocked)
        org.mockito.Mockito.verifyNoInteractions(redisWindowCounterService, redisTemplate)
    }

    @Test
    fun `does not treat docs path as suspicious when docs are enabled`() {
        val blocked = service(docsEnabled = true).recordSuspiciousRequest("127.0.0.1", "GET", "/swagger-ui/index.html")

        assertFalse(blocked)
        org.mockito.Mockito.verifyNoInteractions(redisWindowCounterService, redisTemplate)
    }

    @Test
    fun `isBlocked returns true when block key exists`() {
        doReturn(true).`when`(redisTemplate).hasKey("security:probe:block:${SensitiveValueSanitizer.hash("127.0.0.1")}")

        assertTrue(service().isBlocked("127.0.0.1"))
    }

    @Test
    fun `isBlocked returns false when block key does not exist`() {
        doReturn(false).`when`(redisTemplate).hasKey("security:probe:block:${SensitiveValueSanitizer.hash("127.0.0.1")}")

        assertFalse(service().isBlocked("127.0.0.1"))
    }

    @Test
    fun `blocked request audit log is sampled within window`() {
        val service = service()

        assertTrue(service.shouldLogBlockedRequest("127.0.0.1"))
        assertFalse(service.shouldLogBlockedRequest("127.0.0.1"))
    }

    @Test
    fun `unresolved client ip audit log is sampled within window`() {
        val service = service()

        assertTrue(service.shouldLogUnresolvedClientIp("127.0.0.1", "/.env"))
        assertFalse(service.shouldLogUnresolvedClientIp("127.0.0.1", "/.env"))
    }
}
