package com.cw.vlainter.global.security

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RedisWindowCounterService(
    private val redisTemplate: StringRedisTemplate
) {
    private val incrementWithWindowScript = DefaultRedisScript<Long>().apply {
        setScriptText(
            """
            local key = KEYS[1]
            local desiredTtlMs = tonumber(ARGV[1])
            local count = redis.call('INCR', key)
            local ttl = redis.call('PTTL', key)

            if ttl < 0 or ttl > desiredTtlMs then
                redis.call('PEXPIRE', key, desiredTtlMs)
            end

            return count
            """.trimIndent()
        )
        resultType = Long::class.java
    }

    /**
     * Increments a Redis counter and guarantees the requested window TTL in one atomic script.
     *
     * Throws [IllegalStateException] when Redis returns a null script result so callers never
     * silently treat a failed increment as a successful count.
     */
    fun incrementWithWindow(key: String, window: Duration): Long {
        val count = redisTemplate.execute(
            incrementWithWindowScript,
            listOf(key),
            window.toMillis().toString()
        ) as Long?
        return count ?: throw IllegalStateException("Redis window counter increment returned null for key=$key")
    }
}
