package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider

class AiProviderTransientException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
    val provider: AiProvider = AiProvider.GEMINI
) : RuntimeException(message, cause)

typealias GeminiTransientException = AiProviderTransientException
