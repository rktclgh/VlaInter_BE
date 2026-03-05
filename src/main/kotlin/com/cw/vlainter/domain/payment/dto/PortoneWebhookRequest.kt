package com.cw.vlainter.domain.payment.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class PortoneWebhookRequest(
    @JsonProperty("imp_uid")
    val impUid: String? = null,

    @JsonProperty("merchant_uid")
    val merchantUid: String? = null,

    @JsonProperty("status")
    val status: String? = null
)
