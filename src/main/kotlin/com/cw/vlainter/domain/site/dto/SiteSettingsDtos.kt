package com.cw.vlainter.domain.site.dto

import java.time.OffsetDateTime

data class PublicSiteSettingsResponse(
    val landingVersionLabel: String
)

data class AdminSiteSettingsResponse(
    val landingVersionLabel: String,
    val updatedAt: OffsetDateTime?
)

data class UpdateAdminSiteSettingsRequest(
    val landingVersionLabel: String
)
