package com.cw.vlainter.domain.payment.client

import java.math.BigDecimal

interface PortoneClient {
    fun getPayment(impUid: String): PortonePayment
    fun getPaymentByMerchantUid(merchantUid: String): PortonePayment
    fun cancelPayment(impUid: String, reason: String): PortonePayment
}

data class PortonePayment(
    val impUid: String,
    val merchantUid: String,
    val status: String,
    val amount: BigDecimal
)
