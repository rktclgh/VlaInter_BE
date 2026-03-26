package com.cw.vlainter.global.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
class SuspiciousRequestBlockService(
    private val redisTemplate: StringRedisTemplate,
    private val redisWindowCounterService: RedisWindowCounterService,
    @Value("\${app.docs.enabled:false}")
    private val docsEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(SuspiciousRequestBlockService::class.java)
    private val auditLogWindows = ConcurrentHashMap<String, Long>()

    fun isBlocked(clientIp: String): Boolean {
        return redisTemplate.hasKey(blockKey(clientIp)) == true
    }

    fun recordSuspiciousRequest(clientIp: String, method: String, requestUri: String): Boolean {
        if (!isSuspiciousRequest(method, requestUri)) {
            return false
        }

        val count = redisWindowCounterService.incrementWithWindow(counterKey(clientIp), PROBE_WINDOW)
        val ipHash = SensitiveValueSanitizer.hash(clientIp)
        logger.warn(
            "Suspicious request probe detected ipHash={} method={} path={} count={}",
            ipHash,
            method,
            requestUri,
            count
        )

        if (count < PROBE_THRESHOLD) {
            return false
        }

        redisTemplate.opsForValue().set(blockKey(clientIp), "1", BLOCK_WINDOW)
        logger.warn(
            "Suspicious client temporarily blocked ipHash={} method={} path={} threshold={} windowMinutes={} blockMinutes={}",
            ipHash,
            method,
            requestUri,
            PROBE_THRESHOLD,
            PROBE_WINDOW.toMinutes(),
            BLOCK_WINDOW.toMinutes()
        )
        return true
    }

    fun shouldLogBlockedRequest(clientIp: String): Boolean =
        shouldLogWithinWindow("blocked:${SensitiveValueSanitizer.hash(clientIp)}", BLOCKED_LOG_WINDOW)

    fun shouldLogUnresolvedClientIp(clientIp: String, requestUri: String): Boolean =
        shouldLogWithinWindow(
            "unresolved:${SensitiveValueSanitizer.hash(clientIp)}:${requestUri.lowercase()}",
            UNRESOLVED_LOG_WINDOW
        )

    internal fun isSuspiciousRequest(method: String, requestUri: String): Boolean {
        val lowered = requestUri.lowercase()
        if (
            ".env" in lowered ||
            ".git" in lowered ||
            "phpmyadmin" in lowered ||
            "docker-compose" in lowered ||
            "dockerfile" in lowered ||
            "package.json" in lowered ||
            "package-lock.json" in lowered ||
            "yarn.lock" in lowered ||
            "pnpm-lock.yaml" in lowered ||
            "service-account.json" in lowered ||
            "phpinfo" in lowered ||
            "graphql-playground" in lowered
        ) {
            return true
        }
        if (!docsEnabled && (lowered.startsWith("/swagger-ui") || lowered.startsWith("/v3/api-docs"))) {
            return true
        }
        if (
            "settings.json" in lowered ||
            "fluent-mail" in lowered ||
            "wp-" in lowered ||
            "wp/" in lowered ||
            ".sql" in lowered ||
            ".yaml" in lowered ||
            ".yml" in lowered ||
            ".bak" in lowered ||
            ".old" in lowered ||
            ".swp" in lowered ||
            "backup" in lowered
        ) {
            return true
        }
        if (method.equals("POST", ignoreCase = true) && requestUri == "/") {
            return true
        }
        return false
    }

    private fun counterKey(clientIp: String): String = "security:probe:count:${SensitiveValueSanitizer.hash(clientIp)}"

    private fun blockKey(clientIp: String): String = "security:probe:block:${SensitiveValueSanitizer.hash(clientIp)}"

    private fun shouldLogWithinWindow(key: String, window: Duration): Boolean {
        val now = System.currentTimeMillis()
        val windowMs = window.toMillis()
        var shouldLog = false
        auditLogWindows.compute(key) { _, previous ->
            if (previous == null || now - previous >= windowMs) {
                shouldLog = true
                now
            } else {
                previous
            }
        }
        if (auditLogWindows.size > 4096) {
            val cutoff = now - windowMs
            auditLogWindows.entries.removeIf { it.value < cutoff }
        }
        return shouldLog
    }

    private companion object {
        const val PROBE_THRESHOLD = 5L
        val PROBE_WINDOW: Duration = Duration.ofMinutes(10)
        val BLOCK_WINDOW: Duration = Duration.ofMinutes(30)
        val BLOCKED_LOG_WINDOW: Duration = Duration.ofMinutes(5)
        val UNRESOLVED_LOG_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
