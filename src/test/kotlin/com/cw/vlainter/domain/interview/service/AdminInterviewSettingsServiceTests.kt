@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.UpdateAdminInterviewSettingsRequest
import com.cw.vlainter.domain.interview.entity.TechQuestionReusePolicy
import com.cw.vlainter.domain.interview.repository.AdminInterviewSettingRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.global.security.AuthPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AdminInterviewSettingsServiceTests {

    @Mock
    private lateinit var adminInterviewSettingRepository: AdminInterviewSettingRepository

    @Test
    fun `저장값이 없으면 기본 정책은 ALWAYS_GENERATE를 반환한다`() {
        given(adminInterviewSettingRepository.findById(AdminInterviewSettingsService.TECH_QUESTION_REUSE_POLICY_KEY))
            .willReturn(Optional.empty())

        val result = service().getSettings(adminPrincipal())

        assertThat(result.techQuestionReusePolicy).isEqualTo(TechQuestionReusePolicy.ALWAYS_GENERATE)
        assertThat(result.updatedAt).isNull()
    }

    @Test
    fun `관리자는 질문 재사용 정책을 변경할 수 있다`() {
        given(adminInterviewSettingRepository.findById(AdminInterviewSettingsService.TECH_QUESTION_REUSE_POLICY_KEY))
            .willReturn(Optional.empty())
        given(adminInterviewSettingRepository.save(anyNonNull()))
            .willAnswer { it.getArgument(0) }

        val result = service().updateSettings(
            adminPrincipal(),
            UpdateAdminInterviewSettingsRequest(techQuestionReusePolicy = TechQuestionReusePolicy.REUSE_MATCHING)
        )

        assertThat(result.techQuestionReusePolicy).isEqualTo(TechQuestionReusePolicy.REUSE_MATCHING)
        then(adminInterviewSettingRepository).should().save(anyNonNull())
    }

    @Test
    fun `관리자가 아니면 설정을 변경할 수 없다`() {
        val ex = assertThrows(ResponseStatusException::class.java) {
            service().updateSettings(
                userPrincipal(),
                UpdateAdminInterviewSettingsRequest(techQuestionReusePolicy = TechQuestionReusePolicy.REUSE_MATCHING)
            )
        }

        assertThat(ex.statusCode.value()).isEqualTo(403)
    }

    private fun service(): AdminInterviewSettingsService = AdminInterviewSettingsService(adminInterviewSettingRepository)

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        return null as T
    }

    private fun adminPrincipal() = AuthPrincipal(1L, "admin@vlainter.com", "S", UserRole.ADMIN)

    private fun userPrincipal() = AuthPrincipal(2L, "user@vlainter.com", "S", UserRole.USER)
}
