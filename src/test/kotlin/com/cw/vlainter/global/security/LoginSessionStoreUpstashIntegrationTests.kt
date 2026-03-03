package com.cw.vlainter.global.security

import com.cw.vlainter.global.config.properties.JwtProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@SpringBootTest
@TestPropertySource(
    properties = [
        "app.jwt.issuer=vlainter-test",
        "app.jwt.access-token-exp-seconds=900",
        "app.jwt.refresh-token-exp-seconds=120",
        "app.jwt.access-secret=12345678901234567890123456789012",
        "app.jwt.refresh-secret=abcdefghijklmnopqrstuvwxyz123456",
        "app.cookie.domain=localhost",
        "app.cookie.secure=false",
        "app.cookie.same-site=Lax",
        "app.cookie.access-token-name=vlainter_at",
        "app.cookie.refresh-token-name=vlainter_rt",
        "app.redirect.allowed-origins=http://localhost:5173",
        "app.cors.allowed-origins=http://localhost:5173"
    ]
)
class LoginSessionStoreUpstashIntegrationTests {

    @Autowired
    private lateinit var loginSessionStore: LoginSessionStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Value("\${spring.data.redis.url:}")
    private lateinit var redisUrl: String

    @Autowired
    private lateinit var jwtProperties: JwtProperties

    private val createdSessionKeys = mutableListOf<String>()

    @BeforeEach
    fun checkUpstashConfiguration() {
        assumeTrue(
            redisUrl.isNotBlank(),
            "spring.data.redis.url 이 없어서 Upstash 통합 테스트를 건너뜁니다."
        )
    }

    @AfterEach
    fun cleanupCreatedKeys() {
        if (createdSessionKeys.isNotEmpty()) {
            redisTemplate.delete(createdSessionKeys)
            createdSessionKeys.clear()
        }
    }

    @Test
    fun `Upstash - create 후 isActive와 validateRefreshToken이 true를 반환한다`() {
        val sessionId = uniqueSessionId("create")
        val key = registerSessionKey(sessionId)
        val userId = 101L
        val refreshToken = "refresh-token-create"

        loginSessionStore.create(sessionId, userId, refreshToken)

        assertThat(loginSessionStore.isActive(sessionId, userId)).isTrue()
        assertThat(loginSessionStore.validateRefreshToken(sessionId, userId, refreshToken)).isTrue()

        val entries = redisTemplate.opsForHash<String, String>().entries(key)
        assertThat(entries["userId"]).isEqualTo(userId.toString())
        assertThat(entries["status"]).isEqualTo("ACTIVE")
        assertThat(entries["refreshHash"]).isNotBlank()
        assertThat(entries["refreshHash"]).isNotEqualTo(refreshToken)

        val ttl = redisTemplate.getExpire(key)
        assertThat(ttl).isGreaterThan(0L)
        assertThat(ttl).isLessThanOrEqualTo(jwtProperties.refreshTokenExpSeconds)
    }

    @Test
    fun `Upstash - validateRefreshToken은 사용자 또는 토큰이 다르면 false다`() {
        val sessionId = uniqueSessionId("validate")
        registerSessionKey(sessionId)
        val userId = 202L
        val refreshToken = "refresh-token-validate"

        loginSessionStore.create(sessionId, userId, refreshToken)

        assertThat(loginSessionStore.validateRefreshToken(sessionId, userId + 1, refreshToken)).isFalse()
        assertThat(loginSessionStore.validateRefreshToken(sessionId, userId, "wrong-token")).isFalse()
    }

    @Test
    fun `Upstash - rotateRefreshToken 후 이전 토큰은 무효화된다`() {
        val sessionId = uniqueSessionId("rotate")
        val key = registerSessionKey(sessionId)
        val userId = 303L
        val oldRefreshToken = "refresh-token-old"
        val newRefreshToken = "refresh-token-new"

        loginSessionStore.create(sessionId, userId, oldRefreshToken)
        val beforeTtl = redisTemplate.getExpire(key)

        loginSessionStore.rotateRefreshToken(sessionId, newRefreshToken)

        assertThat(loginSessionStore.validateRefreshToken(sessionId, userId, oldRefreshToken)).isFalse()
        assertThat(loginSessionStore.validateRefreshToken(sessionId, userId, newRefreshToken)).isTrue()

        val afterTtl = redisTemplate.getExpire(key)
        assertThat(afterTtl).isGreaterThan(0L)
        assertThat(afterTtl).isLessThanOrEqualTo(jwtProperties.refreshTokenExpSeconds)
        assertThat(afterTtl).isGreaterThanOrEqualTo(beforeTtl)
    }

    @Test
    fun `Upstash - delete 후 세션은 비활성화된다`() {
        val sessionId = uniqueSessionId("delete")
        val key = registerSessionKey(sessionId)
        val userId = 404L
        val refreshToken = "refresh-token-delete"

        loginSessionStore.create(sessionId, userId, refreshToken)
        assertThat(loginSessionStore.isActive(sessionId, userId)).isTrue()

        loginSessionStore.delete(sessionId)

        assertThat(loginSessionStore.isActive(sessionId, userId)).isFalse()
        assertThat(loginSessionStore.validateRefreshToken(sessionId, userId, refreshToken)).isFalse()
        assertThat(redisTemplate.hasKey(key)).isFalse()
    }

    private fun uniqueSessionId(label: String): String = "it-$label-${UUID.randomUUID()}"

    private fun registerSessionKey(sessionId: String): String {
        return "auth:session:$sessionId".also { createdSessionKeys.add(it) }
    }
}
