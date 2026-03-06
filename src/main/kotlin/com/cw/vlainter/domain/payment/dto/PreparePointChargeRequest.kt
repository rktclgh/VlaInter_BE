package com.cw.vlainter.domain.payment.dto

import jakarta.validation.constraints.NotBlank

data class PreparePointChargeRequest(
    @field:NotBlank(message = "충전 상품 ID를 입력해 주세요.")
    val productId: String
)
