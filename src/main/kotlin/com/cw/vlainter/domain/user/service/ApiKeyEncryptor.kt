package com.cw.vlainter.domain.user.service

import com.cw.vlainter.global.config.properties.ApiKeyEncryptionProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class ApiKeyEncryptor(
    private val properties: ApiKeyEncryptionProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    private val secretKey: SecretKeySpec by lazy { toSecretKey(properties.encryptionSecret) }
    private val legacySecretKeys: List<SecretKeySpec> by lazy {
        properties.normalizedLegacyEncryptionSecrets().map(::toSecretKey)
    }

    fun encrypt(plainText: String): String {
        val normalized = plainText.trim()
        require(normalized.isNotBlank()) { "암호화할 텍스트가 비어 있습니다." }

        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))

        return buildString {
            append("v1:")
            append(encoder.encodeToString(iv))
            append(":")
            append(encoder.encodeToString(encrypted))
        }
    }

    fun decrypt(cipherText: String): String {
        val normalized = cipherText.trim()
        if (normalized.isBlank()) return ""
        val parts = normalized.split(":", limit = 3)
        require(parts.size == 3 && parts[0] == "v1") { "지원하지 않는 API 키 암호문 형식입니다." }

        val iv = decoder.decode(parts[1])
        val encrypted = decoder.decode(parts[2])
        var lastFailure: Throwable? = null

        val candidateKeys = buildList {
            add(secretKey)
            addAll(legacySecretKeys)
        }

        candidateKeys.forEachIndexed { index, key ->
            val decrypted = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val bytes = cipher.doFinal(encrypted)
                String(bytes, StandardCharsets.UTF_8)
            }.onFailure { failure ->
                lastFailure = failure
            }.getOrNull()

            if (decrypted != null) {
                if (index > 0) {
                    logger.warn("API 키 복호화에 레거시 시크릿을 사용했습니다. index={}", index - 1)
                }
                return decrypted
            }
        }

        logger.error("API 키 복호화 실패: {}", lastFailure?.message, lastFailure)
        throw IllegalArgumentException("API 키 복호화에 실패했습니다.", lastFailure)
    }

    private fun toSecretKey(secret: String): SecretKeySpec {
        val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secretBytes)
        return SecretKeySpec(keyBytes, "AES")
    }
}
