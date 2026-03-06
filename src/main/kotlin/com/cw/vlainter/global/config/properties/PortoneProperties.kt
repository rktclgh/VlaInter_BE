package com.cw.vlainter.global.config.properties

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.portone")
data class PortoneProperties(
    @field:NotBlank
    val baseUrl: String,
    @field:NotBlank
    val apiKey: String,
    @field:NotBlank
    val apiSecret: String,
    @field:NotBlank
    val customerCode: String
)
