package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProperties
import com.cw.vlainter.global.config.properties.AiProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LlmProviderRouter(
    private val aiProperties: AiProperties,
    private val aiRoutingContextHolder: AiRoutingContextHolder,
    providers: List<LlmProviderClient>
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val providersByType: Map<AiProvider, LlmProviderClient> =
        providers.associateBy { it.provider }

    fun generateJson(
        prompt: String,
        temperature: Double? = null,
        maxOutputTokens: Int? = null
    ): LlmGenerationResult {
        val targetProvider = providersByType[aiProperties.provider]
            ?: error("등록되지 않은 AI provider 입니다: ${aiProperties.provider}")
        check(targetProvider.isEnabled()) { "AI provider(${aiProperties.provider}) 설정이 비활성화되어 있습니다." }
        val bedrockProvider = providersByType[AiProvider.BEDROCK]
        logger.info(
            "AI provider 라우팅 시작 primary={} geminiExhausted={} bedrockEnabled={} promptLength={} temperature={} maxOutputTokens={}",
            targetProvider.provider,
            aiRoutingContextHolder.isGeminiExhausted(),
            bedrockProvider?.isEnabled() == true,
            prompt.length,
            temperature,
            maxOutputTokens
        )
        if (targetProvider.provider == AiProvider.GEMINI &&
            aiRoutingContextHolder.isGeminiExhausted() &&
            bedrockProvider != null &&
            bedrockProvider.isEnabled()
        ) {
            logger.warn("AI provider direct fallback 실행: gemini exhausted -> BEDROCK")
            aiRoutingContextHolder.markUsed(bedrockProvider.provider)
            return bedrockProvider.generateJson(prompt, temperature, maxOutputTokens)
        }

        aiRoutingContextHolder.markUsed(targetProvider.provider)
        return try {
            targetProvider.generateJson(prompt, temperature, maxOutputTokens)
        } catch (ex: GeminiTransientException) {
            val fallbackProvider = bedrockProvider
            if (targetProvider.provider != AiProvider.GEMINI || fallbackProvider == null || !fallbackProvider.isEnabled()) {
                throw ex
            }
            aiRoutingContextHolder.markGeminiExhausted()
            logger.warn(
                "AI provider fallback 실행: primary={} -> fallback={} status={} reason={}",
                targetProvider.provider,
                fallbackProvider.provider,
                ex.statusCode,
                ex.message
            )
            aiRoutingContextHolder.markUsed(fallbackProvider.provider)
            fallbackProvider.generateJson(prompt, temperature, maxOutputTokens)
        } catch (ex: AiProviderAuthorizationException) {
            logger.error(
                "AI provider 권한 오류 provider={} status={} reason={}",
                ex.provider,
                ex.statusCode,
                ex.message
            )
            throw ex
        }
    }
}
