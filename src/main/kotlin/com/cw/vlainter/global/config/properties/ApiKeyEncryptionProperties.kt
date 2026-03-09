package com.cw.vlainter.global.config.properties

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.charset.StandardCharsets

@ConfigurationProperties(prefix = "app.security.api-key")
data class ApiKeyEncryptionProperties(
    var encryptionSecret: String = "",
    var legacyEncryptionSecrets: List<String> = emptyList()
) {
    @PostConstruct
    fun validate() {
        require(encryptionSecret.isNotBlank()) { "app.security.api-key.encryption-secret 는 필수입니다." }
        require(encryptionSecret.toByteArray(StandardCharsets.UTF_8).size >= 16) {
            "app.security.api-key.encryption-secret 는 최소 16바이트 이상이어야 합니다."
        }
        normalizedLegacyEncryptionSecrets().forEachIndexed { index, secret ->
            require(secret.toByteArray(StandardCharsets.UTF_8).size >= 16) {
                "app.security.api-key.legacy-encryption-secrets[$index] 는 최소 16바이트 이상이어야 합니다."
            }
        }
    }

    fun normalizedLegacyEncryptionSecrets(): List<String> {
        val primary = encryptionSecret.trim()
        return legacyEncryptionSecrets
            .map { it.trim() }
            .filter { it.isNotBlank() && it != primary }
            .distinct()
    }
}
