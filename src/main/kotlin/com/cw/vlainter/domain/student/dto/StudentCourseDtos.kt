package com.cw.vlainter.domain.student.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
