package com.cw.vlainter.domain.interview.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class JobSummaryResponse(
    val jobId: Long,
    val name: String
)

data class SkillSummaryResponse(
    val skillId: Long,
    val jobId: Long,
    val jobName: String,
    val name: String
)

data class CreateJobRequest(
    @field:NotBlank(message = "직무명은 필수입니다.")
    @field:Size(max = 120, message = "직무명은 120자 이하여야 합니다.")
    val name: String
)

data class CreateSkillRequest(
    @field:NotBlank(message = "직무명은 필수입니다.")
    @field:Size(max = 120, message = "직무명은 120자 이하여야 합니다.")
    val jobName: String,

    @field:NotBlank(message = "기술명은 필수입니다.")
    @field:Size(max = 120, message = "기술명은 120자 이하여야 합니다.")
    val skillName: String
)
