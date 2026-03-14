package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.AdminInterviewSettingsResponse
import com.cw.vlainter.domain.interview.dto.UpdateAdminInterviewSettingsRequest
import com.cw.vlainter.domain.interview.entity.AdminInterviewSetting
import com.cw.vlainter.domain.interview.entity.TechQuestionReusePolicy
import com.cw.vlainter.domain.interview.repository.AdminInterviewSettingRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.global.security.AuthPrincipal
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class AdminInterviewSettingsService(
    private val adminInterviewSettingRepository: AdminInterviewSettingRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getSettings(principal: AuthPrincipal): AdminInterviewSettingsResponse {
        ensureAdmin(principal)
        val saved = adminInterviewSettingRepository.findById(TECH_QUESTION_REUSE_POLICY_KEY).orElse(null)
        return AdminInterviewSettingsResponse(
            techQuestionReusePolicy = saved?.toTechQuestionReusePolicy() ?: DEFAULT_TECH_QUESTION_REUSE_POLICY,
            updatedAt = saved?.updatedAt
        )
    }

    @Transactional
    fun updateSettings(
        principal: AuthPrincipal,
        request: UpdateAdminInterviewSettingsRequest
    ): AdminInterviewSettingsResponse {
        ensureAdmin(principal)
        val saved = adminInterviewSettingRepository.findById(TECH_QUESTION_REUSE_POLICY_KEY)
            .map {
                it.settingValue = request.techQuestionReusePolicy.name
                it
            }
            .orElseGet {
                AdminInterviewSetting(
                    settingKey = TECH_QUESTION_REUSE_POLICY_KEY,
                    settingValue = request.techQuestionReusePolicy.name
                )
            }
        val updated = adminInterviewSettingRepository.save(saved)
        return AdminInterviewSettingsResponse(
            techQuestionReusePolicy = updated.toTechQuestionReusePolicy(),
            updatedAt = updated.updatedAt
        )
    }

    @Transactional(readOnly = true)
    fun getTechQuestionReusePolicy(): TechQuestionReusePolicy {
        val saved = adminInterviewSettingRepository.findById(TECH_QUESTION_REUSE_POLICY_KEY).orElse(null)
        return saved?.toTechQuestionReusePolicy() ?: DEFAULT_TECH_QUESTION_REUSE_POLICY
    }

    private fun AdminInterviewSetting.toTechQuestionReusePolicy(): TechQuestionReusePolicy {
        return runCatching { TechQuestionReusePolicy.valueOf(settingValue) }
            .getOrElse { ex ->
                logger.warn(
                    "Unknown tech question reuse policy settingValue={}, fallback={}",
                    settingValue,
                    DEFAULT_TECH_QUESTION_REUSE_POLICY,
                    ex
                )
                DEFAULT_TECH_QUESTION_REUSE_POLICY
            }
    }

    private fun ensureAdmin(principal: AuthPrincipal) {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
    }

    companion object {
        const val TECH_QUESTION_REUSE_POLICY_KEY = "tech_question_reuse_policy"
        val DEFAULT_TECH_QUESTION_REUSE_POLICY: TechQuestionReusePolicy = TechQuestionReusePolicy.ALWAYS_GENERATE
    }
}
