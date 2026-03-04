package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.mail.EmailTemplateService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Service
class PasswordRecoveryService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val mailSender: JavaMailSender,
    private val redisTemplate: StringRedisTemplate,
    private val emailTemplateService: EmailTemplateService,
    @Value("\${spring.mail.username:}")
    private val senderEmail: String
) {
    private val secureRandom = SecureRandom()
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
    private val tempPasswordChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private val issueCooldown = Duration.ofMinutes(1)
    private val dailyIssueLimit = 5L

    @Transactional
    fun sendTemporaryPassword(rawEmail: String, rawName: String) {
        val email = normalizeEmail(rawEmail)
        val name = normalizeName(rawName)
        validateInputs(email, name)

        val user = userRepository.findByEmail(email)
            .orElseThrow { invalidIdentityException() }
        if (user.name != name || user.status != UserStatus.ACTIVE) {
            throw invalidIdentityException()
        }
        enforceIssueRateLimit(email)

        val temporaryPassword = generateTemporaryPassword()
        user.password = passwordEncoder.encode(temporaryPassword)
        userRepository.save(user)

        try {
            sendTemporaryPasswordEmail(email, temporaryPassword)
        } catch (_: MailException) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "임시 비밀번호 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요."
            )
        }
    }

    private fun validateInputs(email: String, name: String) {
        if (email.isBlank() || !emailRegex.matches(email)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 이메일을 입력해 주세요.")
        }
        if (name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름을 입력해 주세요.")
        }
    }

    private fun generateTemporaryPassword(): String {
        while (true) {
            val candidate = buildString(12) {
                repeat(12) {
                    append(tempPasswordChars[secureRandom.nextInt(tempPasswordChars.length)])
                }
            }
            if (candidate.any { it.isLetter() } && candidate.any { it.isDigit() }) {
                return candidate
            }
        }
    }

    private fun sendTemporaryPasswordEmail(email: String, temporaryPassword: String) {
        val html = emailTemplateService.buildTemporaryPasswordEmail(temporaryPassword)
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, StandardCharsets.UTF_8.name())
        if (senderEmail.isNotBlank()) {
            helper.setFrom(senderEmail)
        }
        helper.setTo(email)
        helper.setSubject("[VlaInter] 임시 비밀번호")
        helper.setText(html, true)
        mailSender.send(message)
    }

    private fun normalizeEmail(rawEmail: String): String = rawEmail.trim().lowercase()

    private fun normalizeName(rawName: String): String = rawName.trim()

    private fun enforceIssueRateLimit(email: String) {
        val valueOps = redisTemplate.opsForValue()
        val cooldownApplied = valueOps.setIfAbsent(temporaryPasswordCooldownKey(email), "1", issueCooldown) ?: false
        if (!cooldownApplied) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "임시 비밀번호는 1분에 한 번만 요청할 수 있습니다.")
        }

        val dailyKey = temporaryPasswordDailyCountKey(email)
        val issuedCount = valueOps.increment(dailyKey) ?: 1L
        if (issuedCount == 1L) {
            redisTemplate.expire(dailyKey, durationUntilNextUtcDay())
        }
        if (issuedCount > dailyIssueLimit) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘의 임시 비밀번호 요청 횟수를 초과했습니다.")
        }
    }

    private fun durationUntilNextUtcDay(): Duration {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)
        val duration = Duration.between(now, nextDay)
        return if (duration.isNegative || duration.isZero) Duration.ofDays(1) else duration
    }

    private fun temporaryPasswordCooldownKey(email: String): String = "auth:password-recovery:cooldown:$email"

    private fun temporaryPasswordDailyCountKey(email: String): String {
        val date = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate()
        return "auth:password-recovery:daily:$date:$email"
    }

    private fun invalidIdentityException(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.BAD_REQUEST, "입력한 이메일과 이름이 일치하는 계정을 찾을 수 없습니다.")
    }
}
