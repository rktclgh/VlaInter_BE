package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    var issuer: String = "vlainter",
    var accessTokenExpSeconds: Long = 900,
    var refreshTokenExpSeconds: Long = 1209600,
    var accessSecret: String = "",
    var refreshSecret: String = ""
)
