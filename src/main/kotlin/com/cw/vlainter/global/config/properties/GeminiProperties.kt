package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai.gemini")
data class GeminiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val chatModel: String = "gemini-2.5-flash",
    val embeddingModel: String = "gemini-embedding-001",
    val temperature: Double = 0.2,
    val connectTimeoutSeconds: Long = 3,
    val readTimeoutSeconds: Long = 20
)
