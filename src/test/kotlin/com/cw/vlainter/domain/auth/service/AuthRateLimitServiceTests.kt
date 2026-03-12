package com.cw.vlainter.domain.auth.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class AuthRateLimitServiceTests {

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOperations: ValueOperations<String, String>

    private fun service(): AuthRateLimitService = AuthRateLimitService(redisTemplate)

    @Test
    fun `login attempt under limit is allowed and expiry is set for first hit`() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.increment("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}")).willReturn(1L)
        given(valueOperations.increment("auth:login:email:${AuthLogSanitizer.hash("user@vlainter.com")}")).willReturn(1L)

        service().checkLoginAttempt("user@vlainter.com", "127.0.0.1")

        then(redisTemplate).should().expire("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))
        then(redisTemplate).should().expire("auth:login:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(1))
    }

    @Test
    fun `login attempt over email limit returns 429`() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.increment("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}")).willReturn(2L)
        given(valueOperations.increment("auth:login:email:${AuthLogSanitizer.hash("user@vlainter.com")}")).willReturn(9L)

        val exception = assertThrows<ResponseStatusException> {
            service().checkLoginAttempt("user@vlainter.com", "127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `kakao login attempt over ip limit returns 429`() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations)
        given(valueOperations.increment("auth:kakao:ip:${AuthLogSanitizer.hash("127.0.0.1")}")).willReturn(21L)

        val exception = assertThrows<ResponseStatusException> {
            service().checkKakaoLoginAttempt("127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }
}
