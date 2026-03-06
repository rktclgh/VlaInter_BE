package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProperties
import org.springframework.stereotype.Component

@Component
class EmbeddingProviderRouter(
    private val aiProperties: AiProperties,
    providers: List<EmbeddingProviderClient>
) {
    private val providersByType = providers.associateBy { it.provider }

    fun embedText(text: String): EmbeddingGenerationResult {
        val targetProvider = providersByType[aiProperties.provider]
            ?: error("등록되지 않은 임베딩 provider 입니다: ${aiProperties.provider}")
        check(targetProvider.isEnabled()) { "임베딩 provider(${aiProperties.provider}) 설정이 비활성화되어 있습니다." }
        return targetProvider.embedText(text)
    }
}
