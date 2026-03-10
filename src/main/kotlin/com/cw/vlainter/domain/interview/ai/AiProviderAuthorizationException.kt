package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider

class AiProviderAuthorizationException(
    val statusCode: Int,
    override val message: String,
    override val cause: Throwable? = null,
    val provider: AiProvider
) : RuntimeException(message, cause)
