package com.cw.vlainter.domain.payment.service

import com.cw.vlainter.domain.payment.client.PortoneClient
import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeRequest
import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeResponse
import com.cw.vlainter.domain.payment.dto.PointChargeProductResponse
import com.cw.vlainter.domain.payment.dto.PortoneWebhookRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeResponse
import com.cw.vlainter.domain.payment.entity.PointCharge
import com.cw.vlainter.domain.payment.entity.PointChargeStatus
import com.cw.vlainter.domain.payment.repository.PointChargePolicyRepository
import com.cw.vlainter.domain.payment.repository.PointChargeRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.config.properties.PortoneProperties
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Service
class PointChargeService(
    private val userRepository: UserRepository,
    private val pointChargeRepository: PointChargeRepository,
    private val pointChargePolicyRepository: PointChargePolicyRepository,
    private val portoneClient: PortoneClient,
    private val portoneProperties: PortoneProperties
) {
    @Transactional(readOnly = true)
    fun getPointChargeProducts(): List<PointChargeProductResponse> {
        return pointChargePolicyRepository.findAllActive().map { product ->
            PointChargeProductResponse(
                productId = product.productId,
                amount = product.amount,
                rewardPoint = product.rewardPoint
            )
        }
    }

    @Transactional
    fun prepareCharge(principal: AuthPrincipal, request: PreparePointChargeRequest): PreparePointChargeResponse {
        val actor = loadActiveUser(principal.userId)
        ensurePortoneConfigured()

        val productId = request.productId?.trim().orEmpty()
        if (productId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "충전 상품 ID를 입력해 주세요.")
        }
        val product = pointChargePolicyRepository.findActiveByProductId(productId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 충전 상품입니다.")
        val merchantUid = buildMerchantUid(actor.id)

        pointChargeRepository.save(
            PointCharge(
                user = actor,
                merchantUid = merchantUid,
                requestedAmount = product.amount,
                rewardPoint = product.rewardPoint,
                status = PointChargeStatus.READY
            )
        )

        return PreparePointChargeResponse(
            productId = product.productId,
            merchantUid = merchantUid,
            amount = product.amount,
            rewardPoint = product.rewardPoint,
            customerCode = portoneProperties.customerCode.trim(),
            buyerEmail = actor.email,
            buyerName = actor.name
        )
    }

    @Transactional
    fun confirmCharge(principal: AuthPrincipal, request: ConfirmPointChargeRequest): ConfirmPointChargeResponse {
        val actor = loadActiveUser(principal.userId)
        val charge = pointChargeRepository.findByMerchantUid(request.merchantUid.trim())
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "충전 요청 정보를 찾을 수 없습니다.") }

        if (charge.user.id != actor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 충전 요청만 확정할 수 있습니다.")
        }
        return verifyAndApplyCharge(charge, request.impUid.trim())
    }

    @Transactional
    fun handleWebhook(request: PortoneWebhookRequest): Map<String, String> {
        val merchantUid = request.merchantUid?.trim().orEmpty()
        val impUid = request.impUid?.trim().orEmpty()
        if (merchantUid.isBlank() || impUid.isBlank()) {
            return mapOf("message" to "ignored")
        }

        val charge = pointChargeRepository.findByMerchantUid(merchantUid).orElse(null)
            ?: return mapOf("message" to "ignored")

        verifyAndApplyCharge(charge, impUid)
        return mapOf("message" to "ok")
    }

    private fun verifyAndApplyCharge(charge: PointCharge, impUid: String): ConfirmPointChargeResponse {
        if (charge.status == PointChargeStatus.PAID) {
            val currentPoint = charge.user.point
            return ConfirmPointChargeResponse(
                merchantUid = charge.merchantUid,
                impUid = charge.impUid.orEmpty(),
                paidAmount = charge.paidAmount,
                chargedPoint = charge.rewardPoint,
                currentPoint = currentPoint
            )
        }

        val payment = getPaymentWithRetry(charge.merchantUid, impUid)
        val verifiedImpUid = payment.impUid.ifBlank { impUid }
        if (verifiedImpUid.isBlank()) {
            failCharge(charge, impUid, "결제 식별자 검증에 실패했습니다.")
        }
        val duplicateByVerifiedImpUid = pointChargeRepository.findByImpUid(verifiedImpUid).orElse(null)
        if (duplicateByVerifiedImpUid != null && duplicateByVerifiedImpUid.id != charge.id) {
            failCharge(charge, verifiedImpUid, "이미 처리된 결제 식별자입니다.")
        }

        if (!payment.merchantUid.equals(charge.merchantUid, ignoreCase = false)) {
            failCharge(charge, verifiedImpUid, "결제 정보의 merchantUid가 일치하지 않습니다.")
        }
        if (!payment.status.equals("paid", ignoreCase = true)) {
            failCharge(charge, verifiedImpUid, "결제 완료 상태가 아닙니다. status=${payment.status}")
        }

        val paidAmount = toPositiveAmount(payment.amount)
        if (paidAmount != charge.requestedAmount) {
            failCharge(charge, verifiedImpUid, "결제 금액이 요청 금액과 일치하지 않습니다.")
        }

        charge.impUid = verifiedImpUid
        charge.paidAmount = paidAmount
        charge.status = PointChargeStatus.PAID
        charge.failureReason = null
        charge.paidAt = OffsetDateTime.now()

        val user = charge.user
        user.point += charge.rewardPoint
        userRepository.save(user)
        pointChargeRepository.save(charge)

        return ConfirmPointChargeResponse(
            merchantUid = charge.merchantUid,
            impUid = verifiedImpUid,
            paidAmount = paidAmount,
            chargedPoint = charge.rewardPoint,
            currentPoint = user.point
        )
    }

    private fun failCharge(charge: PointCharge, impUid: String, reason: String): Nothing {
        if (charge.status != PointChargeStatus.PAID) {
            charge.impUid = impUid
            charge.status = PointChargeStatus.FAILED
            charge.failureReason = reason.take(255)
            pointChargeRepository.save(charge)
        }
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
    }

    private fun loadActiveUser(userId: Long): User {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }
        if (user.status != UserStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "비활성 계정은 결제 기능을 사용할 수 없습니다.")
        }
        return user
    }

    private fun buildMerchantUid(userId: Long): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        return "point-${userId}-${System.currentTimeMillis()}-$suffix"
    }

    private fun toPositiveAmount(amount: BigDecimal): Int {
        if (amount.signum() <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 금액이 올바르지 않습니다.")
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 금액 형식이 올바르지 않습니다.")
        }
        return runCatching { amount.intValueExact() }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 금액 범위를 초과했습니다.") }
    }

    private fun getPaymentWithRetry(
        merchantUid: String,
        fallbackImpUid: String
    ): com.cw.vlainter.domain.payment.client.PortonePayment {
        var lastException: ResponseStatusException? = null
        repeat(3) { attempt ->
            try {
                return portoneClient.getPaymentByMerchantUid(merchantUid)
            } catch (ex: ResponseStatusException) {
                val retryable = ex.statusCode == HttpStatus.BAD_REQUEST &&
                    ex.reason?.contains("결제 정보를 찾을 수 없습니다") == true &&
                    attempt < 2
                if (!retryable) {
                    if (fallbackImpUid.isNotBlank()) {
                        return portoneClient.getPayment(fallbackImpUid)
                    }
                    throw ex
                }
                lastException = ex
                Thread.sleep(600L)
            }
        }
        if (fallbackImpUid.isNotBlank()) {
            return portoneClient.getPayment(fallbackImpUid)
        }
        throw lastException ?: ResponseStatusException(HttpStatus.BAD_GATEWAY, "결제 조회에 실패했습니다.")
    }

    private fun ensurePortoneConfigured() {
        if (portoneProperties.customerCode.isBlank() || portoneProperties.apiKey.isBlank() || portoneProperties.apiSecret.isBlank()) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PortOne 설정이 누락되었습니다.")
        }
    }
}
