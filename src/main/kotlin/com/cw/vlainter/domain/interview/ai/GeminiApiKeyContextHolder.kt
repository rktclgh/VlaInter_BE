package com.cw.vlainter.domain.interview.ai

import org.springframework.stereotype.Component

@Component
class GeminiApiKeyContextHolder {
    private val apiKeyHolder = ThreadLocal<String?>()

    fun currentApiKey(): String? {
        return apiKeyHolder.get()?.trim()?.takeIf { it.isNotBlank() }
    }

    fun <T> withApiKey(apiKey: String?, block: () -> T): T {
        val previous = apiKeyHolder.get()
        val normalized = apiKey?.trim().orEmpty().ifBlank { null }
        if (normalized == null) {
            apiKeyHolder.remove()
        } else {
            apiKeyHolder.set(normalized)
        }
        return try {
            block()
        } finally {
            if (previous.isNullOrBlank()) {
                apiKeyHolder.remove()
            } else {
                apiKeyHolder.set(previous)
            }
        }
    }
}
