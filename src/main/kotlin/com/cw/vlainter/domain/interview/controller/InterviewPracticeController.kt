package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.BookmarkTurnRequest
import com.cw.vlainter.domain.interview.dto.QuestionAttemptResponse
import com.cw.vlainter.domain.interview.dto.SavedQuestionResponse
import com.cw.vlainter.domain.interview.dto.StartTechInterviewRequest
import com.cw.vlainter.domain.interview.dto.StartTechInterviewResponse
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerRequest
import com.cw.vlainter.domain.interview.dto.SubmitInterviewAnswerResponse
import com.cw.vlainter.domain.interview.service.InterviewPracticeService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interview/tech")
class InterviewPracticeController(
    private val interviewPracticeService: InterviewPracticeService
) {
    @PostMapping("/sessions")
    fun startTechInterview(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: StartTechInterviewRequest
    ): ResponseEntity<StartTechInterviewResponse> {
        return ResponseEntity.ok(interviewPracticeService.startTechInterview(principal, request))
    }

    @PostMapping("/sessions/{sessionId}/answers")
    fun submitAnswer(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: SubmitInterviewAnswerRequest
    ): ResponseEntity<SubmitInterviewAnswerResponse> {
        return ResponseEntity.ok(interviewPracticeService.submitAnswer(principal, sessionId, request))
    }

    @PostMapping("/turns/{turnId}/bookmark")
    fun bookmarkTurn(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable turnId: Long,
        @RequestBody(required = false) request: BookmarkTurnRequest?
    ): ResponseEntity<SavedQuestionResponse> {
        return ResponseEntity.ok(interviewPracticeService.bookmarkTurn(principal, turnId, request ?: BookmarkTurnRequest()))
    }

    @GetMapping("/saved-questions")
    fun getSavedQuestions(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<SavedQuestionResponse>> {
        return ResponseEntity.ok(interviewPracticeService.getSavedQuestions(principal))
    }

    @DeleteMapping("/saved-questions/{savedQuestionId}")
    fun deleteSavedQuestion(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable savedQuestionId: Long
    ): ResponseEntity<Map<String, String>> {
        interviewPracticeService.deleteSavedQuestion(principal, savedQuestionId)
        return ResponseEntity.ok(mapOf("message" to "저장된 질문이 삭제되었습니다."))
    }

    @GetMapping("/questions/{questionId}/attempts")
    fun getQuestionAttempts(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable questionId: Long
    ): ResponseEntity<List<QuestionAttemptResponse>> {
        return ResponseEntity.ok(interviewPracticeService.getMyAttemptsByQuestion(principal, questionId))
    }
}
