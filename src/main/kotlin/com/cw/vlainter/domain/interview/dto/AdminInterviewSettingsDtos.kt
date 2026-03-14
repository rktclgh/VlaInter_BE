package com.cw.vlainter.domain.interview.dto

import com.cw.vlainter.domain.interview.entity.TechQuestionReusePolicy
import java.time.OffsetDateTime

data class AdminInterviewSettingsResponse(
    val techQuestionReusePolicy: TechQuestionReusePolicy,
    val updatedAt: OffsetDateTime?
)

data class UpdateAdminInterviewSettingsRequest(
    val techQuestionReusePolicy: TechQuestionReusePolicy
)
