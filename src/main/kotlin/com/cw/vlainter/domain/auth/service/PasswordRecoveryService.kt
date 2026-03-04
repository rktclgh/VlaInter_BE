package com.cw.vlainter.domain.auth.service

import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.mail.EmailTemplateService
import org.springframework.beans.factory.annotation.Value
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

@Service
class PasswordRecoveryService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val mailSender: JavaMailSender,
    private val emailTemplateService: EmailTemplateService,
    @Value("\${spring.mail.username:}")
    private val senderEmail: String
) {
    private val secureRandom = SecureRandom()
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
    private val tempPasswordChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

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

    private fun invalidIdentityException(): ResponseStatusException {
        return ResponseStatusException(HttpStatus.BAD_REQUEST, "입력한 이메일과 이름이 일치하는 계정을 찾을 수 없습니다.")
    }
}
