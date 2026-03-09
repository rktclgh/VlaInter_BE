package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider

interface EmbeddingProviderClient {
    val provider: AiProvider

    fun isEnabled(): Boolean

    fun embedText(text: String): EmbeddingGenerationResult
}

data class EmbeddingGenerationResult(
    val model: String,
    val modelVersion: String? = null,
    val values: List<Double>
)
