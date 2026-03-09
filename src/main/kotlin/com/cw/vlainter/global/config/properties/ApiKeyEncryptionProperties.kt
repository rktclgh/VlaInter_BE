package com.cw.vlainter.global.config.properties

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.charset.StandardCharsets

@ConfigurationProperties(prefix = "app.security.api-key")
data class ApiKeyEncryptionProperties(
    var encryptionSecret: String = ""
) {
    @PostConstruct
    fun validate() {
        require(encryptionSecret.isNotBlank()) { "app.security.api-key.encryption-secret 는 필수입니다." }
        require(encryptionSecret.toByteArray(StandardCharsets.UTF_8).size >= 16) {
            "app.security.api-key.encryption-secret 는 최소 16바이트 이상이어야 합니다."
        }
    }
}
