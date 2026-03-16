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
    val fileName: String,
    val originalFileName: String?,
    val fileUrl: String,
    val createdAt: OffsetDateTime
)

data class CreateStudentExamSessionRequest(
    @field:Min(value = 5, message = "문항 수는 5개 이상이어야 합니다.")
    @field:Max(value = 20, message = "문항 수는 20개 이하여야 합니다.")
    val questionCount: Int = 5
)

data class StudentExamSessionResponse(
    val sessionId: Long,
    val courseId: Long,
    val title: String,
    val status: StudentExamSessionStatus,
    val questionCount: Int,
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
    val questionText: String,
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
    val questionCount: Int,
    val sourceMaterialCount: Int,
    val answeredCount: Int,
    val totalScore: Int?,
    val submittedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val questions: List<StudentExamQuestionResponse>
)

data class SubmitStudentExamAnswersRequest(
    @field:Valid
    val answers: List<StudentExamAnswerRequest>
)

data class StudentExamAnswerRequest(
    val questionId: Long,

    @field:Size(max = 5000, message = "답안은 5000자 이하여야 합니다.")
    val answerText: String
)
