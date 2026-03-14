package com.cw.vlainter.domain.site.service

import com.cw.vlainter.domain.interview.entity.AdminInterviewSetting
import com.cw.vlainter.domain.interview.repository.AdminInterviewSettingRepository
import com.cw.vlainter.domain.site.dto.AdminSiteSettingsResponse
import com.cw.vlainter.domain.site.dto.PublicSiteSettingsResponse
import com.cw.vlainter.domain.site.dto.UpdateAdminSiteSettingsRequest
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class SiteSettingsService(
    private val adminInterviewSettingRepository: AdminInterviewSettingRepository
) {
    @Transactional(readOnly = true)
    fun getPublicSettings(): PublicSiteSettingsResponse {
        return PublicSiteSettingsResponse(
            landingVersionLabel = getLandingVersionLabel()
        )
    }

    @Transactional(readOnly = true)
    fun getAdminSettings(principal: AuthPrincipal): AdminSiteSettingsResponse {
        ensureAdmin(principal)
        val saved = adminInterviewSettingRepository.findById(LANDING_VERSION_LABEL_KEY).orElse(null)
        return AdminSiteSettingsResponse(
            landingVersionLabel = saved?.settingValue ?: DEFAULT_LANDING_VERSION_LABEL,
            updatedAt = saved?.updatedAt
        )
    }

    @Transactional
    fun updateAdminSettings(
        principal: AuthPrincipal,
        request: UpdateAdminSiteSettingsRequest
    ): AdminSiteSettingsResponse {
        ensureAdmin(principal)
        val normalizedLabel = request.landingVersionLabel.trim().ifBlank {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "버전 라벨을 입력해 주세요.")
        }
        val saved = adminInterviewSettingRepository.findById(LANDING_VERSION_LABEL_KEY)
            .map {
                it.settingValue = normalizedLabel
                it
            }
            .orElseGet {
                AdminInterviewSetting(
                    settingKey = LANDING_VERSION_LABEL_KEY,
                    settingValue = normalizedLabel
                )
            }
        val updated = adminInterviewSettingRepository.save(saved)
        return AdminSiteSettingsResponse(
            landingVersionLabel = updated.settingValue,
            updatedAt = updated.updatedAt
        )
    }

    @Transactional(readOnly = true)
    fun getLandingVersionLabel(): String {
        val saved = adminInterviewSettingRepository.findById(LANDING_VERSION_LABEL_KEY).orElse(null)
        return saved?.settingValue ?: DEFAULT_LANDING_VERSION_LABEL
    }

    private fun ensureAdmin(principal: AuthPrincipal) {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근할 수 있습니다.")
        }
    }

    companion object {
        const val LANDING_VERSION_LABEL_KEY = "landing_version_label"
        const val DEFAULT_LANDING_VERSION_LABEL = "v0.5"
    }
}
