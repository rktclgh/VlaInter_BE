package com.cw.vlainter.domain.payment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank

data class PortoneWebhookRequest(
    @JsonProperty("imp_uid")
    @field:NotBlank(message = "imp_uid는 필수입니다.")
    val impUid: String? = null,

    @JsonProperty("merchant_uid")
    @field:NotBlank(message = "merchant_uid는 필수입니다.")
    val merchantUid: String? = null,

    @JsonProperty("status")
    @field:NotBlank(message = "status는 필수입니다.")
    val status: String? = null
)
