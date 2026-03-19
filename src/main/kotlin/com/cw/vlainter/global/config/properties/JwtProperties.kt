package com.cw.vlainter.global.config.properties

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.charset.StandardCharsets

@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    var issuer: String = "vlainter",
    var accessTokenExpSeconds: Long = 7200,
    var refreshTokenExpSeconds: Long = 1209600,
    var accessSecret: String = "",
    var refreshSecret: String = ""
) {
    @PostConstruct
    fun validate() {
        require(issuer.isNotBlank()) { "app.jwt.issuer 는 비어 있을 수 없습니다." }
        require(accessTokenExpSeconds > 0) { "app.jwt.access-token-exp-seconds 는 0보다 커야 합니다." }
        require(refreshTokenExpSeconds > 0) { "app.jwt.refresh-token-exp-seconds 는 0보다 커야 합니다." }
        require(accessSecret.isNotBlank()) { "app.jwt.access-secret 는 필수입니다." }
        require(refreshSecret.isNotBlank()) { "app.jwt.refresh-secret 는 필수입니다." }
        require(accessSecret.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "app.jwt.access-secret 는 최소 32바이트 이상이어야 합니다."
        }
        require(refreshSecret.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "app.jwt.refresh-secret 는 최소 32바이트 이상이어야 합니다."
        }
    }
}
