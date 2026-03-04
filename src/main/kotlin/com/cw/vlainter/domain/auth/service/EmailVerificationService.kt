package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.global.config.properties.EmailVerificationProperties
import com.cw.vlainter.global.mail.EmailTemplateService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.mail.javamail.MimeMessageHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration

@Service
class EmailVerificationService(
    private val mailSender: JavaMailSender,
    private val redisTemplate: StringRedisTemplate,
    private val emailTemplateService: EmailTemplateService,
    private val emailVerificationProperties: EmailVerificationProperties,
    @Value("\${spring.mail.username:}")
    private val senderEmail: String
) {
    private val secureRandom = SecureRandom()
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
    private val verifyAndConsumeScript = DefaultRedisScript<Long>().apply {
        setScriptText(
            """
            local verificationKey = KEYS[1]
            local cooldownKey = KEYS[2]
            local attemptsKey = KEYS[3]
            local expectedHash = ARGV[1]
            local storedHash = redis.call('GET', verificationKey)

            if (not storedHash) then
                return 0
            end

            if (storedHash ~= expectedHash) then
                return -1
            end

            redis.call('DEL', verificationKey)
            redis.call('DEL', cooldownKey)
            redis.call('DEL', attemptsKey)
            return 1
            """.trimIndent()
        )
        resultType = Long::class.java
    }

    fun sendVerificationCode(rawEmail: String): SendVerificationResult {
        val email = normalizeEmail(rawEmail)
        validateEmail(email)
        checkResendCooldown(email)

        val code = generateVerificationCode()
        val verificationKey = verificationCodeKey(email)
        val cooldownKey = resendCooldownKey(email)
        val valueOps = redisTemplate.opsForValue()

        valueOps.set(
            verificationKey,
            hash(code),
            Duration.ofSeconds(emailVerificationProperties.codeExpSeconds)
        )
        valueOps.set(
            cooldownKey,
            "1",
            Duration.ofSeconds(emailVerificationProperties.resendCooldownSeconds)
        )

        try {
            sendEmail(email, code)
        } catch (_: MailException) {
            redisTemplate.delete(verificationKey)
            redisTemplate.delete(cooldownKey)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "이메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요."
            )
        }

        return SendVerificationResult(expiresInSeconds = emailVerificationProperties.codeExpSeconds)
    }

    fun verifyCode(rawEmail: String, rawCode: String): VerifyCodeResult {
        val email = normalizeEmail(rawEmail)
        validateEmail(email)
        val code = normalizeCode(rawCode)
        validateCode(code)

        ensureAttemptsNotExceeded(email)

        val verificationKey = verificationCodeKey(email)
        val cooldownKey = resendCooldownKey(email)
        val attemptsKey = verifyAttemptsKey(email)
        val result = redisTemplate.execute(
            verifyAndConsumeScript,
            listOf(verificationKey, cooldownKey, attemptsKey),
            hash(code)
        )

        if (result == VERIFY_SUCCESS) {
            return VerifyCodeResult(verified = true)
        }

        val failedAttempts = increaseFailedAttempts(verificationKey, attemptsKey)
        if (failedAttempts >= emailVerificationProperties.maxVerifyAttempts) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "인증 시도 횟수를 초과했습니다. 인증 코드를 다시 요청해 주세요."
            )
        }
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 인증 코드입니다.")
    }

    private fun sendEmail(email: String, code: String) {
        val html = emailTemplateService.buildVerificationCodeEmail(code, emailVerificationProperties.codeExpSeconds)
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, StandardCharsets.UTF_8.name())
        if (senderEmail.isNotBlank()) {
            helper.setFrom(senderEmail)
        }
        helper.setTo(email)
        helper.setSubject("[VlaInter] 이메일 인증 코드")
        helper.setText(html, true)

        mailSender.send(message)
    }

    private fun generateVerificationCode(): String {
        return buildString(emailVerificationProperties.codeLength) {
            repeat(emailVerificationProperties.codeLength) {
                append(secureRandom.nextInt(10))
            }
        }
    }

    private fun checkResendCooldown(email: String) {
        val key = resendCooldownKey(email)
        if (redisTemplate.hasKey(key) == true) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "인증 코드 요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."
            )
        }
    }

    private fun ensureAttemptsNotExceeded(email: String) {
        val attempts = redisTemplate.opsForValue().get(verifyAttemptsKey(email))
            ?.toLongOrNull()
            ?: 0L
        if (attempts >= emailVerificationProperties.maxVerifyAttempts) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "인증 시도 횟수를 초과했습니다. 인증 코드를 다시 요청해 주세요."
            )
        }
    }

    private fun increaseFailedAttempts(verificationKey: String, attemptsKey: String): Long {
        val attempts = redisTemplate.opsForValue().increment(attemptsKey) ?: 1L
        val codeTtl = redisTemplate.getExpire(verificationKey)
        val attemptsTtl = if (codeTtl > 0L) {
            codeTtl
        } else {
            emailVerificationProperties.codeExpSeconds
        }
        redisTemplate.expire(attemptsKey, Duration.ofSeconds(attemptsTtl))
        return attempts
    }

    private fun validateEmail(email: String) {
        if (email.isBlank() || !emailRegex.matches(email)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 이메일 형식이 아닙니다.")
        }
    }

    private fun validateCode(code: String) {
        val isValid = code.length == emailVerificationProperties.codeLength && code.all { it.isDigit() }
        if (!isValid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "인증 코드 형식이 올바르지 않습니다.")
        }
    }

    private fun normalizeEmail(rawEmail: String): String = rawEmail.trim().lowercase()

    private fun normalizeCode(rawCode: String): String = rawCode.trim()

    private fun verificationCodeKey(email: String): String = "auth:email-verification:code:$email"

    private fun resendCooldownKey(email: String): String = "auth:email-verification:cooldown:$email"

    private fun verifyAttemptsKey(email: String): String = "auth:email-verification:attempts:$email"

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val VERIFY_SUCCESS = 1L
    }
}

data class SendVerificationResult(
    val expiresInSeconds: Long
)

data class VerifyCodeResult(
    val verified: Boolean
)
