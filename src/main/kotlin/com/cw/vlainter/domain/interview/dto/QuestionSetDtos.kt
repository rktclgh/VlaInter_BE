package com.cw.vlainter.domain.interview.dto

import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetStatus
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class CreateQuestionSetRequest(
    @field:NotBlank(message = "질문 세트 제목은 필수입니다.")
    @field:Size(max = 200, message = "질문 세트 제목은 200자 이하여야 합니다.")
    val title: String,
    @field:Size(max = 120, message = "계열명은 120자 이하여야 합니다.")
    val branchName: String? = null,
    @field:Size(max = 120, message = "직무명은 120자 이하여야 합니다.")
    val jobName: String? = null,
    @field:Size(max = 120, message = "기술명은 120자 이하여야 합니다.")
    val skillName: String? = null,
    @field:Size(max = 2000, message = "질문 세트 설명은 2000자 이하여야 합니다.")
    val description: String? = null,
    val visibility: QuestionSetVisibility = QuestionSetVisibility.PRIVATE
)

data class UpdateQuestionSetRequest(
    @field:Size(max = 200, message = "질문 세트 제목은 200자 이하여야 합니다.")
    val title: String? = null,
    @field:Size(max = 2000, message = "질문 세트 설명은 2000자 이하여야 합니다.")
    val description: String? = null,
    val visibility: QuestionSetVisibility? = null,
    val status: QuestionSetStatus? = null
)

data class AddQuestionToSetRequest(
    @field:NotBlank(message = "질문은 필수입니다.")
    val questionText: String,
    val canonicalAnswer: String? = null,
    val categoryId: Long? = null,
    @field:NotBlank(message = "직무명은 필수입니다.")
    @field:Size(max = 120, message = "직무명은 120자 이하여야 합니다.")
    val jobName: String,
    @field:NotBlank(message = "기술명은 필수입니다.")
    @field:Size(max = 120, message = "기술명은 120자 이하여야 합니다.")
    val skillName: String,
    val difficulty: QuestionDifficulty,
    val tags: List<String> = emptyList()
)

data class QuestionSetSummaryResponse(
    val setId: Long,
    val title: String,
    val description: String?,
    val branchName: String?,
    val jobName: String?,
    val jobNames: List<String>,
    val skillName: String?,
    val skillNames: List<String>,
    val ownerName: String?,
    val ownerType: QuestionSetOwnerType,
    val visibility: QuestionSetVisibility,
    val status: QuestionSetStatus,
    val questionCount: Int,
    val certified: Boolean,
    val aiGenerated: Boolean,
    val isPromoted: Boolean,
    val createdAt: OffsetDateTime?
)

data class AdminQuestionSetSummaryResponse(
    val setId: Long,
    val title: String,
    val description: String?,
    val branchName: String?,
    val jobName: String?,
    val jobNames: List<String>,
    val skillName: String?,
    val skillNames: List<String>,
    val ownerUserId: Long?,
    val ownerName: String?,
    val ownerType: QuestionSetOwnerType,
    val visibility: QuestionSetVisibility,
    val status: QuestionSetStatus,
    val questionCount: Int,
    val certified: Boolean,
    val aiGenerated: Boolean,
    val isPromoted: Boolean,
    val createdAt: OffsetDateTime?
)

data class QuestionSummaryResponse(
    val questionId: Long,
    val questionText: String,
    val canonicalAnswer: String?,
    val modelAnswer: String? = null,
    val bestPractice: String? = null,
    val categoryId: Long,
    val categoryName: String,
    val jobName: String?,
    val skillName: String?,
    val difficulty: QuestionDifficulty,
    val sourceTag: QuestionSourceTag,
    val tags: List<String>
)

data class UpdateQuestionInSetRequest(
    @field:NotBlank(message = "질문은 필수입니다.")
    val questionText: String,
    val canonicalAnswer: String? = null,
    val categoryId: Long? = null,
    @field:NotBlank(message = "직무명은 필수입니다.")
    @field:Size(max = 120, message = "직무명은 120자 이하여야 합니다.")
    val jobName: String,
    @field:NotBlank(message = "기술명은 필수입니다.")
    @field:Size(max = 120, message = "기술명은 120자 이하여야 합니다.")
    val skillName: String,
    val difficulty: QuestionDifficulty,
    val tags: List<String> = emptyList()
)
