package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.GeminiProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.util.ArrayDeque
import java.util.function.Supplier

@Component
class GeminiApiClient(
    restTemplateBuilder: RestTemplateBuilder,
    private val geminiProperties: GeminiProperties,
    private val objectMapper: ObjectMapper,
    private val apiKeyContextHolder: GeminiApiKeyContextHolder
) : LlmProviderClient, EmbeddingProviderClient {
    override val provider: AiProvider = AiProvider.GEMINI
    private val chatRateLock = Any()
    private val chatRequestTimes = ArrayDeque<Long>()

    private val restTemplate: RestTemplate = restTemplateBuilder
        .requestFactory(Supplier {
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout((geminiProperties.connectTimeoutSeconds * 1000).toInt())
                setReadTimeout((geminiProperties.readTimeoutSeconds * 1000).toInt())
            }
        })
        .build()

    override fun isEnabled(): Boolean = resolveApiKey().isNotBlank()

    override fun generateJson(prompt: String, temperature: Double?): LlmGenerationResult {
        val apiKey = resolveApiKey()
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }
        waitForChatRateLimitSlot()

        val model = geminiProperties.chatModel.trim()
        val url = "${geminiProperties.baseUrl.trim().trimEnd('/')}/v1beta/models/$model:generateContent?key=$apiKey"

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.accept = listOf(MediaType.APPLICATION_JSON)

        val payload = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = prompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = temperature ?: geminiProperties.temperature,
                responseMimeType = "application/json"
            )
        )

        val response = post(url, HttpEntity(payload, headers), GeminiGenerateContentResponse::class.java)
        val body = response.body ?: error("Gemini 응답이 비어 있습니다.")
        val errorMessage = body.error?.get("message")?.asText()?.trim().orEmpty()
        if (errorMessage.isNotBlank()) {
            error("Gemini 오류: $errorMessage")
        }

        val text = body.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?.trim()

        if (text.isNullOrBlank()) {
            error("Gemini 응답 텍스트가 비어 있습니다.")
        }
        return LlmGenerationResult(
            model = model,
            modelVersion = "v1beta",
            text = text
        )
    }

    override fun embedText(text: String): EmbeddingGenerationResult {
        val apiKey = resolveApiKey()
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }

        val model = geminiProperties.embeddingModel.trim()
        val url = "${geminiProperties.baseUrl.trim().trimEnd('/')}/v1beta/models/$model:embedContent?key=$apiKey"

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.accept = listOf(MediaType.APPLICATION_JSON)

        val payload = GeminiEmbedContentRequest(
            model = "models/$model",
            content = GeminiContent(parts = listOf(GeminiPart(text = text))),
            outputDimensionality = geminiProperties.embeddingOutputDimensionality
        )

        val response = post(url, HttpEntity(payload, headers), GeminiEmbedContentResponse::class.java)
        val body = response.body ?: error("Gemini 임베딩 응답이 비어 있습니다.")
        val errorMessage = body.error?.get("message")?.asText()?.trim().orEmpty()
        if (errorMessage.isNotBlank()) {
            error("Gemini 임베딩 오류: $errorMessage")
        }

        val values = body.embedding?.values?.takeIf { it.isNotEmpty() }
            ?: error("Gemini 임베딩 벡터가 비어 있습니다.")

        return EmbeddingGenerationResult(
            model = model,
            modelVersion = "v1beta",
            values = values
        )
    }

    private fun <T> post(
        url: String,
        entity: HttpEntity<*>,
        responseType: Class<T>
    ): ResponseEntity<T> {
        return runCatching {
            restTemplate.postForEntity(url, entity, responseType)
        }.getOrElse { ex ->
            val httpEx = ex as? RestClientResponseException
            if (httpEx != null) {
                val details = runCatching {
                    objectMapper.readTree(httpEx.responseBodyAsString).toString()
                }.getOrElse { httpEx.responseBodyAsString }
                error("Gemini 호출 실패: HTTP ${httpEx.statusCode.value()} $details")
            }
            error("Gemini 호출 실패: ${ex.message}")
        }
    }

    private fun waitForChatRateLimitSlot() {
        val maxPerMinute = geminiProperties.chatMaxRequestsPerMinute.coerceAtLeast(1)
        val windowMillis = 60_000L
        while (true) {
            val waitMillis = synchronized(chatRateLock) {
                val now = System.currentTimeMillis()
                while (chatRequestTimes.isNotEmpty() && now - chatRequestTimes.first() >= windowMillis) {
                    chatRequestTimes.removeFirst()
                }
                if (chatRequestTimes.size < maxPerMinute) {
                    chatRequestTimes.addLast(now)
                    0L
                } else {
                    val earliest = chatRequestTimes.first()
                    (windowMillis - (now - earliest)).coerceAtLeast(100L)
                }
            }
            if (waitMillis <= 0L) return
            try {
                Thread.sleep(waitMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error("Gemini 호출 대기 중 인터럽트가 발생했습니다.")
            }
        }
    }

    private fun resolveApiKey(): String {
        return apiKeyContextHolder.currentApiKey()
            ?: geminiProperties.apiKey.trim()
    }
}

private data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    @JsonProperty("generationConfig")
    val generationConfig: GeminiGenerationConfig
)

private data class GeminiContent(
    val parts: List<GeminiPart>
)

private data class GeminiPart(
    val text: String
)

private data class GeminiGenerationConfig(
    val temperature: Double,
    @JsonProperty("responseMimeType")
    val responseMimeType: String
)

private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: JsonNode? = null
)

private data class GeminiEmbedContentRequest(
    val model: String,
    val content: GeminiContent,
    @JsonProperty("outputDimensionality")
    val outputDimensionality: Int? = null
)

private data class GeminiEmbedContentResponse(
    val embedding: GeminiEmbeddingPayload? = null,
    val error: JsonNode? = null
)

private data class GeminiEmbeddingPayload(
    val values: List<Double>? = null
)

private data class GeminiCandidate(
    val content: GeminiContentResponse? = null
)

private data class GeminiContentResponse(
    val parts: List<GeminiPart>? = null
)
