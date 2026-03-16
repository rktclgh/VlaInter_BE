package com.cw.vlainter.domain.student.service

import com.cw.vlainter.domain.student.dto.CreateStudentCourseRequest
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialResponse
import com.cw.vlainter.domain.student.dto.StudentCourseResponse
import com.cw.vlainter.domain.student.entity.StudentCourse
import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
import com.cw.vlainter.domain.student.repository.StudentCourseRepository
import com.cw.vlainter.domain.user.entity.UserServiceMode
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.service.UserFileService
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@Service
class StudentCourseService(
    private val studentCourseRepository: StudentCourseRepository,
    private val studentCourseMaterialRepository: StudentCourseMaterialRepository,
    private val userRepository: UserRepository,
    private val userFileService: UserFileService
) {
    @Transactional(readOnly = true)
    fun getMyCourses(principal: AuthPrincipal): List<StudentCourseResponse> {
        val user = getValidatedStudentUser(principal)
        return studentCourseRepository.findAllByUserIdAndIsArchivedFalseOrderByUpdatedAtDesc(user.id)
            .map { it.toResponse() }
    }

    @Transactional
    fun createCourse(principal: AuthPrincipal, request: CreateStudentCourseRequest): StudentCourseResponse {
        val user = getValidatedStudentUser(principal)
        val normalizedCourseName = request.courseName.trim()
        val normalizedProfessorName = request.professorName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedDescription = request.description?.trim()?.takeIf { it.isNotBlank() }

        val duplicateExists = studentCourseRepository
            .findAllByUserIdAndIsArchivedFalseOrderByUpdatedAtDesc(user.id)
            .any { course ->
                course.universityName.equals(user.universityName, ignoreCase = true) &&
                    course.departmentName.equals(user.departmentName, ignoreCase = true) &&
                    course.courseName.equals(normalizedCourseName, ignoreCase = true) &&
                    normalizeProfessorName(course.professorName).equals(normalizeProfessorName(normalizedProfessorName), ignoreCase = true)
            }
        if (duplicateExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "같은 과목이 이미 등록되어 있습니다.")
        }

        val saved = studentCourseRepository.save(
            StudentCourse(
                userId = user.id,
                universityName = user.universityName!!.trim(),
                departmentName = user.departmentName!!.trim(),
                courseName = normalizedCourseName,
                professorName = normalizedProfessorName,
                description = normalizedDescription
            )
        )
        return saved.toResponse()
    }

    @Transactional(readOnly = true)
    fun getCourseMaterials(principal: AuthPrincipal, courseId: Long): List<StudentCourseMaterialResponse> {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        return studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(course.id)
            .map { it.toResponse() }
    }

    @Transactional
    fun uploadCourseMaterial(principal: AuthPrincipal, courseId: Long, file: MultipartFile): StudentCourseMaterialResponse {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val uploaded = userFileService.uploadMyFile(principal, FileType.COURSE_MATERIAL, file)
        val userFile = userFileService.loadOwnedFile(user.id, uploaded.fileId)
        val saved = studentCourseMaterialRepository.save(
            StudentCourseMaterial(
                course = course,
                userFile = userFile
            )
        )
        return saved.toResponse()
    }

    private fun getValidatedStudentUser(principal: AuthPrincipal) = userRepository.findById(principal.userId)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }
        .also { user ->
            if (user.serviceMode != UserServiceMode.STUDENT) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학생 모드에서만 사용할 수 있습니다.")
            }
            if (user.universityName.isNullOrBlank() || user.departmentName.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학교와 학과를 먼저 등록해 주세요.")
            }
        }

    private fun getOwnedCourse(userId: Long, courseId: Long): StudentCourse {
        return studentCourseRepository.findById(courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다.") }
            .also { course ->
                if (course.userId != userId || course.isArchived) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다.")
                }
            }
    }

    private fun normalizeProfessorName(value: String?): String = value?.trim().orEmpty()

    private fun StudentCourse.toResponse(): StudentCourseResponse = StudentCourseResponse(
        courseId = id,
        universityName = universityName,
        departmentName = departmentName,
        courseName = courseName,
        professorName = professorName,
        description = description,
        materialCount = studentCourseMaterialRepository.countByCourse_Id(id).toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun StudentCourseMaterial.toResponse(): StudentCourseMaterialResponse = StudentCourseMaterialResponse(
        materialId = id,
        fileId = userFile.id,
        fileType = userFile.fileType,
        fileName = userFile.fileName,
        originalFileName = userFile.originalFileName,
        fileUrl = userFile.fileUrl,
        createdAt = createdAt
    )
}
