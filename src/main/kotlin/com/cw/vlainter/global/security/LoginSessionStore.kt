package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * Redis에 로그인 세션 상태를 저장/검증한다.
 *
 * 키 구조:
 * - auth:session:{sid}
 *
 * 값 구조(Hash):
 * - userId: 세션 소유자
 * - refreshHash: Refresh Token SHA-256 해시
 * - status: ACTIVE
 */
@Component
class LoginSessionStore(
    private val redisTemplate: StringRedisTemplate,
    private val jwtProperties: JwtProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 새 로그인 세션 생성.
     */
    fun create(sessionId: String, userId: Long, refreshToken: String) {
        redisTemplate.opsForHash<String, String>().putAll(
            key(sessionId),
            mapOf(
                "userId" to userId.toString(),
                "refreshHash" to hash(refreshToken),
                "previousRefreshHash" to "",
                "rotatedAtEpochMs" to "0",
                "status" to "ACTIVE"
            )
        )
        redisTemplate.expire(key(sessionId), Duration.ofSeconds(jwtProperties.refreshTokenExpSeconds))
    }

    /**
     * Access 인증 시 세션이 활성 상태인지 확인한다.
     */
    fun isActive(sessionId: String, userId: Long): Boolean {
        val values = redisTemplate.opsForHash<String, String>().entries(key(sessionId))
        if (values.isEmpty()) return false
        val storedUserId = values["userId"]?.toLongOrNull() ?: return false
        val status = values["status"] ?: return false
        return storedUserId == userId && status == "ACTIVE"
    }

    /**
     * Refresh 요청 시 세션/사용자/토큰 해시 일치 여부를 검증한다.
     */
    fun validateRefreshToken(sessionId: String, userId: Long, refreshToken: String): Boolean {
        return inspectRefreshToken(sessionId, userId, refreshToken, Duration.ZERO) == RefreshTokenValidationResult.CURRENT_TOKEN
    }

    fun inspectRefreshToken(
        sessionId: String,
        userId: Long,
        refreshToken: String,
        graceWindow: Duration
    ): RefreshTokenValidationResult {
        val values = redisTemplate.opsForHash<String, String>().entries(key(sessionId))
        if (values.isEmpty()) return RefreshTokenValidationResult.SESSION_NOT_FOUND
        if (values["status"] != "ACTIVE") return RefreshTokenValidationResult.SESSION_NOT_FOUND
        if (values["userId"]?.toLongOrNull() != userId) return RefreshTokenValidationResult.SESSION_NOT_FOUND

        val tokenHash = hash(refreshToken)
        if (values["refreshHash"] == tokenHash) {
            return RefreshTokenValidationResult.CURRENT_TOKEN
        }

        val previousHash = values["previousRefreshHash"]
        if (previousHash != tokenHash) {
            return RefreshTokenValidationResult.HASH_MISMATCH
        }

        val rotatedAtEpochMs = values["rotatedAtEpochMs"]?.toLongOrNull()
            ?: return RefreshTokenValidationResult.HASH_MISMATCH
        val elapsedMillis = System.currentTimeMillis() - rotatedAtEpochMs
        return if (elapsedMillis in 0..graceWindow.toMillis()) {
            RefreshTokenValidationResult.PREVIOUS_TOKEN_WITHIN_GRACE
        } else {
            RefreshTokenValidationResult.HASH_MISMATCH
        }
    }

    /**
     * Refresh Token 회전 시 새 해시로 교체하고 TTL을 갱신한다.
     */
    fun rotateRefreshToken(sessionId: String, refreshToken: String) {
        val sessionKey = key(sessionId)
        val ops = redisTemplate.opsForHash<String, String>()
        val currentRefreshHash = ops.get(sessionKey, "refreshHash").orEmpty()
        ops.putAll(
            sessionKey,
            mapOf(
                "refreshHash" to hash(refreshToken),
                "previousRefreshHash" to currentRefreshHash,
                "rotatedAtEpochMs" to System.currentTimeMillis().toString()
            )
        )
        redisTemplate.expire(sessionKey, Duration.ofSeconds(jwtProperties.refreshTokenExpSeconds))
    }

    /**
     * 로그아웃/비정상 세션 감지 시 세션을 삭제한다.
     */
    fun delete(sessionId: String) {
        redisTemplate.delete(key(sessionId))
    }

    fun deleteAllByUserId(userId: Long) {
        try {
            val sessionKeys = redisTemplate.keys("auth:session:*")
            if (sessionKeys.isEmpty()) return

            sessionKeys.forEach { sessionKey ->
                val storedUserId = redisTemplate.opsForHash<String, String>()
                    .get(sessionKey, "userId")
                    ?.toLongOrNull()
                if (storedUserId == userId) {
                    redisTemplate.delete(sessionKey)
                }
            }
        } catch (ex: Exception) {
            logger.error("회원 전체 세션 삭제에 실패했습니다. userId={}", userId, ex)
        }
    }

    /**
     * Redis 세션 키 생성.
     */
    private fun key(sessionId: String): String = "auth:session:$sessionId"

    /**
     * 원문 Refresh Token 저장을 피하기 위한 해시 함수.
     */
    private fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

enum class RefreshTokenValidationResult {
    CURRENT_TOKEN,
    PREVIOUS_TOKEN_WITHIN_GRACE,
    HASH_MISMATCH,
    SESSION_NOT_FOUND
}
