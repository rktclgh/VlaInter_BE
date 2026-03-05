package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.oauth.kakao")
data class KakaoProperties(
    var clientId: String = "",
    var clientSecret: String = "",
    var redirectUri: String = "",
    var tokenUri: String = "https://kauth.kakao.com/oauth/token",
    var userInfoUri: String = "https://kapi.kakao.com/v2/user/me"
)

