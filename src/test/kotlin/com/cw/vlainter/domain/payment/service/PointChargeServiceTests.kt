package com.cw.vlainter.domain.payment.service

import com.cw.vlainter.domain.payment.client.PortoneClient
import com.cw.vlainter.domain.payment.client.PortonePayment
import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeRequest
import com.cw.vlainter.domain.payment.entity.PointCharge
import com.cw.vlainter.domain.payment.entity.PointChargeStatus
import com.cw.vlainter.domain.payment.model.PointChargePolicy
import com.cw.vlainter.domain.payment.repository.PointChargePolicyRepository
import com.cw.vlainter.domain.payment.repository.PointChargeRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.config.properties.PortoneProperties
import com.cw.vlainter.global.security.AuthPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.Mockito.never
import org.springframework.http.HttpStatus
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PointChargeServiceTests {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var pointChargeRepository: PointChargeRepository

    @Mock
    private lateinit var pointChargePolicyRepository: PointChargePolicyRepository

    @Mock
    private lateinit var portoneClient: PortoneClient

    @Test
    fun prepareChargeMapsProductToAmountAndRewardPoint() {
        val user = createUser()
        val principal = createPrincipal(user)
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargePolicyRepository.findActiveByProductId("POINT_100000")).willReturn(
            PointChargePolicy(
                productId = "POINT_100000",
                amount = 100_000,
                rewardPoint = 200_000L,
                sortOrder = 6
            )
        )
        given(pointChargeRepository.save(org.mockito.ArgumentMatchers.any(PointCharge::class.java)))
            .willAnswer { invocation -> invocation.getArgument(0) }

        val result = service().prepareCharge(principal, PreparePointChargeRequest(productId = "POINT_100000"))

        assertThat(result.productId).isEqualTo("POINT_100000")
        assertThat(result.amount).isEqualTo(100_000)
        assertThat(result.rewardPoint).isEqualTo(200_000)

        val captor = ArgumentCaptor.forClass(PointCharge::class.java)
        then(pointChargeRepository).should().save(captor.capture())
        assertThat(captor.value.requestedAmount).isEqualTo(100_000)
        assertThat(captor.value.rewardPoint).isEqualTo(200_000)
        assertThat(captor.value.status).isEqualTo(PointChargeStatus.READY)
    }

    @Test
    fun confirmChargeAddsUserPointWhenPortonePaymentIsValid() {
        val user = createUser(point = 500L)
        val principal = createPrincipal(user)
        val charge = PointCharge(
            id = 1L,
            user = user,
            merchantUid = "merchant-1",
            requestedAmount = 10_000,
            rewardPoint = 10_000L,
            status = PointChargeStatus.READY
        )

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.findByMerchantUid("merchant-1")).willReturn(Optional.of(charge))
        given(pointChargeRepository.findByMerchantUidForUpdate("merchant-1")).willReturn(charge)
        given(pointChargeRepository.findByImpUidForUpdate("imp-1")).willReturn(null)
        given(portoneClient.getPaymentByMerchantUid("merchant-1")).willReturn(
            PortonePayment(
                impUid = "imp-1",
                merchantUid = "merchant-1",
                status = "paid",
                amount = BigDecimal("10000")
            )
        )
        given(userRepository.addPoint(user.id, 10_000L)).willAnswer {
            user.point += 10_000L
            1
        }
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.save(charge)).willReturn(charge)

        val result = service().confirmCharge(
            principal,
            ConfirmPointChargeRequest(impUid = "imp-1", merchantUid = "merchant-1")
        )

        assertThat(result.chargedPoint).isEqualTo(10_000L)
        assertThat(result.currentPoint).isEqualTo(10_500L)
        assertThat(charge.status).isEqualTo(PointChargeStatus.PAID)
        assertThat(charge.impUid).isEqualTo("imp-1")
    }

    @Test
    fun confirmChargeThrowsBadRequestWhenAmountMismatches() {
        val user = createUser()
        val principal = createPrincipal(user)
        val charge = PointCharge(
            id = 1L,
            user = user,
            merchantUid = "merchant-1",
            requestedAmount = 20_000,
            rewardPoint = 22_000L,
            status = PointChargeStatus.READY
        )

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.findByMerchantUid("merchant-1")).willReturn(Optional.of(charge))
        given(pointChargeRepository.findByMerchantUidForUpdate("merchant-1")).willReturn(charge)
        given(pointChargeRepository.findByImpUidForUpdate("imp-2")).willReturn(null)
        given(portoneClient.getPaymentByMerchantUid("merchant-1")).willReturn(
            PortonePayment(
                impUid = "imp-2",
                merchantUid = "merchant-1",
                status = "paid",
                amount = BigDecimal("10000")
            )
        )
        given(pointChargeRepository.save(charge)).willReturn(charge)

        val exception = assertThrows<ResponseStatusException> {
            service().confirmCharge(
                principal,
                ConfirmPointChargeRequest(impUid = "imp-2", merchantUid = "merchant-1")
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(charge.status).isEqualTo(PointChargeStatus.FAILED)
    }

    @Test
    fun refundChargeCancelsPortonePaymentAndDeductsPoint() {
        val user = createUser(point = 15_000L)
        val principal = createPrincipal(user)
        val charge = PointCharge(
            id = 10L,
            user = user,
            merchantUid = "merchant-10",
            impUid = "imp-10",
            requestedAmount = 10_000,
            rewardPoint = 10_000L,
            paidAmount = 10_000,
            status = PointChargeStatus.PAID
        )

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.findById(charge.id)).willReturn(Optional.of(charge))
        given(pointChargeRepository.findByIdForUpdate(charge.id)).willReturn(charge)
        given(portoneClient.cancelPayment("imp-10", "사용자 요청 환불")).willReturn(
            PortonePayment(
                impUid = "imp-10",
                merchantUid = "merchant-10",
                status = "cancelled",
                amount = BigDecimal("10000")
            )
        )
        given(userRepository.addPointIfNotNegative(user.id, -10_000L)).willAnswer {
            user.point -= 10_000L
            1
        }
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.save(charge)).willReturn(charge)

        val result = service().refundCharge(principal, charge.id)

        assertThat(result.status).isEqualTo(PointChargeStatus.CANCELLED)
        assertThat(result.currentPoint).isEqualTo(5_000L)
        assertThat(user.point).isEqualTo(5_000L)
        assertThat(charge.status).isEqualTo(PointChargeStatus.CANCELLED)
    }

    @Test
    fun refundChargeThrowsBadRequestWhenCurrentPointIsNotEnough() {
        val user = createUser(point = 3_000L)
        val principal = createPrincipal(user)
        val charge = PointCharge(
            id = 11L,
            user = user,
            merchantUid = "merchant-11",
            impUid = "imp-11",
            requestedAmount = 10_000,
            rewardPoint = 10_000L,
            paidAmount = 10_000,
            status = PointChargeStatus.PAID
        )

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.findById(charge.id)).willReturn(Optional.of(charge))

        val exception = assertThrows<ResponseStatusException> {
            service().refundCharge(principal, charge.id)
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        then(portoneClient).should(never()).cancelPayment(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun getPointLedgerHistoryIncludesChargeAndRefundRows() {
        val user = createUser(point = 30_000L)
        val principal = createPrincipal(user)
        val now = OffsetDateTime.now()

        given(userRepository.findById(user.id)).willReturn(Optional.of(user))
        given(pointChargeRepository.countLedgerRows(user.id)).willReturn(3L)
        given(pointChargeRepository.findLedgerRows(user.id, 10, 0)).willReturn(
            listOf(
                ledgerRow(now, -36_000L, "포인트 환불"),
                ledgerRow(now.minusDays(1), 36_000L, "포인트 충전"),
                ledgerRow(now.minusDays(2), 22_000L, "포인트 충전")
            )
        )

        val response = service().getPointLedgerHistory(principal, page = 0, size = 10)

        assertThat(response.totalCount).isEqualTo(3L)
        assertThat(response.items).hasSize(3)
        assertThat(response.items[0].pointDelta).isEqualTo(-36_000L)
        assertThat(response.items[0].description).isEqualTo("포인트 환불")
        assertThat(response.items[1].pointDelta).isEqualTo(36_000L)
        assertThat(response.items[2].pointDelta).isEqualTo(22_000L)
    }

    private fun service(): PointChargeService {
        return PointChargeService(
            userRepository = userRepository,
            pointChargeRepository = pointChargeRepository,
            pointChargePolicyRepository = pointChargePolicyRepository,
            portoneClient = portoneClient,
            portoneProperties = PortoneProperties(
                baseUrl = "https://api.iamport.kr",
                apiKey = "test-key",
                apiSecret = "test-secret",
                customerCode = "imp12345678"
            ),
            transactionTemplate = TransactionTemplate(NoOpTransactionManager())
        )
    }

    private fun ledgerRow(occurredAt: OffsetDateTime, pointDelta: Long, description: String) =
        object : com.cw.vlainter.domain.payment.repository.PointLedgerRowProjection {
            override fun getOccurredAt(): OffsetDateTime = occurredAt
            override fun getPointDelta(): Long = pointDelta
            override fun getDescription(): String = description
        }

    private class NoOpTransactionManager : org.springframework.transaction.PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }

    private fun createUser(
        id: Long = 1L,
        email: String = "user@vlainter.com",
        password: String = "encoded-password",
        name: String = "Tester",
        status: UserStatus = UserStatus.ACTIVE,
        role: UserRole = UserRole.USER,
        point: Long = 0L
    ): User {
        return User(
            id = id,
            email = email,
            password = password,
            name = name,
            status = status,
            role = role,
            point = point
        )
    }

    private fun createPrincipal(user: User): AuthPrincipal {
        return AuthPrincipal(
            userId = user.id,
            email = user.email,
            sessionId = "sid-${user.id}",
            role = user.role
        )
    }
}
