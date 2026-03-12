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

    fun incrementWithWindow(key: String, window: Duration): Long {
        return redisTemplate.execute(
            incrementWithWindowScript,
            listOf(key),
            window.toMillis().toString()
        )
    }
}
