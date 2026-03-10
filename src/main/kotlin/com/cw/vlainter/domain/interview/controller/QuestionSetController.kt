package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.dto.CreateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.QuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.QuestionSummaryResponse
import com.cw.vlainter.domain.interview.dto.UpdateQuestionInSetRequest
import com.cw.vlainter.domain.interview.dto.UpdateQuestionSetRequest
import com.cw.vlainter.domain.interview.service.QuestionSetService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interview/sets")
class QuestionSetController(
    private val questionSetService: QuestionSetService
) {
    @PostMapping
    fun createMySet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: CreateQuestionSetRequest
    ): ResponseEntity<QuestionSetSummaryResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(questionSetService.createMySet(principal, request))
    }

    @GetMapping
    fun getMyAndGlobalSets(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<QuestionSetSummaryResponse>> {
        return ResponseEntity.ok(questionSetService.getMyAndGlobalSets(principal))
    }

    @GetMapping("/my")
    fun getMySets(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<QuestionSetSummaryResponse>> {
        return ResponseEntity.ok(questionSetService.getMySets(principal))
    }

    @GetMapping("/global")
    fun getGlobalSets(): ResponseEntity<List<QuestionSetSummaryResponse>> {
        return ResponseEntity.ok(questionSetService.getGlobalSets())
    }

    @GetMapping("/{setId}/questions")
    fun getSetQuestions(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long
    ): ResponseEntity<List<QuestionSummaryResponse>> {
        return ResponseEntity.ok(questionSetService.getSetQuestions(principal, setId))
    }

    @PostMapping("/{setId}/questions")
    fun addQuestionToSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long,
        @Valid @RequestBody request: AddQuestionToSetRequest
    ): ResponseEntity<QuestionSummaryResponse> {
        return ResponseEntity.ok(questionSetService.addQuestionToSet(principal, setId, request))
    }

    @PatchMapping("/{setId}/questions/{questionId}")
    fun updateQuestionInSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long,
        @PathVariable questionId: Long,
        @Valid @RequestBody request: UpdateQuestionInSetRequest
    ): ResponseEntity<QuestionSummaryResponse> {
        return ResponseEntity.ok(questionSetService.updateQuestionInSet(principal, setId, questionId, request))
    }

    @DeleteMapping("/{setId}/questions/{questionId}")
    fun deleteQuestionFromSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long,
        @PathVariable questionId: Long
    ): ResponseEntity<Map<String, String>> {
        questionSetService.deleteQuestionFromSet(principal, setId, questionId)
        return ResponseEntity.ok(mapOf("message" to "질문이 세트에서 삭제되었습니다."))
    }

    @PatchMapping("/{setId}")
    fun updateMySet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long,
        @Valid @RequestBody request: UpdateQuestionSetRequest
    ): ResponseEntity<QuestionSetSummaryResponse> {
        return ResponseEntity.ok(questionSetService.updateMySet(principal, setId, request))
    }

    @DeleteMapping("/{setId}")
    fun deleteMySet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long
    ): ResponseEntity<Map<String, String>> {
        questionSetService.deleteMySet(principal, setId)
        return ResponseEntity.ok(mapOf("message" to "질문 세트가 삭제되었습니다."))
    }
}
