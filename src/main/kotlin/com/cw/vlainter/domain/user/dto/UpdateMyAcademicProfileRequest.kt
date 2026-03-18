package com.cw.vlainter.domain.user.dto

import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class UpdateMyAcademicProfileRequest(
    @field:Size(max = 120, message = "대학교 이름은 120자 이하여야 합니다.")
    val universityName: String? = null,
    @field:Positive(message = "아이디는 양수여야 합니다.")
    val universityId: Long? = null,
    @field:Size(max = 120, message = "학과 이름은 120자 이하여야 합니다.")
    val departmentName: String? = null,
    @field:Positive(message = "아이디는 양수여야 합니다.")
    val departmentId: Long? = null
)
