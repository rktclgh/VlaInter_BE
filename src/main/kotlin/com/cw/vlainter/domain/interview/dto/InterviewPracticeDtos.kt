package com.cw.vlainter.domain.interview.dto

import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.OffsetDateTime

data class StartTechInterviewRequest(
    val setId: Long? = null,
    val categoryId: Long? = null,
    val difficulty: QuestionDifficulty? = null,
    val sourceTag: QuestionSourceTag? = null,
    @field:Min(value = 1, message = "questionCount는 1 이상이어야 합니다.")
    @field:Max(value = 20, message = "questionCount는 20 이하여야 합니다.")
    val questionCount: Int = 5
)

data class InterviewQuestionResponse(
    val turnId: Long,
    val turnNo: Int,
    val questionId: Long?,
    val categoryId: Long?,
    val questionText: String,
    val sourceTag: TurnSourceTag,
    val category: String?,
    val difficulty: String?,
    val tags: List<String>
)

data class StartTechInterviewResponse(
    val sessionId: Long,
    val status: String,
    val currentQuestion: InterviewQuestionResponse,
    val hasNext: Boolean
)

data class SubmitInterviewAnswerRequest(
    @field:NotBlank(message = "답변은 비어 있을 수 없습니다.")
    val answer: String
)

data class TurnEvaluationResponse(
    val score: BigDecimal,
    val feedback: String,
    val bestPractice: String
)

data class SubmitInterviewAnswerResponse(
    val sessionId: Long,
    val answeredTurnId: Long,
    val evaluation: TurnEvaluationResponse,
    val nextQuestion: InterviewQuestionResponse? = null,
    val completed: Boolean
)

data class BookmarkTurnRequest(
    val note: String? = null
)

data class SavedQuestionResponse(
    val savedQuestionId: Long,
    val questionId: Long?,
    val categoryId: Long?,
    val questionText: String,
    val category: String?,
    val difficulty: String?,
    val sourceTag: String?,
    val tags: List<String>,
    val note: String?,
    val createdAt: OffsetDateTime
)

data class QuestionAttemptResponse(
    val attemptId: Long,
    val sessionId: Long?,
    val turnId: Long?,
    val answerText: String,
    val score: BigDecimal?,
    val feedbackSummary: String?,
    val createdAt: OffsetDateTime
)
