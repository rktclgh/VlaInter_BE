package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider

interface LlmProviderClient {
    val provider: AiProvider

    fun isEnabled(): Boolean

    fun generateJson(
        prompt: String,
        temperature: Double? = null,
        maxOutputTokens: Int? = null
    ): LlmGenerationResult
}

data class LlmGenerationResult(
    val model: String,
    val modelVersion: String? = null,
    val text: String
)
