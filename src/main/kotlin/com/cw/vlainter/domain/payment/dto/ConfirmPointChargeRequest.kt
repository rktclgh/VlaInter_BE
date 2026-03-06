package com.cw.vlainter.domain.payment.dto

import jakarta.validation.constraints.NotBlank

data class ConfirmPointChargeRequest(
    @field:NotBlank(message = "impUid는 필수입니다.")
    val impUid: String,

    @field:NotBlank(message = "merchantUid는 필수입니다.")
    val merchantUid: String
)
