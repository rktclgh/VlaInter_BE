package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.GeminiProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.util.ArrayDeque
import java.util.function.Supplier

@Component
class GeminiApiClient(
    restTemplateBuilder: RestTemplateBuilder,
    private val geminiProperties: GeminiProperties,
    private val objectMapper: ObjectMapper,
    private val apiKeyContextHolder: GeminiApiKeyContextHolder,
    private val aiRoutingContextHolder: AiRoutingContextHolder
) : LlmProviderClient, EmbeddingProviderClient {
    override val provider: AiProvider = AiProvider.GEMINI
    private val logger = LoggerFactory.getLogger(javaClass)
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

    override fun generateJson(
        prompt: String,
        temperature: Double?,
        maxOutputTokens: Int?
    ): LlmGenerationResult {
        val apiKey = resolveApiKey()
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }

        val models = buildList {
            add(geminiProperties.chatModel.trim())
            addAll(geminiProperties.fallbackChatModels.map { it.trim() })
        }
            .filter { it.isNotBlank() }
            .distinct()

        val startIndex = aiRoutingContextHolder.preferredGeminiModelIndex()
            .coerceIn(0, (models.size - 1).coerceAtLeast(0))
        var lastTransient: GeminiTransientException? = null
        for (index in startIndex..models.lastIndex) {
            val model = models[index]
            try {
                waitForChatRateLimitSlot()
                aiRoutingContextHolder.promoteGeminiModelIndex(index)
                logger.info(
                    "Gemini 채팅 모델 호출 시도 model={} index={} promptLength={} temperature={} maxOutputTokens={}",
                    model,
                    index,
                    prompt.length,
                    temperature ?: geminiProperties.temperature,
                    maxOutputTokens ?: geminiProperties.chatMaxOutputTokens
                )
                return generateJsonWithModel(
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    temperature = temperature,
                    maxOutputTokens = maxOutputTokens ?: geminiProperties.chatMaxOutputTokens
                )
            } catch (ex: GeminiTransientException) {
                lastTransient = ex
                if (index < models.lastIndex) {
                    aiRoutingContextHolder.promoteGeminiModelIndex(index + 1)
                    logger.warn(
                        "Gemini 채팅 모델 fallback 시도 primary={} fallback={} status={} reason={}",
                        model,
                        models[index + 1],
                        ex.statusCode,
                        ex.message
                    )
                }
            }
        }

        throw lastTransient ?: error("Gemini 채팅 모델이 구성되지 않았습니다.")
    }

    override fun embedText(text: String): EmbeddingGenerationResult {
        val apiKey = resolveApiKey()
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }

        val model = geminiProperties.embeddingModel.trim()
        val url = "${geminiProperties.baseUrl.trim().trimEnd('/')}/v1beta/models/$model:embedContent"
        val headers = geminiHeaders(apiKey)

        val payload = GeminiEmbedContentRequest(
            model = "models/$model",
            content = GeminiContentRequest(parts = listOf(GeminiRequestPart(text = text))),
            outputDimensionality = geminiProperties.embeddingOutputDimensionality
        )

        val response = post(
            url = url,
            entity = HttpEntity(payload, headers),
            responseType = GeminiEmbedContentResponse::class.java
        )
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
                val statusCode = httpEx.statusCode.value()
                if (statusCode == 429 || statusCode == 503) {
                    throw GeminiTransientException(
                        statusCode = statusCode,
                        message = "Gemini 호출 실패: HTTP $statusCode $details",
                        cause = httpEx
                    )
                }
                error("Gemini 호출 실패: HTTP $statusCode $details")
            }
            if (isTimeoutException(ex)) {
                throw GeminiTransientException(
                    statusCode = 503,
                    message = "Gemini 호출 실패: ${ex.message}",
                    cause = ex
                )
            }
            error("Gemini 호출 실패: ${ex.message}")
        }
    }

    private fun generateJsonWithModel(
        apiKey: String,
        model: String,
        prompt: String,
        temperature: Double?,
        maxOutputTokens: Int
    ): LlmGenerationResult {
        val url = "${geminiProperties.baseUrl.trim().trimEnd('/')}/v1beta/models/$model:generateContent"
        val headers = geminiHeaders(apiKey)

        val payload = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContentRequest(parts = listOf(GeminiRequestPart(text = prompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = temperature ?: geminiProperties.temperature,
                responseMimeType = "application/json",
                maxOutputTokens = maxOutputTokens
            )
        )

        val response = post(
            url = url,
            entity = HttpEntity(payload, headers),
            responseType = GeminiGenerateContentResponse::class.java
        )
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
        ).also {
            logger.info(
                "Gemini 채팅 모델 호출 성공 model={} responseLength={}",
                model,
                text.length
            )
        }
    }

    private fun isTimeoutException(ex: Throwable): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            if (current is java.net.SocketTimeoutException) return true
            if (current is ResourceAccessException && current.message?.contains("timed out", ignoreCase = true) == true) return true
            if (current.message?.contains("timed out", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
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

    private fun geminiHeaders(apiKey: String): HttpHeaders {
        return HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
            set("x-goog-api-key", apiKey)
        }
    }
}

private data class GeminiGenerateContentRequest(
    val contents: List<GeminiContentRequest>,
    @JsonProperty("generationConfig")
    val generationConfig: GeminiGenerationConfig
)

private data class GeminiContentRequest(
    val parts: List<GeminiRequestPart>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class GeminiRequestPart(
    val text: String? = null,
    @JsonProperty("file_data")
    val fileData: GeminiFileData? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class GeminiFileData(
    @JsonProperty("file_uri")
    val fileUri: String,
    @JsonProperty("mime_type")
    val mimeType: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class GeminiGenerationConfig(
    val temperature: Double,
    @JsonProperty("responseMimeType")
    val responseMimeType: String,
    @JsonProperty("maxOutputTokens")
    val maxOutputTokens: Int? = null
)

private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: JsonNode? = null
)

private data class GeminiEmbedContentRequest(
    val model: String,
    val content: GeminiContentRequest,
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
    val parts: List<GeminiResponsePart>? = null
)

private data class GeminiResponsePart(
    val text: String? = null
)
