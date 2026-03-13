package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.global.security.RedisWindowCounterService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

@Service
class AuthRateLimitService(
    private val redisWindowCounterService: RedisWindowCounterService
) {
    fun checkLoginAttempt(email: String, clientIp: String, reliableClientIp: Boolean = true) {
        enforceIpLimitIfReliable(
            reliableClientIp = reliableClientIp,
            key = "auth:login:ip:${AuthLogSanitizer.hash(clientIp)}",
            limit = 20,
            unreliableLimit = 120,
            window = Duration.ofMinutes(1),
            message = "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
        )
        enforceLimit(
            key = "auth:login:email:${AuthLogSanitizer.hash(email)}",
            limit = 8,
            window = Duration.ofMinutes(1),
            message = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."
        )
    }

    fun checkSignupAttempt(email: String, clientIp: String, reliableClientIp: Boolean = true) {
        enforceIpLimitIfReliable(
            reliableClientIp = reliableClientIp,
            key = "auth:signup:ip:${AuthLogSanitizer.hash(clientIp)}",
            limit = 8,
            unreliableLimit = 40,
            window = Duration.ofMinutes(10),
            message = "회원가입 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
        )
        enforceLimit(
            key = "auth:signup:email:${AuthLogSanitizer.hash(email)}",
            limit = 3,
            window = Duration.ofMinutes(10),
            message = "같은 이메일로 회원가입을 너무 자주 시도하고 있습니다. 잠시 후 다시 시도해 주세요."
        )
    }

    fun checkKakaoLoginAttempt(clientIp: String, reliableClientIp: Boolean = true) {
        enforceIpLimitIfReliable(
            reliableClientIp = reliableClientIp,
            key = "auth:kakao:ip:${AuthLogSanitizer.hash(clientIp)}",
            limit = 20,
            unreliableLimit = 60,
            window = Duration.ofMinutes(1),
            message = "카카오 로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
        )
    }

    private fun enforceLimit(key: String, limit: Long, window: Duration, message: String) {
        val count = redisWindowCounterService.incrementWithWindow(key, window)
        if (count > limit) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message)
        }
    }

    private fun enforceIpLimitIfReliable(
        reliableClientIp: Boolean,
        key: String,
        limit: Long,
        unreliableLimit: Long,
        window: Duration,
        message: String
    ) {
        if (reliableClientIp) {
            enforceLimit(key, limit, window, message)
            return
        }

        enforceLimit("$key:unreliable", unreliableLimit, window, message)
    }
}
