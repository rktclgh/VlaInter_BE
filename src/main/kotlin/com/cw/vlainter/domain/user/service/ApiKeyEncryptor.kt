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

    private val secretKey: SecretKeySpec by lazy {
        val secretBytes = properties.encryptionSecret.toByteArray(StandardCharsets.UTF_8)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secretBytes)
        SecretKeySpec(keyBytes, "AES")
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

        return runCatching {
            val iv = decoder.decode(parts[1])
            val encrypted = decoder.decode(parts[2])
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val bytes = cipher.doFinal(encrypted)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrElse {
            logger.error("API 키 복호화 실패: {}", it.message, it)
            throw IllegalArgumentException("API 키 복호화에 실패했습니다.", it)
        }
    }
}
