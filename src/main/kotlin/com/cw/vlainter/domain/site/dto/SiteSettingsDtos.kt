package com.cw.vlainter.domain.site.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class PublicSiteSettingsResponse(
    val landingVersionLabel: String
)

data class AdminSiteSettingsResponse(
    val landingVersionLabel: String,
    val updatedAt: OffsetDateTime?
)

data class UpdateAdminSiteSettingsRequest(
    @field:NotBlank(message = "버전 라벨을 입력해 주세요.")
    @field:Size(max = 100, message = "버전 라벨은 100자 이하여야 합니다.")
    val landingVersionLabel: String
)
