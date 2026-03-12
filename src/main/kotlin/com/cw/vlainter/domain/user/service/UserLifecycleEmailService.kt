package com.cw.vlainter.domain.user.service

import com.cw.vlainter.global.mail.EmailTemplateService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

@Service
class UserLifecycleEmailService(
    private val mailSender: JavaMailSender,
    private val emailTemplateService: EmailTemplateService,
    @Value("\${spring.mail.username:}")
    private val senderEmail: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendWelcomeEmail(email: String, userName: String, signupChannel: String) {
        sendBestEffort(
            recipient = email,
            subject = "[VlaInter] 가입해주셔서 감사합니다",
            htmlProvider = { emailTemplateService.buildWelcomeEmail(userName, signupChannel) },
            event = "welcome"
        )
    }

    fun sendAccountDeletionEmail(email: String, userName: String) {
        sendBestEffort(
            recipient = email,
            subject = "[VlaInter] 회원 탈퇴가 완료되었습니다",
            htmlProvider = { emailTemplateService.buildAccountDeletionEmail(userName) },
            event = "account-deletion"
        )
    }

    private fun sendBestEffort(recipient: String, subject: String, htmlProvider: () -> String, event: String) {
        runCatching {
            val html = htmlProvider()
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, StandardCharsets.UTF_8.name())
            if (senderEmail.isNotBlank()) {
                helper.setFrom(senderEmail)
            }
            helper.setTo(recipient)
            helper.setSubject(subject)
            helper.setText(html, true)
            helper.addInline(
                emailTemplateService.logoContentId(),
                emailTemplateService.logoResource(),
                "image/png"
            )
            mailSender.send(message)
        }.onFailure { ex ->
            logger.warn("lifecycle email send failed event={} reason={}", event, ex::class.simpleName, ex)
        }
    }
}
