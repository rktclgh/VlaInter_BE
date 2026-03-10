package com.cw.vlainter.domain.user.service

import com.cw.vlainter.domain.interview.ai.GeminiApiKeyContextHolder
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class UserGeminiApiKeyService(
    private val userRepository: UserRepository,
    private val apiKeyEncryptor: ApiKeyEncryptor,
    private val apiKeyContextHolder: GeminiApiKeyContextHolder
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun updateMyGeminiApiKey(principal: AuthPrincipal, rawApiKey: String): User {
        val user = loadActiveUser(principal.userId)
        val normalized = normalizeRawApiKey(rawApiKey)
        user.geminiApiKeyEncrypted = apiKeyEncryptor.encrypt(normalized)
        return userRepository.save(user)
    }

    @Transactional
    fun clearMyGeminiApiKey(principal: AuthPrincipal): User {
        val user = loadActiveUser(principal.userId)
        user.geminiApiKeyEncrypted = null
        return userRepository.save(user)
    }

    fun <T> withUserApiKey(userId: Long, block: () -> T): T {
        val key = getRequiredDecryptedGeminiApiKey(userId)
        return apiKeyContextHolder.withApiKey(key, block)
    }

    fun hasGeminiApiKey(user: User): Boolean {
        return !user.geminiApiKeyEncrypted.isNullOrBlank()
    }

    fun assertGeminiApiKeyConfigured(userId: Long) {
        getRequiredDecryptedGeminiApiKey(userId)
    }

    fun getRequiredDecryptedGeminiApiKey(userId: Long): String {
        val user = loadActiveUser(userId)
        val encrypted = user.geminiApiKeyEncrypted?.trim().orEmpty()
        if (encrypted.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.PRECONDITION_REQUIRED,
                "Gemini API 키가 등록되지 않았습니다. 마이페이지에서 API 키를 입력해 주세요."
            )
        }
        return try {
            apiKeyEncryptor.decrypt(encrypted)
        } catch (ex: IllegalArgumentException) {
            logger.warn("Gemini API 키 복호화 실패 userId={} reason={}", userId, ex.message)
            throw ResponseStatusException(
                HttpStatus.PRECONDITION_REQUIRED,
                "저장된 Gemini API 키를 확인할 수 없습니다. 마이페이지에서 API 키를 다시 입력해 주세요."
            )
        }
    }

    private fun loadActiveUser(userId: Long): User {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }
        if (user.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 계정은 Gemini API 키를 사용할 수 없습니다.")
        }
        return user
    }

    private fun normalizeRawApiKey(rawApiKey: String): String {
        val normalized = rawApiKey.trim()
        if (normalized.length < 20 || normalized.length > 512) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Gemini API 키 형식이 올바르지 않습니다.")
        }
        return normalized
    }
}
