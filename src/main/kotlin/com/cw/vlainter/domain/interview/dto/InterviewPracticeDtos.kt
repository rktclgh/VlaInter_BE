package com.cw.vlainter.domain.interview.dto

import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.InterviewQuestionKind
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.entity.TurnSourceTag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime

data class StartTechInterviewRequest(
    val setId: Long? = null,
    val categoryId: Long? = null,
    val jobName: String? = null,
    val skillName: String? = null,
    val difficulty: QuestionDifficulty? = null,
    val language: InterviewLanguage = InterviewLanguage.KO,
    val sourceTag: QuestionSourceTag? = null,
    val saveHistory: Boolean = true,
    @field:Min(value = 1, message = "questionCount는 1 이상이어야 합니다.")
    @field:Max(value = 20, message = "questionCount는 20 이하여야 합니다.")
    val questionCount: Int = 5
)

data class InterviewQuestionResponse(
    val turnId: Long,
    val turnNo: Int,
    val questionId: Long?,
    val documentQuestionId: Long?,
    val questionKind: InterviewQuestionKind,
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
    val hasNext: Boolean,
    val language: String = InterviewLanguage.KO.name,
    val providerUsed: String? = null,
    val fallbackDepth: Int = 0
)

data class SubmitInterviewAnswerRequest(
    @field:NotBlank(message = "답변은 비어 있을 수 없습니다.")
    @field:Size(max = 2000, message = "답변은 2000자 이하여야 합니다.")
    val answer: String
)

data class TurnEvaluationResponse(
    val score: BigDecimal,
    val feedback: String,
    val bestPractice: String,
    val modelAnswer: String? = null,
    val providerUsed: String? = null
)

data class SubmitInterviewAnswerResponse(
    val sessionId: Long,
    val answeredTurnId: Long,
    val submittedAnswer: String,
    val evaluation: TurnEvaluationResponse? = null,
    val nextQuestion: InterviewQuestionResponse? = null,
    val completed: Boolean
)

data class BookmarkTurnRequest(
    val note: String? = null
)

data class SavedQuestionResponse(
    val savedQuestionId: Long,
    val questionId: Long?,
    val documentQuestionId: Long?,
    val questionKind: InterviewQuestionKind,
    val categoryId: Long?,
    val questionText: String,
    val canonicalAnswer: String?,
    val modelAnswer: String? = null,
    val bestPractice: String? = null,
    val feedback: String? = null,
    val answerText: String?,
    val branchName: String? = null,
    val jobName: String? = null,
    val skillName: String? = null,
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

data class InterviewTurnResultResponse(
    val turnId: Long,
    val turnNo: Int,
    val questionId: Long?,
    val documentQuestionId: Long?,
    val questionKind: InterviewQuestionKind,
    val categoryId: Long?,
    val questionText: String,
    val answerText: String?,
    val category: String?,
    val difficulty: String?,
    val sourceTag: TurnSourceTag,
    val tags: List<String>,
    val bookmarked: Boolean,
    val evaluation: TurnEvaluationResponse? = null
)

data class InterviewSessionResultsResponse(
    val sessionId: Long,
    val status: String,
    val mode: String,
    val finishedAt: OffsetDateTime?,
    val turns: List<InterviewTurnResultResponse>
)

data class InterviewHistoryDocumentResponse(
    val fileId: Long?,
    val fileType: String?,
    val label: String,
    val ocrUsed: Boolean = false
)

data class InterviewSessionHistoryResponse(
    val sessionId: Long,
    val status: String,
    val mode: String,
    val language: String?,
    val questionCount: Int,
    val difficulty: String?,
    val difficultyRating: Int?,
    val categoryId: Long?,
    val categoryName: String?,
    val jobName: String?,
    val selectedDocuments: List<InterviewHistoryDocumentResponse>,
    val startedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?
)

data class InterviewSessionHistoryPageResponse(
    val items: List<InterviewSessionHistoryResponse>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean
)

data class ResumeInterviewSessionResponse(
    val sessionId: Long,
    val status: String,
    val mode: String,
    val language: String?,
    val currentQuestion: InterviewQuestionResponse,
    val questionCount: Int,
    val difficulty: String?,
    val difficultyRating: Int?,
    val categoryId: Long?,
    val categoryName: String?,
    val jobName: String?,
    val selectedDocuments: List<InterviewHistoryDocumentResponse>,
    val questionSetId: Long?,
    val includeSelfIntroduction: Boolean,
    val providerUsed: String? = null,
    val fallbackDepth: Int = 0
)
