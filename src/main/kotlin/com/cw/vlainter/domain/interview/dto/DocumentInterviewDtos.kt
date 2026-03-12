package com.cw.vlainter.domain.interview.dto

import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class DocumentIngestionResponse(
    val jobId: Long,
    val fileId: Long,
    val status: DocumentIngestionStatus,
    val chunkCount: Int?,
    val extractionMethod: String? = null,
    val ocrUsed: Boolean = false,
    val requestedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val errorMessage: String? = null
)

data class ReadyDocumentResponse(
    val fileId: Long,
    val fileName: String,
    val fileType: String,
    val status: DocumentIngestionStatus,
    val chunkCount: Int?,
    val extractionMethod: String? = null,
    val ocrUsed: Boolean = false,
    val lastIngestedAt: OffsetDateTime?
)

data class StartMockInterviewRequest(
    @field:NotEmpty(message = "documentFileIds는 1개 이상이어야 합니다.")
    val documentFileIds: List<Long>,
    val questionSetId: Long? = null,
    @field:Size(max = 3, message = "기술 카테고리는 최대 3개까지 선택할 수 있습니다.")
    val categoryIds: List<Long> = emptyList(),
    @field:Size(max = 3, message = "기술 스킬 이름은 최대 3개까지 입력할 수 있습니다.")
    val skillNames: List<String> = emptyList(),
    val jobName: String? = null,
    val difficulty: QuestionDifficulty? = null,
    val language: InterviewLanguage = InterviewLanguage.KO,
    val includeSelfIntroduction: Boolean = false,
    @field:Min(value = 5, message = "questionCount는 5 이상이어야 합니다.")
    @field:Max(value = 20, message = "questionCount는 20 이하여야 합니다.")
    val questionCount: Int = 5
)
