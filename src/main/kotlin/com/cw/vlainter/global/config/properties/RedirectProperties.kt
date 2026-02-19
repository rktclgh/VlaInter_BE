package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.redirect")
data class RedirectProperties(
    var allowedOrigins: List<String> = emptyList()
)
