package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.BedrockProperties
import org.springframework.stereotype.Component

@Component
class BedrockLlmProviderClient(
    private val bedrockProperties: BedrockProperties
) : LlmProviderClient, EmbeddingProviderClient {
    override val provider: AiProvider = AiProvider.BEDROCK

    override fun isEnabled(): Boolean = bedrockProperties.enabled

    override fun generateJson(prompt: String, temperature: Double?): LlmGenerationResult {
        error(
            "BEDROCK provider는 아직 구현되지 않았습니다. " +
                "modelId=${bedrockProperties.modelId}, region=${bedrockProperties.region}"
        )
    }

    override fun embedText(text: String): EmbeddingGenerationResult {
        error(
            "BEDROCK 임베딩 provider는 아직 구현되지 않았습니다. " +
                "modelId=${bedrockProperties.modelId}, region=${bedrockProperties.region}"
        )
    }
}
