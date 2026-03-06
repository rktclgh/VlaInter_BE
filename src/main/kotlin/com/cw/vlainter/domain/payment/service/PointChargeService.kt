package com.cw.vlainter.domain.payment.service

import com.cw.vlainter.domain.payment.client.PortoneClient
import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeRequest
import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeResponse
import com.cw.vlainter.domain.payment.dto.PointLedgerHistoryResponse
import com.cw.vlainter.domain.payment.dto.PointLedgerItemResponse
import com.cw.vlainter.domain.payment.dto.PointPaymentHistoryItemResponse
import com.cw.vlainter.domain.payment.dto.PointPaymentHistoryResponse
import com.cw.vlainter.domain.payment.dto.PointChargeProductResponse
import com.cw.vlainter.domain.payment.dto.PortoneWebhookRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeResponse
import com.cw.vlainter.domain.payment.dto.RefundPointChargeResponse
import com.cw.vlainter.domain.payment.entity.PointCharge
import com.cw.vlainter.domain.payment.entity.PointChargeStatus
import com.cw.vlainter.domain.payment.repository.PointChargePolicyRepository
import com.cw.vlainter.domain.payment.repository.PointChargeRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.config.properties.PortoneProperties
import com.cw.vlainter.global.security.AuthPrincipal
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
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
    private val portoneProperties: PortoneProperties,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val defaultPaymentMethod = "CARD"
    private val refundPendingReason = "__refund_pending__"

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

        val productId = request.productId.trim()
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

    fun confirmCharge(principal: AuthPrincipal, request: ConfirmPointChargeRequest): ConfirmPointChargeResponse {
        val actor = loadActiveUser(principal.userId)
        val merchantUid = request.merchantUid.trim()
        if (merchantUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "주문번호(merchantUid)가 비어 있습니다.")
        }
        val fallbackImpUid = request.impUid.trim()

        val charge = pointChargeRepository.findByMerchantUid(merchantUid)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "충전 요청 정보를 찾을 수 없습니다.") }

        if (charge.user.id != actor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 충전 요청만 확정할 수 있습니다.")
        }
        if (charge.status == PointChargeStatus.PAID) {
            return toConfirmResponse(charge, actor.point)
        }

        val payment = getPaymentWithRetry(merchantUid, fallbackImpUid)
        return transactionTemplate.execute {
            applyVerifiedChargeWithLock(
                merchantUid = merchantUid,
                fallbackImpUid = fallbackImpUid,
                payment = payment,
                expectedUserId = actor.id
            )
        } ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "결제 확정 처리에 실패했습니다.")
    }

    fun handleWebhook(request: PortoneWebhookRequest): Map<String, String> {
        val merchantUid = request.merchantUid?.trim().orEmpty()
        val impUid = request.impUid?.trim().orEmpty()
        if (merchantUid.isBlank() || impUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "merchant_uid, imp_uid, status는 필수입니다.")
        }

        val charge = pointChargeRepository.findByMerchantUid(merchantUid).orElse(null)
            ?: return mapOf("message" to "ignored")

        if (charge.status == PointChargeStatus.PAID) {
            return mapOf("message" to "ok")
        }

        val payment = getPaymentWithRetry(merchantUid, impUid)
        transactionTemplate.executeWithoutResult {
            applyVerifiedChargeWithLock(
                merchantUid = merchantUid,
                fallbackImpUid = impUid,
                payment = payment,
                expectedUserId = null
            )
        }
        return mapOf("message" to "ok")
    }

    @Transactional(readOnly = true)
    fun getPaymentHistory(principal: AuthPrincipal, page: Int, size: Int): PointPaymentHistoryResponse {
        validatePageRequest(page, size)
        val actor = loadActiveUser(principal.userId)
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val paymentPage = pointChargeRepository.findAllByUser_IdOrderByCreatedAtDesc(actor.id, pageable)
        val items = paymentPage.content.map { charge ->
            val pointDelta = when (charge.status) {
                PointChargeStatus.PAID -> charge.rewardPoint
                PointChargeStatus.CANCELLED -> -charge.rewardPoint
                else -> 0L
            }
            val paymentNumber = when {
                !charge.impUid.isNullOrBlank() -> charge.impUid!!
                else -> charge.merchantUid
            }

            PointPaymentHistoryItemResponse(
                chargeId = charge.id,
                occurredAt = resolvePaymentOccurredAt(charge),
                amount = if (charge.paidAmount > 0) charge.paidAmount else charge.requestedAmount,
                pointDelta = pointDelta,
                paymentMethod = defaultPaymentMethod,
                status = charge.status,
                paymentNumber = paymentNumber,
                refundable = isRefundable(actor.point, charge)
            )
        }

        return PointPaymentHistoryResponse(
            currentPoint = actor.point,
            page = paymentPage.number,
            size = paymentPage.size,
            totalCount = paymentPage.totalElements,
            totalPages = paymentPage.totalPages,
            items = items
        )
    }

    @Transactional(readOnly = true)
    fun getPointLedgerHistory(principal: AuthPrincipal, page: Int, size: Int): PointLedgerHistoryResponse {
        validatePageRequest(page, size)
        val actor = loadActiveUser(principal.userId)
        val statuses = listOf(PointChargeStatus.PAID, PointChargeStatus.CANCELLED)
        val chargedEventCount = pointChargeRepository.countByUser_IdAndStatusIn(actor.id, statuses)
        val refundEventCount = pointChargeRepository.countByUser_IdAndStatus(actor.id, PointChargeStatus.CANCELLED)
        val totalCount = chargedEventCount + refundEventCount
        val totalPages = if (totalCount == 0L) 0 else ((totalCount + size - 1) / size).toInt()

        if (totalCount == 0L) {
            return PointLedgerHistoryResponse(
                currentPoint = actor.point,
                page = page,
                size = size,
                totalCount = 0L,
                totalPages = 0,
                items = emptyList()
            )
        }

        val offset = page.toLong() * size.toLong()
        val fetchSize = (offset + size.toLong()).coerceAtLeast(size.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val chargeEvents = pointChargeRepository.findAllByUser_IdAndStatusInOrderByPaidAtDescIdDesc(
            actor.id,
            statuses,
            PageRequest.of(0, fetchSize)
        ).content.map { charge ->
            LedgerEvent(
                occurredAt = resolveChargeOccurredAt(charge),
                pointDelta = charge.rewardPoint,
                description = "포인트 충전",
                chargeId = charge.id,
                eventSeq = 2
            )
        }

        val refundEvents = pointChargeRepository.findAllByUser_IdAndStatusOrderByUpdatedAtDescIdDesc(
            actor.id,
            PointChargeStatus.CANCELLED,
            PageRequest.of(0, fetchSize)
        ).content.map { charge ->
            LedgerEvent(
                occurredAt = charge.updatedAt ?: OffsetDateTime.now(),
                pointDelta = -charge.rewardPoint,
                description = "포인트 환불",
                chargeId = charge.id,
                eventSeq = 1
            )
        }

        val ledgerEvents = (chargeEvents + refundEvents).sortedWith(
            compareByDescending<LedgerEvent> { it.occurredAt }
                .thenByDescending { it.chargeId }
                .thenBy { it.eventSeq }
        )

        val fromIndex = offset.coerceAtMost(ledgerEvents.size.toLong()).toInt()
        val toIndex = (fromIndex + size).coerceAtMost(ledgerEvents.size)
        val pageItems = ledgerEvents.subList(fromIndex, toIndex).map { event ->
            PointLedgerItemResponse(
                occurredAt = event.occurredAt,
                pointDelta = event.pointDelta,
                description = event.description
            )
        }

        return PointLedgerHistoryResponse(
            currentPoint = actor.point,
            page = page,
            size = size,
            totalCount = totalCount,
            totalPages = totalPages,
            items = pageItems
        )
    }

    fun refundCharge(principal: AuthPrincipal, chargeId: Long): RefundPointChargeResponse {
        val actor = loadActiveUser(principal.userId)
        val reserved = transactionTemplate.execute {
            reserveRefund(actor.id, chargeId)
        } ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "환불 예약 처리에 실패했습니다.")

        val canceledPayment = try {
            portoneClient.cancelPayment(reserved.impUid, "사용자 요청 환불")
        } catch (ex: Exception) {
            val finalizedByVerification = runCatching {
                portoneClient.getPayment(reserved.impUid)
            }.getOrNull()?.status?.equals("cancelled", ignoreCase = true) == true

            if (finalizedByVerification) {
                return transactionTemplate.execute {
                    finalizeRefund(actor.id, chargeId)
                } ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "환불 처리에 실패했습니다.")
            }

            transactionTemplate.executeWithoutResult {
                markRefundPending(actor.id, chargeId)
            }
            scheduleRefundVerification(chargeId, reserved.impUid, ex)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "환불 상태 확인이 지연되고 있습니다. 잠시 후 다시 확인해 주세요."
            )
        }
        if (!canceledPayment.status.equals("cancelled", ignoreCase = true)) {
            transactionTemplate.executeWithoutResult {
                rollbackRefundReservation(actor.id, chargeId, reserved.rewardPoint)
            }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "환불 처리 상태가 올바르지 않습니다.")
        }

        return transactionTemplate.execute {
            finalizeRefund(actor.id, chargeId)
        } ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "환불 처리에 실패했습니다.")
    }

    private fun reserveRefund(userId: Long, chargeId: Long): ReservedRefund {
        val charge = pointChargeRepository.findForUpdateById(chargeId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "결제 내역을 찾을 수 없습니다.")

        if (charge.user.id != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 결제 건만 환불할 수 있습니다.")
        }
        if (charge.status != PointChargeStatus.PAID) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "환불 가능한 결제 상태가 아닙니다.")
        }
        if (charge.failureReason == refundPendingReason) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 환불 처리 중인 결제 건입니다.")
        }

        val impUid = charge.impUid?.trim().orEmpty()
        if (impUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "환불할 결제 식별자를 찾을 수 없습니다.")
        }
        val deducted = userRepository.addPointIfNotNegative(userId, -charge.rewardPoint)
        if (deducted == 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "보유 포인트가 부족하여 환불할 수 없습니다. 사용 포인트를 회복한 뒤 다시 시도해 주세요."
            )
        }

        charge.failureReason = refundPendingReason
        pointChargeRepository.save(charge)
        return ReservedRefund(
            impUid = impUid,
            rewardPoint = charge.rewardPoint
        )
    }

    private fun rollbackRefundReservation(userId: Long, chargeId: Long, rewardPoint: Long) {
        val charge = pointChargeRepository.findForUpdateById(chargeId) ?: return
        if (charge.user.id != userId) return
        if (charge.status != PointChargeStatus.PAID) return
        if (charge.failureReason != refundPendingReason) return
        charge.failureReason = null
        pointChargeRepository.save(charge)
        if (rewardPoint > 0) {
            userRepository.rewardPoint(userId, rewardPoint)
        }
    }

    private fun markRefundPending(userId: Long, chargeId: Long) {
        val charge = pointChargeRepository.findForUpdateById(chargeId) ?: return
        if (charge.user.id != userId) return
        if (charge.status != PointChargeStatus.PAID) return
        charge.failureReason = refundPendingReason
        pointChargeRepository.save(charge)
    }

    private fun scheduleRefundVerification(chargeId: Long, impUid: String, cause: Exception) {
        log.warn(
            "Refund verification required for chargeId={}, impUid={}. keep reservation pending until external state is reconciled.",
            chargeId,
            impUid,
            cause
        )
    }

    private fun finalizeRefund(userId: Long, chargeId: Long): RefundPointChargeResponse {
        val actor = loadActiveUser(userId)
        val charge = pointChargeRepository.findForUpdateById(chargeId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "결제 내역을 찾을 수 없습니다.")

        if (charge.user.id != actor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 결제 건만 환불할 수 있습니다.")
        }
        if (charge.status != PointChargeStatus.PAID) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "환불 가능한 결제 상태가 아닙니다.")
        }
        if (charge.failureReason != refundPendingReason) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "환불 예약 상태가 아닙니다.")
        }
        val impUid = charge.impUid?.trim().orEmpty()
        if (impUid.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "환불할 결제 식별자를 찾을 수 없습니다.")
        }

        charge.status = PointChargeStatus.CANCELLED
        charge.failureReason = null

        pointChargeRepository.save(charge)
        val currentPoint = loadCurrentPoint(actor.id)

        return RefundPointChargeResponse(
            chargeId = charge.id,
            status = charge.status,
            refundedPoint = charge.rewardPoint,
            currentPoint = currentPoint
        )
    }

    private fun applyVerifiedChargeWithLock(
        merchantUid: String,
        fallbackImpUid: String,
        payment: com.cw.vlainter.domain.payment.client.PortonePayment,
        expectedUserId: Long?
    ): ConfirmPointChargeResponse {
        val charge = pointChargeRepository.findForUpdateByMerchantUid(merchantUid)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "충전 요청 정보를 찾을 수 없습니다.")

        if (expectedUserId != null && charge.user.id != expectedUserId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "본인 충전 요청만 확정할 수 있습니다.")
        }

        if (charge.status == PointChargeStatus.PAID) {
            return toConfirmResponse(charge, loadCurrentPoint(charge.user.id))
        }

        val verifiedImpUid = payment.impUid.ifBlank { fallbackImpUid }
        if (verifiedImpUid.isBlank()) {
            failCharge(charge, fallbackImpUid, "결제 식별자 검증에 실패했습니다.")
        }
        val duplicateByVerifiedImpUid = pointChargeRepository.findForUpdateByImpUid(verifiedImpUid)
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

        if (charge.rewardPoint <= 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "적립 포인트 값이 올바르지 않습니다.")
        }
        val updated = userRepository.rewardPoint(charge.user.id, charge.rewardPoint)
        if (updated == 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "포인트 적립 처리에 실패했습니다.")
        }
        pointChargeRepository.save(charge)
        val currentPoint = loadCurrentPoint(charge.user.id)

        return ConfirmPointChargeResponse(
            merchantUid = charge.merchantUid,
            impUid = verifiedImpUid,
            paidAmount = paidAmount,
            chargedPoint = charge.rewardPoint,
            currentPoint = currentPoint
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

    private fun toConfirmResponse(charge: PointCharge, currentPoint: Long): ConfirmPointChargeResponse {
        return ConfirmPointChargeResponse(
            merchantUid = charge.merchantUid,
            impUid = charge.impUid.orEmpty(),
            paidAmount = charge.paidAmount,
            chargedPoint = charge.rewardPoint,
            currentPoint = currentPoint
        )
    }

    private fun loadCurrentPoint(userId: Long): Long {
        return userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }
            .point
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

    private fun validatePageRequest(page: Int, size: Int) {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (size !in 1..100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1 이상 100 이하여야 합니다.")
        }
    }

    private fun resolvePaymentOccurredAt(charge: PointCharge): OffsetDateTime {
        return when (charge.status) {
            PointChargeStatus.PAID -> resolveChargeOccurredAt(charge)
            PointChargeStatus.CANCELLED -> charge.updatedAt ?: resolveChargeOccurredAt(charge)
            else -> charge.createdAt ?: charge.updatedAt ?: OffsetDateTime.now()
        }
    }

    private fun resolveChargeOccurredAt(charge: PointCharge): OffsetDateTime {
        return charge.paidAt ?: charge.createdAt ?: charge.updatedAt ?: OffsetDateTime.now()
    }

    private fun isRefundable(currentPoint: Long, charge: PointCharge): Boolean {
        if (charge.status != PointChargeStatus.PAID) return false
        if (charge.impUid.isNullOrBlank()) return false
        return currentPoint >= charge.rewardPoint
    }

    private data class ReservedRefund(
        val impUid: String,
        val rewardPoint: Long
    )

    private data class LedgerEvent(
        val occurredAt: OffsetDateTime,
        val pointDelta: Long,
        val description: String,
        val chargeId: Long,
        val eventSeq: Int
    )

}
