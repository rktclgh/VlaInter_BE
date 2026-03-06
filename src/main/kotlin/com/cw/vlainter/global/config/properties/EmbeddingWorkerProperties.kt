package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai.embedding-worker")
data class EmbeddingWorkerProperties(
    val enabled: Boolean = true,
    val fixedDelayMs: Long = 5000,
    val batchSize: Int = 5
)
