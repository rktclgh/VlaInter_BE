package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.GeminiProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Component
class GeminiApiClient(
    restTemplateBuilder: RestTemplateBuilder,
    private val geminiProperties: GeminiProperties,
    private val objectMapper: ObjectMapper
) : LlmProviderClient {
    override val provider: AiProvider = AiProvider.GEMINI

    private val restTemplate: RestTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(geminiProperties.connectTimeoutSeconds))
        .setReadTimeout(Duration.ofSeconds(geminiProperties.readTimeoutSeconds))
        .build()

    override fun isEnabled(): Boolean = geminiProperties.apiKey.isNotBlank()

    override fun generateJson(prompt: String, temperature: Double?): LlmGenerationResult {
        val apiKey = geminiProperties.apiKey.trim()
        require(apiKey.isNotBlank()) { "Gemini API key is missing." }

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

        val response = exchange(url, HttpMethod.POST, HttpEntity(payload, headers), GeminiGenerateContentResponse::class.java)
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

    private fun <T> exchange(
        url: String,
        method: HttpMethod,
        entity: HttpEntity<*>,
        responseType: Class<T>
    ): ResponseEntity<T> {
        return runCatching {
            restTemplate.exchange(url, method, entity, responseType)
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

private data class GeminiCandidate(
    val content: GeminiContentResponse? = null
)

private data class GeminiContentResponse(
    val parts: List<GeminiPart>? = null
)
