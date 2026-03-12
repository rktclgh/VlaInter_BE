package com.cw.vlainter.domain.support.service

import com.cw.vlainter.domain.support.dto.SupportReportResponse
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Service
class SupportReportService(
    private val userRepository: UserRepository,
    private val supportWebhookNotifier: SupportWebhookNotifier
) {
    companion object {
        private const val MAX_ATTACHMENT_SIZE_BYTES = 5L * 1024L * 1024L
        private val ALLOWED_ATTACHMENT_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        private val ALLOWED_CATEGORIES = setOf("BUG_REPORT", "MESSAGE")
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

        val normalizedCategory = category?.trim().orEmpty().ifBlank { "BUG_REPORT" }
        if (normalizedCategory.length > 50 || normalizedCategory !in ALLOWED_CATEGORIES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 문의 종류입니다.")
        }
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
        val sent = supportWebhookNotifier.send(
            SupportWebhookPayload(
                category = normalizedCategory,
                title = normalizedTitle,
                message = normalizedMessage,
                reporterId = reporter.id,
                reporterName = reporter.name,
                reporterEmail = reporter.email,
                currentPath = currentPath?.trim().orEmpty(),
                userAgent = userAgent?.trim().orEmpty(),
                screenshot = attachment.file,
                attachmentNotice = attachment.notice
            )
        )

        if (!sent) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "디스코드 전송에 실패했습니다. 잠시 후 다시 시도해 주세요.")
        }

        return SupportReportResponse(
            message = "운영자 디스코드로 전송했습니다.",
            recipientCount = 1
        )
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

    private data class AttachmentValidationResult(
        val file: MultipartFile?,
        val notice: String?
    )
}
