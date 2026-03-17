package com.cw.vlainter.domain.student.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import jakarta.validation.Valid
import com.cw.vlainter.domain.student.entity.StudentExamSessionStatus
import com.cw.vlainter.domain.userFile.entity.FileType
import java.time.OffsetDateTime

data class CreateStudentCourseRequest(
    @field:NotBlank(message = "과목명을 입력해 주세요.")
    @field:Size(max = 160, message = "과목명은 160자 이하여야 합니다.")
    val courseName: String,

    @field:Size(max = 120, message = "교수명은 120자 이하여야 합니다.")
    val professorName: String? = null,

    @field:Size(max = 3000, message = "과목 설명은 3000자 이하여야 합니다.")
    val description: String? = null
)

data class StudentCourseResponse(
    val courseId: Long,
    val universityName: String,
    val departmentName: String,
    val courseName: String,
    val professorName: String?,
    val description: String?,
    val materialCount: Int = 0,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)

data class StudentCourseMaterialResponse(
    val materialId: Long,
    val fileId: Long,
    val fileType: FileType,
    val materialKind: StudentCourseMaterialKind,
    val fileName: String,
    val originalFileName: String?,
    val fileUrl: String,
    val createdAt: OffsetDateTime,
    val ingestionStatus: String? = null,
    val ingested: Boolean = false,
    val errorMessage: String? = null,
    val extractionMethod: String? = null,
    val ocrUsed: Boolean = false,
    val visualAssets: List<StudentCourseMaterialVisualAssetResponse> = emptyList()
)

data class StudentCourseMaterialVisualAssetResponse(
    val assetId: Long,
    val assetType: StudentCourseMaterialVisualAssetType,
    val assetOrder: Int,
    val label: String,
    val pageNo: Int? = null,
    val slideNo: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val downloadUrl: String
)

enum class StudentCourseMaterialKind {
    LECTURE_MATERIAL,
    PAST_EXAM
}

enum class StudentCourseMaterialVisualAssetType {
    PDF_PAGE_RENDER,
    PPT_SLIDE_RENDER,
    DOCX_EMBEDDED_IMAGE,
    ORIGINAL_IMAGE
}

data class StudentCourseMaterialDownloadResponse(
    val downloadUrl: String,
    val expiresInSeconds: Long
)

data class CreateStudentExamSessionRequest(
    @field:Min(value = 3, message = "문항 수는 3개 이상이어야 합니다.")
    @field:Max(value = 20, message = "문항 수는 20개 이하여야 합니다.")
    val questionCount: Int = 5,

    val generationMode: StudentExamGenerationMode = StudentExamGenerationMode.STANDARD,

    @field:Min(value = 1, message = "난이도는 1 이상이어야 합니다.")
    @field:Max(value = 5, message = "난이도는 5 이하여야 합니다.")
    val difficultyLevel: Int? = null,

    @field:Size(max = 5, message = "문제 스타일은 최대 5개까지 선택할 수 있습니다.")
    val questionStyles: List<StudentExamQuestionStyle> = emptyList()
)

enum class StudentExamGenerationMode {
    STANDARD,
    PAST_EXAM,
    PAST_EXAM_PRACTICE,
    WRONG_ANSWER_RETEST
}

enum class StudentExamQuestionStyle {
    DEFINITION,
    CODING,
    CALCULATION,
    ESSAY,
    PRACTICAL
}

data class StudentExamSessionResponse(
    val sessionId: Long,
    val courseId: Long,
    val title: String,
    val status: StudentExamSessionStatus,
    val generationMode: StudentExamGenerationMode,
    val difficultyLevel: Int?,
    val questionStyles: List<StudentExamQuestionStyle>,
    val questionCount: Int,
    val maxScore: Int,
    val sourceMaterialCount: Int,
    val answeredCount: Int,
    val totalScore: Int?,
    val submittedAt: OffsetDateTime?,
    val previewQuestions: List<String>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)

data class StudentExamQuestionResponse(
    val questionId: Long,
    val questionOrder: Int,
    val questionStyle: StudentExamQuestionStyle,
    val questionText: String,
    val canonicalAnswer: String,
    val gradingCriteria: String,
    val referenceExample: String?,
    val maxScore: Int,
    val answerText: String?,
    val score: Int?,
    val feedback: String?,
    val isCorrect: Boolean?,
    val answeredAt: OffsetDateTime?
)

data class StudentExamSessionDetailResponse(
    val sessionId: Long,
    val courseId: Long,
    val title: String,
    val status: StudentExamSessionStatus,
    val generationMode: StudentExamGenerationMode,
    val difficultyLevel: Int?,
    val questionStyles: List<StudentExamQuestionStyle>,
    val questionCount: Int,
    val maxScore: Int,
    val sourceMaterialCount: Int,
    val answeredCount: Int,
    val totalScore: Int?,
    val submittedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val questions: List<StudentExamQuestionResponse>
)

data class StudentWrongAnswerSetResponse(
    val setId: Long,
    val sessionId: Long,
    val courseId: Long,
    val title: String,
    val questionCount: Int,
    val retestSessionId: Long?,
    val previewQuestions: List<String>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)

data class StudentWrongAnswerSetDetailResponse(
    val setId: Long,
    val sessionId: Long,
    val courseId: Long,
    val title: String,
    val questionCount: Int,
    val retestSessionId: Long?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val items: List<StudentWrongAnswerItemResponse>
)

data class StudentWrongAnswerItemResponse(
    val questionId: Long,
    val questionOrder: Int,
    val questionStyle: StudentExamQuestionStyle,
    val questionText: String,
    val canonicalAnswer: String,
    val gradingCriteria: String,
    val referenceExample: String?,
    val maxScore: Int,
    val answerText: String?,
    val score: Int?,
    val feedback: String?
)

data class SubmitStudentExamAnswersRequest(
    @field:Valid
    val answers: List<StudentExamAnswerRequest>
)

data class CreateStudentWrongAnswerSetRequest(
    @field:Size(max = 200, message = "오답노트 제목은 200자 이하여야 합니다.")
    val title: String? = null,

    @field:Size(min = 1, max = 20, message = "저장할 문제를 1개 이상 선택해 주세요.")
    val questionIds: List<Long>
)

data class StudentExamAnswerRequest(
    val questionId: Long,

    @field:Size(max = 5000, message = "답안은 5000자 이하여야 합니다.")
    val answerText: String
)
