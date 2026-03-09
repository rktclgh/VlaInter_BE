package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai")
data class AiProperties(
    val provider: AiProvider = AiProvider.GEMINI,
    val fallbackToHeuristic: Boolean = true
)

enum class AiProvider {
    GEMINI,
    BEDROCK
}
