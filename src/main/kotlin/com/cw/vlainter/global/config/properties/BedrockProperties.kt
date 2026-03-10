package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai.bedrock")
data class BedrockProperties(
    val enabled: Boolean = false,
    val modelId: String = "amazon.nova-micro-v1:0",
    val region: String = "ap-northeast-2",
    val temperature: Double = 0.2,
    val topP: Double = 0.9,
    val maxTokens: Int = 2048
)
