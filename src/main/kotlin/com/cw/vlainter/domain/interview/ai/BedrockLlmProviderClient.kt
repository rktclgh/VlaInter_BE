package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.BedrockProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

@Component
class BedrockLlmProviderClient(
    private val bedrockProperties: BedrockProperties,
    private val objectMapper: ObjectMapper
) : LlmProviderClient, EmbeddingProviderClient {
    override val provider: AiProvider = AiProvider.BEDROCK
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client: BedrockRuntimeClient by lazy {
        BedrockRuntimeClient.builder()
            .region(Region.of(bedrockProperties.region.trim()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build()
    }

    override fun isEnabled(): Boolean = bedrockProperties.enabled

    override fun generateJson(prompt: String, temperature: Double?): LlmGenerationResult {
        val modelId = bedrockProperties.modelId.trim()
        require(modelId.isNotBlank()) { "BEDROCK modelId is missing." }
        logger.info(
            "Bedrock 호출 시도 modelId={} region={} promptLength={} temperature={}",
            modelId,
            bedrockProperties.region,
            prompt.length,
            temperature ?: bedrockProperties.temperature
        )

        val payload = mapOf(
            "schemaVersion" to "messages-v1",
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            ),
            "inferenceConfig" to mapOf(
                "maxTokens" to bedrockProperties.maxTokens,
                "temperature" to (temperature ?: bedrockProperties.temperature),
                "topP" to bedrockProperties.topP
            )
        )

        val body = runCatching {
            val response = client.invokeModel(
                InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                    .build()
            )
            response.body().asUtf8String()
        }.getOrElse { ex ->
            throw translateException(ex)
        }

        val node = objectMapper.readTree(body)
        val text = node.path("output")
            .path("message")
            .path("content")
            .firstOrNull()
            ?.path("text")
            ?.asText()
            ?.trim()
            .orEmpty()

        if (text.isBlank()) {
            logger.warn("Bedrock 응답 텍스트가 비어 있습니다. body={}", body.take(500))
            error("Bedrock 응답 텍스트가 비어 있습니다.")
        }

        return LlmGenerationResult(
            model = modelId,
            modelVersion = "bedrock",
            text = text
        ).also {
            logger.info(
                "Bedrock 호출 성공 modelId={} responseLength={}",
                modelId,
                text.length
            )
        }
    }

    override fun embedText(text: String): EmbeddingGenerationResult {
        error(
            "BEDROCK 임베딩 provider는 아직 구현되지 않았습니다. " +
                "modelId=${bedrockProperties.modelId}, region=${bedrockProperties.region}"
        )
    }

    private fun translateException(ex: Throwable): RuntimeException {
        val awsEx = ex as? AwsServiceException
        if (awsEx != null) {
            val statusCode = awsEx.statusCode()
            val message = "Bedrock 호출 실패: HTTP $statusCode ${awsEx.awsErrorDetails()?.errorMessage() ?: awsEx.message}"
            if (statusCode == 429 || statusCode == 503) {
                return AiProviderTransientException(
                    statusCode = statusCode,
                    message = message,
                    cause = awsEx,
                    provider = AiProvider.BEDROCK
                )
            }
            if (statusCode == 401 || statusCode == 403) {
                return AiProviderAuthorizationException(
                    statusCode = statusCode,
                    message = message,
                    cause = awsEx,
                    provider = AiProvider.BEDROCK
                )
            }
            return IllegalStateException(message, awsEx)
        }

        val sdkEx = ex as? SdkClientException
        if (sdkEx != null && sdkEx.message?.contains("timed out", ignoreCase = true) == true) {
            return AiProviderTransientException(
                statusCode = 503,
                message = "Bedrock 호출 실패: ${sdkEx.message}",
                cause = sdkEx,
                provider = AiProvider.BEDROCK
            )
        }

        if (ex.message?.contains("timed out", ignoreCase = true) == true) {
            return AiProviderTransientException(
                statusCode = 503,
                message = "Bedrock 호출 실패: ${ex.message}",
                cause = ex,
                provider = AiProvider.BEDROCK
            )
        }

        return IllegalStateException("Bedrock 호출 실패: ${ex.message}", ex)
    }
}
