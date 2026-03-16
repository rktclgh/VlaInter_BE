package com.cw.vlainter.domain.student.controller

import com.cw.vlainter.domain.student.dto.CreateStudentCourseRequest
import com.cw.vlainter.domain.student.dto.CreateStudentExamSessionRequest
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialResponse
import com.cw.vlainter.domain.student.dto.StudentCourseResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionDetailResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionResponse
import com.cw.vlainter.domain.student.dto.SubmitStudentExamAnswersRequest
import com.cw.vlainter.domain.student.service.StudentCourseService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/student/courses")
class StudentCourseController(
    private val studentCourseService: StudentCourseService
) {
    @GetMapping
    fun getMyCourses(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<StudentCourseResponse>> {
        return ResponseEntity.ok(studentCourseService.getMyCourses(principal))
    }

    @PostMapping
    fun createCourse(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: CreateStudentCourseRequest
    ): ResponseEntity<StudentCourseResponse> {
        return ResponseEntity.ok(studentCourseService.createCourse(principal, request))
    }

    @GetMapping("/{courseId}/materials")
    fun getCourseMaterials(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long
    ): ResponseEntity<List<StudentCourseMaterialResponse>> {
        return ResponseEntity.ok(studentCourseService.getCourseMaterials(principal, courseId))
    }

    @PostMapping("/{courseId}/materials", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadCourseMaterial(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @RequestParam file: MultipartFile
    ): ResponseEntity<StudentCourseMaterialResponse> {
        return ResponseEntity.ok(studentCourseService.uploadCourseMaterial(principal, courseId, file))
    }

    @GetMapping("/{courseId}/sessions")
    fun getCourseSessions(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long
    ): ResponseEntity<List<StudentExamSessionResponse>> {
        return ResponseEntity.ok(studentCourseService.getCourseSessions(principal, courseId))
    }

    @PostMapping("/{courseId}/sessions")
    fun createCourseSession(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @Valid @RequestBody request: CreateStudentExamSessionRequest
    ): ResponseEntity<StudentExamSessionResponse> {
        return ResponseEntity.ok(studentCourseService.createCourseSession(principal, courseId, request))
    }

    @GetMapping("/sessions/{sessionId}")
    fun getSessionDetail(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long
    ): ResponseEntity<StudentExamSessionDetailResponse> {
        return ResponseEntity.ok(studentCourseService.getSessionDetail(principal, sessionId))
    }

    @PostMapping("/sessions/{sessionId}/submit")
    fun submitSessionAnswers(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: SubmitStudentExamAnswersRequest
    ): ResponseEntity<StudentExamSessionDetailResponse> {
        return ResponseEntity.ok(studentCourseService.submitSessionAnswers(principal, sessionId, request))
    }
}
