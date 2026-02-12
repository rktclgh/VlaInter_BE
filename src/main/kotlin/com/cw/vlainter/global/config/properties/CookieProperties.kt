package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cookie")
data class CookieProperties(
    var domain: String = "",
    var secure: Boolean = true,
    var sameSite: String = "Lax",
    var accessTokenName: String = "vlainter_at",
    var refreshTokenName: String = "vlainter_rt"
)
