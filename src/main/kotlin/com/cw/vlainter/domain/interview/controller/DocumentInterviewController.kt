package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.BookmarkTurnRequest
import com.cw.vlainter.domain.interview.dto.DocumentIngestionResponse
import com.cw.vlainter.domain.interview.dto.InterviewSessionResultsResponse
import com.cw.vlainter.domain.interview.dto.InterviewSessionHistoryResponse
import com.cw.vlainter.domain.interview.dto.ReadyDocumentResponse
import com.cw.vlainter.domain.interview.dto.ResumeInterviewSessionResponse
import com.cw.vlainter.domain.interview.dto.SavedQuestionResponse
import com.cw.vlainter.domain.interview.dto.StartMockInterviewRequest
import com.cw.vlainter.domain.interview.dto.StartTechInterviewResponse
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerRequest
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerResponse
import com.cw.vlainter.domain.interview.service.DocumentInterviewService
import com.cw.vlainter.domain.interview.service.InterviewPracticeService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interview/mock")
class DocumentInterviewController(
    private val documentInterviewService: DocumentInterviewService,
    private val interviewPracticeService: InterviewPracticeService
) {
    @GetMapping("/documents")
    fun getReadyDocuments(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<ReadyDocumentResponse>> {
        return ResponseEntity.ok(documentInterviewService.getReadyDocuments(principal))
    }

    @PostMapping("/documents/{fileId}/ingestion")
    fun ingestDocument(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable fileId: Long
    ): ResponseEntity<DocumentIngestionResponse> {
        return ResponseEntity.ok(documentInterviewService.ingestDocument(principal, fileId))
    }

    @PostMapping("/sessions")
    fun startMockInterview(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: StartMockInterviewRequest
    ): ResponseEntity<StartTechInterviewResponse> {
        return ResponseEntity.ok(documentInterviewService.startMockInterview(principal, request))
    }

    @PostMapping("/sessions/{sessionId}/answers")
    fun submitAnswer(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: SubmitInterviewAnswerRequest
    ): ResponseEntity<SubmitInterviewAnswerResponse> {
        return ResponseEntity.ok(interviewPracticeService.submitMockAnswer(principal, sessionId, request))
    }

    @GetMapping("/sessions/{sessionId}/results")
    fun getSessionResults(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long
    ): ResponseEntity<InterviewSessionResultsResponse> {
        return ResponseEntity.ok(interviewPracticeService.getSessionResults(principal, sessionId))
    }

    @GetMapping("/sessions/history")
    fun getSessionHistory(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<InterviewSessionHistoryResponse>> {
        return ResponseEntity.ok(documentInterviewService.getMockSessionHistory(principal))
    }

    @GetMapping("/sessions/latest-incomplete")
    fun getLatestIncompleteSession(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<ResumeInterviewSessionResponse?> {
        return ResponseEntity.ok(documentInterviewService.getLatestIncompleteMockSession(principal))
    }

    @PostMapping("/sessions/{sessionId}/dismiss")
    fun dismissSession(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long
    ): ResponseEntity<Map<String, String>> {
        documentInterviewService.dismissMockSession(principal, sessionId)
        return ResponseEntity.ok(mapOf("message" to "진행 중인 모의면접 세션을 종료했습니다."))
    }

    @PostMapping("/turns/{turnId}/bookmark")
    fun bookmarkTurn(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable turnId: Long,
        @RequestBody(required = false) request: BookmarkTurnRequest?
    ): ResponseEntity<SavedQuestionResponse> {
        return ResponseEntity.ok(interviewPracticeService.bookmarkTurn(principal, turnId, request ?: BookmarkTurnRequest()))
    }
}
