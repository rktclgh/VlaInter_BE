package com.cw.vlainter.domain.user.controller

import com.cw.vlainter.domain.user.dto.AdminMemberDetailResponse
import com.cw.vlainter.domain.user.dto.AdminMemberListResponse
import com.cw.vlainter.domain.user.dto.AdminMemberSummaryResponse
import com.cw.vlainter.domain.user.dto.UpdateMemberByAdminRequest
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.service.UserService
import com.cw.vlainter.global.security.AuthPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willDoNothing
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.time.OffsetDateTime

@ExtendWith(MockitoExtension::class)
class AdminUserControllerTests {

    @Mock
    private lateinit var userService: UserService

    @Test
    fun `get members returns notion path spec response`() {
        val principal = adminPrincipal()
        val responsePayload = AdminMemberListResponse(
            totalCount = 1,
            members = listOf(
                AdminMemberSummaryResponse(
                    memberId = 10L,
                    email = "member@vlainter.com",
                    name = "Member",
                    status = UserStatus.ACTIVE,
                    role = UserRole.USER,
                    createdAt = OffsetDateTime.parse("2026-03-04T10:00:00+09:00")
                )
            )
        )
        given(userService.getMembersByAdmin(principal, 0, 20)).willReturn(responsePayload)

        val response = AdminUserController(userService).getMembers(principal, 0, 20)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isEqualTo(responsePayload)
        then(userService).should().getMembersByAdmin(principal, 0, 20)
    }

    @Test
    fun getMemberReturnsDetailResponse() {
        val principal = adminPrincipal()
        val payload = AdminMemberDetailResponse(
            memberId = 20L,
            email = "member2@vlainter.com",
            name = "Member2",
            status = UserStatus.BLOCKED,
            role = UserRole.USER,
            point = 0L,
            free = 0,
            createdAt = OffsetDateTime.parse("2026-03-01T10:00:00+09:00"),
            updatedAt = OffsetDateTime.parse("2026-03-04T10:00:00+09:00")
        )
        given(userService.getMemberByAdmin(principal, 20L)).willReturn(payload)

        val response = AdminUserController(userService).getMember(principal, 20L)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isEqualTo(payload)
        then(userService).should().getMemberByAdmin(principal, 20L)
    }

    @Test
    fun updateMemberCallsAdminPatchService() {
        val principal = adminPrincipal()
        val request = UpdateMemberByAdminRequest(status = UserStatus.BLOCKED, role = UserRole.ADMIN)
        val payload = AdminMemberDetailResponse(
            memberId = 20L,
            email = "member2@vlainter.com",
            name = "Member2",
            status = UserStatus.BLOCKED,
            role = UserRole.ADMIN,
            point = 100L,
            free = 1,
            createdAt = OffsetDateTime.parse("2026-03-01T10:00:00+09:00"),
            updatedAt = OffsetDateTime.parse("2026-03-04T10:00:00+09:00")
        )
        given(userService.updateMemberByAdmin(principal, 20L, request)).willReturn(payload)

        val response = AdminUserController(userService).updateMember(principal, 20L, request)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.role).isEqualTo(UserRole.ADMIN)
        then(userService).should().updateMemberByAdmin(principal, 20L, request)
    }

    @Test
    fun hardDeleteMemberReturnsSuccessMessage() {
        val principal = adminPrincipal()
        willDoNothing().given(userService).hardDeleteMemberByAdmin(principal, 50L)

        val response = AdminUserController(userService).hardDeleteMember(principal, 50L)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.get("message")).isEqualTo("User has been permanently deleted.")
        then(userService).should().hardDeleteMemberByAdmin(principal, 50L)
    }

    private fun adminPrincipal(): AuthPrincipal {
        return AuthPrincipal(
            userId = 999L,
            email = "admin@vlainter.com",
            sessionId = "session-999"
        )
    }
}
