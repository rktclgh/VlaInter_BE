package com.cw.vlainter.domain.user.dto

import jakarta.validation.constraints.Size

data class UpdateMyAcademicProfileRequest(
    @field:Size(max = 120, message = "대학교 이름은 120자 이하여야 합니다.")
    val universityName: String? = null,
    val universityId: Long? = null,
    @field:Size(max = 120, message = "학과 이름은 120자 이하여야 합니다.")
    val departmentName: String? = null,
    val departmentId: Long? = null
)
