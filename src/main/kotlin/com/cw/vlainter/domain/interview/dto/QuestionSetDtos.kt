package com.cw.vlainter.domain.interview.dto

import com.cw.vlainter.domain.interview.entity.EmbeddingStatus
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class CreateQuestionSetRequest(
    @field:NotBlank(message = "질문 세트 제목은 필수입니다.")
    @field:Size(max = 200, message = "질문 세트 제목은 200자 이하여야 합니다.")
    val title: String,
    @field:Size(max = 2000, message = "질문 세트 설명은 2000자 이하여야 합니다.")
    val description: String? = null,
    val visibility: QuestionSetVisibility = QuestionSetVisibility.PRIVATE
)

data class AddQuestionToSetRequest(
    @field:NotBlank(message = "질문은 필수입니다.")
    val questionText: String,
    val canonicalAnswer: String? = null,
    val categoryId: Long,
    val difficulty: QuestionDifficulty,
    val tags: List<String> = emptyList()
)

data class QuestionSetSummaryResponse(
    val setId: Long,
    val title: String,
    val description: String?,
    val ownerType: QuestionSetOwnerType,
    val visibility: QuestionSetVisibility,
    val embeddingStatus: EmbeddingStatus,
    val questionCount: Int,
    val isPromoted: Boolean,
    val createdAt: OffsetDateTime?
)

data class QuestionSummaryResponse(
    val questionId: Long,
    val questionText: String,
    val canonicalAnswer: String?,
    val categoryId: Long,
    val categoryName: String,
    val categoryPath: String,
    val difficulty: QuestionDifficulty,
    val sourceTag: QuestionSourceTag,
    val tags: List<String>
)

data class EmbeddingRequestResponse(
    val setId: Long,
    val embeddingStatus: EmbeddingStatus,
    val message: String
)
