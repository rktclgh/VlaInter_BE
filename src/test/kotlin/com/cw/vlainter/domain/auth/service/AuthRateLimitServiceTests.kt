package com.cw.vlainter.domain.auth.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import com.cw.vlainter.global.security.RedisWindowCounterService
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class AuthRateLimitServiceTests {

    @Mock
    private lateinit var redisWindowCounterService: RedisWindowCounterService

    private fun service(): AuthRateLimitService = AuthRateLimitService(redisWindowCounterService)

    @Test
    fun `login attempt under limit is allowed`() {
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(1))

        service().checkLoginAttempt("user@vlainter.com", "127.0.0.1")
    }

    @Test
    fun `login attempt skips ip limit when client ip is not reliable`() {
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(1))
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}:unreliable", Duration.ofMinutes(1))

        service().checkLoginAttempt("user@vlainter.com", "127.0.0.1", reliableClientIp = false)

        verify(redisWindowCounterService, never())
            .incrementWithWindow("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))
        verify(redisWindowCounterService)
            .incrementWithWindow("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}:unreliable", Duration.ofMinutes(1))
    }

    @Test
    fun `login attempt over email limit returns 429`() {
        doReturn(2L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))
        doReturn(9L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(1))

        val exception = assertThrows<ResponseStatusException> {
            service().checkLoginAttempt("user@vlainter.com", "127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `login attempt over ip limit returns 429`() {
        doReturn(21L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:login:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))

        val exception = assertThrows<ResponseStatusException> {
            service().checkLoginAttempt("user@vlainter.com", "127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `signup attempt under limit is allowed`() {
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(10))
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(10))

        service().checkSignupAttempt("user@vlainter.com", "127.0.0.1")
    }

    @Test
    fun `signup attempt skips ip limit when client ip is not reliable`() {
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(10))
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:ip:${AuthLogSanitizer.hash("127.0.0.1")}:unreliable", Duration.ofMinutes(10))

        service().checkSignupAttempt("user@vlainter.com", "127.0.0.1", reliableClientIp = false)

        verify(redisWindowCounterService, never())
            .incrementWithWindow("auth:signup:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(10))
        verify(redisWindowCounterService)
            .incrementWithWindow("auth:signup:ip:${AuthLogSanitizer.hash("127.0.0.1")}:unreliable", Duration.ofMinutes(10))
    }

    @Test
    fun `signup attempt over ip limit returns 429`() {
        doReturn(9L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(10))

        val exception = assertThrows<ResponseStatusException> {
            service().checkSignupAttempt("user@vlainter.com", "127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `signup attempt over email limit returns 429`() {
        doReturn(2L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(10))
        doReturn(4L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:signup:email:${AuthLogSanitizer.hash("user@vlainter.com")}", Duration.ofMinutes(10))

        val exception = assertThrows<ResponseStatusException> {
            service().checkSignupAttempt("user@vlainter.com", "127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `kakao login attempt at threshold is allowed`() {
        doReturn(20L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:kakao:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))

        service().checkKakaoLoginAttempt("127.0.0.1")
    }

    @Test
    fun `kakao login attempt skips ip limit when client ip is not reliable`() {
        doReturn(1L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:kakao:ip:${AuthLogSanitizer.hash("127.0.0.1")}:unreliable", Duration.ofMinutes(1))

        service().checkKakaoLoginAttempt("127.0.0.1", reliableClientIp = false)

        verify(redisWindowCounterService, never())
            .incrementWithWindow("auth:kakao:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))
        verify(redisWindowCounterService)
            .incrementWithWindow("auth:kakao:ip:${AuthLogSanitizer.hash("127.0.0.1")}:unreliable", Duration.ofMinutes(1))
    }

    @Test
    fun `kakao login attempt over ip limit returns 429`() {
        doReturn(21L).`when`(redisWindowCounterService)
            .incrementWithWindow("auth:kakao:ip:${AuthLogSanitizer.hash("127.0.0.1")}", Duration.ofMinutes(1))

        val exception = assertThrows<ResponseStatusException> {
            service().checkKakaoLoginAttempt("127.0.0.1")
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }
}
