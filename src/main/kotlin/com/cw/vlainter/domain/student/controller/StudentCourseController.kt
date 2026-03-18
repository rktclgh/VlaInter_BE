package com.cw.vlainter.domain.student.controller

import com.cw.vlainter.domain.student.dto.CreateStudentCourseRequest
import com.cw.vlainter.domain.student.dto.CreateStudentCourseSummaryPreviewRequest
import com.cw.vlainter.domain.student.dto.CreateStudentCourseSummaryDocumentRequest
import com.cw.vlainter.domain.student.dto.CreateStudentExamSessionRequest
import com.cw.vlainter.domain.student.dto.CreateStudentCourseYoutubeMaterialRequest
import com.cw.vlainter.domain.student.dto.CreateStudentWrongAnswerSetRequest
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialKind
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialDownloadResponse
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialResponse
import com.cw.vlainter.domain.student.dto.StudentCourseResponse
import com.cw.vlainter.domain.student.dto.StudentCourseYoutubeSummaryJobResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionDetailResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionResponse
import com.cw.vlainter.domain.student.dto.StudentWrongAnswerSetDetailResponse
import com.cw.vlainter.domain.student.dto.StudentWrongAnswerSetResponse
import com.cw.vlainter.domain.student.dto.SubmitStudentExamAnswersRequest
import com.cw.vlainter.domain.student.service.StudentCourseService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import java.nio.charset.StandardCharsets
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.DeleteMapping
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

    @DeleteMapping("/{courseId}")
    fun deleteCourse(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long
    ): ResponseEntity<Void> {
        studentCourseService.deleteCourse(principal, courseId)
        return ResponseEntity.noContent().build()
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
        @RequestParam file: MultipartFile,
        @RequestParam(defaultValue = "LECTURE_MATERIAL") materialKind: StudentCourseMaterialKind
    ): ResponseEntity<StudentCourseMaterialResponse> {
        return ResponseEntity.ok(studentCourseService.uploadCourseMaterial(principal, courseId, file, materialKind))
    }

    @PostMapping("/{courseId}/youtube-materials")
    fun uploadYoutubeCourseMaterial(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @Valid @RequestBody request: CreateStudentCourseYoutubeMaterialRequest
    ): ResponseEntity<StudentCourseYoutubeSummaryJobResponse> {
        return ResponseEntity.ok(studentCourseService.uploadYoutubeCourseMaterial(principal, courseId, request))
    }

    @GetMapping("/{courseId}/youtube-materials")
    fun getYoutubeCourseMaterialJobs(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long
    ): ResponseEntity<List<StudentCourseYoutubeSummaryJobResponse>> {
        return ResponseEntity.ok(studentCourseService.getYoutubeCourseMaterialJobs(principal, courseId))
    }

    @DeleteMapping("/{courseId}/youtube-materials/{jobId}")
    fun deleteYoutubeCourseMaterialJob(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @PathVariable jobId: Long
    ): ResponseEntity<Void> {
        studentCourseService.deleteYoutubeCourseMaterialJob(principal, courseId, jobId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{courseId}/materials/{materialId}")
    fun deleteCourseMaterial(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @PathVariable materialId: Long
    ): ResponseEntity<Void> {
        studentCourseService.deleteCourseMaterial(principal, courseId, materialId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{courseId}/materials/{materialId}/download")
    fun getCourseMaterialDownloadUrl(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @PathVariable materialId: Long
    ): ResponseEntity<StudentCourseMaterialDownloadResponse> {
        return ResponseEntity.ok(studentCourseService.getCourseMaterialDownloadUrl(principal, courseId, materialId))
    }

    @GetMapping("/{courseId}/materials/{materialId}/content")
    fun getCourseMaterialContent(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @PathVariable materialId: Long
    ): ResponseEntity<ByteArray> {
        val resource = studentCourseService.getCourseMaterialContent(principal, courseId, materialId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(resource.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(resource.fileName, StandardCharsets.UTF_8).build().toString()
            )
            .body(resource.bytes)
    }

    @GetMapping("/material-visual-assets/{assetId}/content")
    fun getCourseMaterialVisualAssetContent(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable assetId: Long
    ): ResponseEntity<ByteArray> {
        val resource = studentCourseService.getCourseMaterialVisualAssetContent(principal, assetId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(resource.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(resource.fileName, StandardCharsets.UTF_8).build().toString()
            )
            .body(resource.bytes)
    }

    @PostMapping("/{courseId}/materials/{materialId}/analyze")
    fun analyzeCourseMaterial(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @PathVariable materialId: Long
    ): ResponseEntity<StudentCourseMaterialResponse> {
        return ResponseEntity.ok(studentCourseService.requestCourseMaterialIngestion(principal, courseId, materialId))
    }

    @PostMapping("/{courseId}/summary-documents")
    fun generateCourseSummaryDocument(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @Valid @RequestBody request: CreateStudentCourseSummaryDocumentRequest
    ): ResponseEntity<ByteArray> {
        val resource = studentCourseService.generateCourseSummaryDocument(principal, courseId, request)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(resource.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(resource.fileName, StandardCharsets.UTF_8).build().toString()
            )
            .body(resource.bytes)
    }

    @PostMapping("/{courseId}/summary-preview")
    fun generateCourseSummaryPreview(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long,
        @Valid @RequestBody request: CreateStudentCourseSummaryPreviewRequest
    ) = ResponseEntity.ok(studentCourseService.generateCourseSummaryPreview(principal, courseId, request))

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

    @GetMapping("/{courseId}/wrong-answer-sets")
    fun getCourseWrongAnswerSets(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable courseId: Long
    ): ResponseEntity<List<StudentWrongAnswerSetResponse>> {
        return ResponseEntity.ok(studentCourseService.getCourseWrongAnswerSets(principal, courseId))
    }

    @GetMapping("/wrong-answer-sets/{setId}")
    fun getWrongAnswerSetDetail(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long
    ): ResponseEntity<StudentWrongAnswerSetDetailResponse> {
        return ResponseEntity.ok(studentCourseService.getWrongAnswerSetDetail(principal, setId))
    }

    @PostMapping("/wrong-answer-sets/{setId}/retest")
    fun createRetestSession(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long
    ): ResponseEntity<StudentExamSessionResponse> {
        return ResponseEntity.ok(studentCourseService.createRetestSession(principal, setId))
    }

    @GetMapping("/sessions/{sessionId}")
    fun getSessionDetail(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long
    ): ResponseEntity<StudentExamSessionDetailResponse> {
        return ResponseEntity.ok(studentCourseService.getSessionDetail(principal, sessionId))
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun deleteSession(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long
    ): ResponseEntity<Void> {
        studentCourseService.deleteSession(principal, sessionId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/sessions/{sessionId}/submit")
    fun submitSessionAnswers(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: SubmitStudentExamAnswersRequest
    ): ResponseEntity<StudentExamSessionDetailResponse> {
        return ResponseEntity.ok(studentCourseService.submitSessionAnswers(principal, sessionId, request))
    }

    @PostMapping("/sessions/{sessionId}/wrong-answer-set")
    fun createWrongAnswerSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CreateStudentWrongAnswerSetRequest
    ): ResponseEntity<StudentWrongAnswerSetResponse> {
        return ResponseEntity.ok(studentCourseService.createWrongAnswerSet(principal, sessionId, request))
    }
}
