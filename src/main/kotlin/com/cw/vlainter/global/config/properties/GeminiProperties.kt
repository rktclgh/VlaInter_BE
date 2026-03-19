package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai.gemini")
data class GeminiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val chatModel: String = "gemini-3.1-flash-lite-preview",
    val fallbackChatModels: List<String> = listOf("gemini-2.5-flash-lite"),
    val embeddingModel: String = "gemini-embedding-001",
    val chatMaxRequestsPerMinute: Int = 30,
    val embeddingOutputDimensionality: Int = 768,
    val temperature: Double = 0.2,
    val chatMaxOutputTokens: Int = 8192,
    val connectTimeoutSeconds: Long = 5,
    val readTimeoutSeconds: Long = 45
)
