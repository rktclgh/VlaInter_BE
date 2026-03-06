package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai.bedrock")
data class BedrockProperties(
    val enabled: Boolean = false,
    val modelId: String = "amazon.nova-lite-v1:0",
    val region: String = "ap-northeast-2"
)
