package com.cw.vlainter.domain.user.dto

import jakarta.validation.constraints.NotBlank

data class UpdateGeminiApiKeyRequest(
    @field:NotBlank(message = "geminiApiKey는 비어 있을 수 없습니다.")
    val geminiApiKey: String
)
