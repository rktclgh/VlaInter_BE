package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.portone")
data class PortoneProperties(
    val baseUrl: String = "https://api.iamport.kr",
    val apiKey: String = "",
    val apiSecret: String = "",
    val customerCode: String = ""
)
