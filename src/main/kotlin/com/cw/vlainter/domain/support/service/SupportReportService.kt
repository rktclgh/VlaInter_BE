package com.cw.vlainter.domain.support.service

import com.cw.vlainter.domain.support.dto.SupportReportResponse
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.mail.MessagingException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture

@Service
class SupportReportService(
    private val userRepository: UserRepository,
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username:}")
    private val senderEmail: String
) {
    companion object {
        private const val MAX_ATTACHMENT_SIZE_BYTES = 5L * 1024L * 1024L
        private val ALLOWED_ATTACHMENT_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendReport(
        principal: AuthPrincipal,
        category: String?,
        title: String?,
        message: String?,
        currentPath: String?,
        userAgent: String?,
        screenshot: MultipartFile?
    ): SupportReportResponse {
        val reporter = userRepository.findById(principal.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 정보를 찾을 수 없습니다.") }
        val recipients = userRepository.findReportRecipients()
            .filter { it.role == UserRole.ADMIN && it.email.isNotBlank() }
            .map { it.email.trim() }
            .distinct()

        if (recipients.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리자 수신 이메일이 설정되지 않았습니다.")
        }

        val normalizedCategory = category?.trim().orEmpty().ifBlank { "BUG_REPORT" }
        val normalizedTitle = title?.trim().orEmpty()
        val normalizedMessage = message?.trim().orEmpty()
        if (normalizedTitle.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제목을 입력해 주세요.")
        }
        if (normalizedMessage.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "내용을 입력해 주세요.")
        }
        if (normalizedTitle.length > 120) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제목은 120자 이하로 입력해 주세요.")
        }
        if (normalizedMessage.length > 5000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "내용은 5000자 이하로 입력해 주세요.")
        }

        val attachment = validateAttachment(screenshot)
        val sentCount = recipients
            .map { recipient ->
                CompletableFuture.supplyAsync {
                    sendToRecipient(
                        recipient = recipient,
                        normalizedCategory = normalizedCategory,
                        normalizedTitle = normalizedTitle,
                        normalizedMessage = normalizedMessage,
                        reporterId = reporter.id,
                        reporterName = reporter.name,
                        reporterEmail = reporter.email,
                        currentPath = currentPath?.trim().orEmpty(),
                        userAgent = userAgent?.trim().orEmpty(),
                        screenshot = attachment.file,
                        attachmentNotice = attachment.notice
                    )
                }
            }
            .sumOf { it.join() }

        if (sentCount == 0) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.")
        }

        return SupportReportResponse(
            message = "운영자에게 메일을 전송했습니다.",
            recipientCount = sentCount
        )
    }

    private fun buildHtml(
        category: String,
        title: String,
        message: String,
        reporterId: Long,
        reporterName: String,
        reporterEmail: String,
        currentPath: String,
        userAgent: String,
        attachmentNotice: String?
    ): String {
        val escapedTitle = escapeHtml(title)
        val escapedMessage = escapeHtml(message).replace("\n", "<br/>")
        val escapedPath = escapeHtml(currentPath.ifBlank { "-" })
        val escapedAgent = escapeHtml(userAgent.ifBlank { "-" })
        val escapedName = escapeHtml(reporterName)
        val escapedEmail = escapeHtml(reporterEmail)
        val escapedCategory = escapeHtml(category)
        val escapedSentAt = escapeHtml(OffsetDateTime.now().toString())
        val escapedAttachmentNotice = attachmentNotice?.takeIf { it.isNotBlank() }?.let {
            """<p style="margin-top:12px;color:#c05621;font-size:13px;">${escapeHtml(it)}</p>"""
        }.orEmpty()

        return """
            <div style="font-family:Arial,sans-serif;line-height:1.6;color:#171b24;">
              <h2 style="margin-bottom:8px;">사용자 신고 메일</h2>
              <p style="margin:0 0 16px;color:#5e6472;">VlaInter 서비스 내 Report 모달에서 전송된 메일입니다.</p>
              <table style="border-collapse:collapse;width:100%;max-width:720px;">
                <tr><td style="padding:6px 0;font-weight:700;width:120px;">분류</td><td style="padding:6px 0;">$escapedCategory</td></tr>
                <tr><td style="padding:6px 0;font-weight:700;">제목</td><td style="padding:6px 0;">$escapedTitle</td></tr>
                <tr><td style="padding:6px 0;font-weight:700;">사용자</td><td style="padding:6px 0;">#$reporterId / $escapedName / $escapedEmail</td></tr>
                <tr><td style="padding:6px 0;font-weight:700;">경로</td><td style="padding:6px 0;">$escapedPath</td></tr>
                <tr><td style="padding:6px 0;font-weight:700;">브라우저</td><td style="padding:6px 0;">$escapedAgent</td></tr>
                <tr><td style="padding:6px 0;font-weight:700;">전송시각</td><td style="padding:6px 0;">$escapedSentAt</td></tr>
              </table>
              <div style="margin-top:18px;padding:16px;border:1px solid #dfe3eb;border-radius:12px;background:#f8fafc;">
                $escapedMessage
              </div>
              $escapedAttachmentNotice
            </div>
        """.trimIndent()
    }

    private fun sendToRecipient(
        recipient: String,
        normalizedCategory: String,
        normalizedTitle: String,
        normalizedMessage: String,
        reporterId: Long,
        reporterName: String,
        reporterEmail: String,
        currentPath: String,
        userAgent: String,
        screenshot: MultipartFile?,
        attachmentNotice: String?
    ): Int {
        return try {
            val mail = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(mail, true, StandardCharsets.UTF_8.name())
            if (senderEmail.isNotBlank()) {
                helper.setFrom(senderEmail)
            }
            helper.setTo(recipient)
            helper.setReplyTo(reporterEmail)
            helper.setSubject("[VlaInter][$normalizedCategory] $normalizedTitle")
            helper.setText(
                buildHtml(
                    category = normalizedCategory,
                    title = normalizedTitle,
                    message = normalizedMessage,
                    reporterId = reporterId,
                    reporterName = reporterName,
                    reporterEmail = reporterEmail,
                    currentPath = currentPath,
                    userAgent = userAgent,
                    attachmentNotice = attachmentNotice
                ),
                true
            )
            if (screenshot != null && !screenshot.isEmpty) {
                helper.addAttachment(
                    screenshot.originalFilename?.takeIf { it.isNotBlank() } ?: "report-image",
                    screenshot
                )
            }
            mailSender.send(mail)
            1
        } catch (ex: MailException) {
            logger.warn(
                "support report mail failed recipient={} reporterId={} errorType={}",
                maskEmail(recipient),
                reporterId,
                ex::class.simpleName
            )
            0
        } catch (ex: MessagingException) {
            logger.warn(
                "support report message build failed recipient={} reporterId={} errorType={}",
                maskEmail(recipient),
                reporterId,
                ex::class.simpleName
            )
            0
        }
    }

    private fun validateAttachment(screenshot: MultipartFile?): AttachmentValidationResult {
        if (screenshot == null || screenshot.isEmpty) {
            return AttachmentValidationResult(null, null)
        }
        val contentType = screenshot.contentType?.lowercase().orEmpty()
        if (contentType !in ALLOWED_ATTACHMENT_TYPES) {
            logger.warn("support report attachment skipped reason=unsupported_type contentType={}", contentType.ifBlank { "unknown" })
            return AttachmentValidationResult(null, "첨부 파일은 PNG/JPEG/WEBP 이미지만 전송됩니다.")
        }
        if (screenshot.size > MAX_ATTACHMENT_SIZE_BYTES) {
            logger.warn("support report attachment skipped reason=too_large size={}", screenshot.size)
            return AttachmentValidationResult(null, "첨부 이미지는 5MB 이하만 전송됩니다.")
        }
        return AttachmentValidationResult(screenshot, null)
    }

    private fun maskEmail(value: String): String {
        val parts = value.trim().split("@")
        if (parts.size != 2) return "***"
        val local = parts[0]
        val domain = parts[1]
        val maskedLocal = when {
            local.length <= 1 -> "*"
            local.length == 2 -> "${local.first()}*"
            else -> "${local.first()}***${local.last()}"
        }
        return "$maskedLocal@$domain"
    }

    private data class AttachmentValidationResult(
        val file: MultipartFile?,
        val notice: String?
    )

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
