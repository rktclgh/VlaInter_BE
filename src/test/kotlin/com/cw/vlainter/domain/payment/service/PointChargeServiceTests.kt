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
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
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
        given(pointChargeRepository.findByImpUid("imp-1")).willReturn(Optional.empty())
        given(portoneClient.getPayment("imp-1")).willReturn(
            PortonePayment(
                impUid = "imp-1",
                merchantUid = "merchant-1",
                status = "paid",
                amount = BigDecimal("10000")
            )
        )
        given(userRepository.save(user)).willReturn(user)
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
        given(pointChargeRepository.findByImpUid("imp-2")).willReturn(Optional.empty())
        given(portoneClient.getPayment("imp-2")).willReturn(
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
            )
        )
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
