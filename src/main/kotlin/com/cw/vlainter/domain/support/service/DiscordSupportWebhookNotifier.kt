package com.cw.vlainter.domain.support.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime

interface SupportWebhookNotifier {
    fun send(payload: SupportWebhookPayload): Boolean
}

data class SupportWebhookPayload(
    val category: String,
    val title: String,
    val message: String,
    val reporterId: Long,
    val reporterName: String,
    val reporterEmail: String,
    val currentPath: String,
    val userAgent: String,
    val attachmentNotice: String?,
    val screenshot: MultipartFile?
)

@Component
class DiscordSupportWebhookNotifier(
    restTemplateBuilder: RestTemplateBuilder,
    private val objectMapper: ObjectMapper,
    @Value("\${app.support.discord.webhook-url:}")
    private val webhookUrl: String
) : SupportWebhookNotifier {
    companion object {
        private const val WEBHOOK_USERNAME = "VlaInter Support Bot"
        private const val MAX_BROWSER_LENGTH = 220
        private const val MAX_MESSAGE_LENGTH = 3500
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val restTemplate: RestTemplate = restTemplateBuilder
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(10))
        .build()

    override fun send(payload: SupportWebhookPayload): Boolean {
        if (webhookUrl.isBlank()) {
            logger.warn("support webhook skipped reason=missing_webhook_url category={}", payload.category)
            return false
        }

        return try {
            val requestBody = LinkedMultiValueMap<String, Any>()
            requestBody.add("payload_json", buildJsonPart(payload))
            buildFilePart(payload.screenshot)?.let { requestBody.add("files[0]", it) }
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            restTemplate.postForEntity(
                webhookUrl,
                HttpEntity(requestBody, headers),
                String::class.java
            )
            true
        } catch (ex: Exception) {
            logger.warn(
                "support webhook send failed category={} reporterId={} errorType={}",
                payload.category,
                payload.reporterId,
                ex::class.simpleName
            )
            false
        }
    }

    private fun buildJsonPart(payload: SupportWebhookPayload): HttpEntity<String> {
        val filename = payload.screenshot?.originalFilename?.takeIf { it.isNotBlank() } ?: "support-report-image"
        val summaryEmbed = linkedMapOf<String, Any>(
            "title" to buildEmbedTitle(payload),
            "description" to buildSummaryDescription(payload),
            "color" to resolveEmbedColor(payload),
            "fields" to listOf(
                mapOf("name" to "사용자", "value" to buildReporterField(payload), "inline" to true),
                mapOf("name" to "첨부", "value" to resolveAttachmentStatus(payload), "inline" to true),
                mapOf("name" to "현재 위치", "value" to formatCodeField(payload.currentPath.ifBlank { "-" }), "inline" to false),
                mapOf("name" to "브라우저", "value" to formatCodeField(shortenBrowser(payload.userAgent)), "inline" to false),
            ),
            "footer" to mapOf("text" to "VlaInter Support Report"),
            "timestamp" to OffsetDateTime.now().toString()
        )
        val embeds = mutableListOf<Map<String, Any>>(
            summaryEmbed,
            linkedMapOf(
                "title" to "제보 내용",
                "description" to formatMessageBody(payload.message),
                "color" to 0x1F2430
            )
        )
        if (payload.screenshot != null && payload.attachmentNotice == null) {
            embeds += linkedMapOf(
                "title" to "첨부 스크린샷",
                "color" to 0x64748B,
                "image" to mapOf("url" to "attachment://$filename")
            )
        }

        val json = objectMapper.writeValueAsString(
            mapOf(
                "username" to WEBHOOK_USERNAME,
                "content" to buildContentLine(payload),
                "embeds" to embeds
            )
        )
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.acceptCharset = listOf(StandardCharsets.UTF_8)
        return HttpEntity(json, headers)
    }

    private fun buildFilePart(file: MultipartFile?): HttpEntity<ByteArrayResource>? {
        if (file == null || file.isEmpty) return null
        val resource = object : ByteArrayResource(file.bytes) {
            override fun getFilename(): String = file.originalFilename?.takeIf { it.isNotBlank() } ?: "support-report-image"
        }
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(file.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE)
        return HttpEntity(resource, headers)
    }

    private fun buildEmbedTitle(payload: SupportWebhookPayload): String {
        return if (payload.category == "BUG_REPORT") {
            "🐞 버그 리포트"
        } else {
            "💬 운영자에게 한마디"
        }
    }

    private fun buildSummaryDescription(payload: SupportWebhookPayload): String {
        return buildString {
            appendLine("**제목**")
            append(payload.title.trim().take(120))
        }.trim()
    }

    private fun buildReporterField(payload: SupportWebhookPayload): String {
        return """
            **#${payload.reporterId} · ${payload.reporterName}**
            ${payload.reporterEmail}
        """.trimIndent()
    }

    private fun formatMessageBody(message: String): String {
        val normalizedMessage = message.trim().ifBlank { "-" }.take(MAX_MESSAGE_LENGTH)
        return normalizedMessage
            .lineSequence()
            .map { line -> if (line.isBlank()) "> " else "> $line" }
            .joinToString("\n")
    }

    private fun buildContentLine(payload: SupportWebhookPayload): String {
        return if (payload.category == "BUG_REPORT") {
            "새 버그 리포트가 접수되었습니다."
        } else {
            "새 운영자 메시지가 접수되었습니다."
        }
    }

    private fun resolveEmbedColor(payload: SupportWebhookPayload): Int {
        return if (payload.category == "BUG_REPORT") 0xE67E22 else 0x3B82F6
    }

    private fun resolveAttachmentStatus(payload: SupportWebhookPayload): String {
        return payload.attachmentNotice
            ?: if (payload.screenshot != null) "스크린샷 포함" else "첨부 없음"
    }

    private fun shortenBrowser(value: String): String {
        val normalized = value.ifBlank { "-" }.replace("\n", " ")
        return if (normalized.length <= MAX_BROWSER_LENGTH) normalized else normalized.take(MAX_BROWSER_LENGTH - 1) + "…"
    }

    private fun wrapCode(value: String): String = "```$value```"

    private fun formatCodeField(value: String, maxLength: Int = 1024): String {
        val fencedPrefix = "```"
        val fencedSuffix = "```"
        val availableContentLength = (maxLength - fencedPrefix.length - fencedSuffix.length).coerceAtLeast(1)
        return wrapCode(truncateByCodePoint(value.ifBlank { "-" }, availableContentLength))
    }

    private fun truncateByCodePoint(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        if (maxLength <= 1) return "…"
        val safeCodePointCount = (maxLength - 1).coerceAtLeast(0).coerceAtMost(value.codePointCount(0, value.length))
        val safeEndIndex = value.offsetByCodePoints(0, safeCodePointCount)
        return value.substring(0, safeEndIndex) + "…"
    }
}
