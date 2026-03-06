package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProperties
import org.springframework.stereotype.Component

@Component
class LlmProviderRouter(
    private val aiProperties: AiProperties,
    providers: List<LlmProviderClient>
) {
    private val providersByType: Map<com.cw.vlainter.global.config.properties.AiProvider, LlmProviderClient> =
        providers.associateBy { it.provider }

    fun generateJson(prompt: String, temperature: Double? = null): LlmGenerationResult {
        val targetProvider = providersByType[aiProperties.provider]
            ?: error("등록되지 않은 AI provider 입니다: ${aiProperties.provider}")
        check(targetProvider.isEnabled()) { "AI provider(${aiProperties.provider}) 설정이 비활성화되어 있습니다." }
        return targetProvider.generateJson(prompt, temperature)
    }
}
