package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.dto.CreateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.QuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.QuestionSummaryResponse
import com.cw.vlainter.domain.interview.service.QuestionSetService
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
@RequestMapping("/api/interview/sets")
class QuestionSetController(
    private val questionSetService: QuestionSetService
) {
    @PostMapping
    fun createMySet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: CreateQuestionSetRequest
    ): ResponseEntity<QuestionSetSummaryResponse> {
        return ResponseEntity.ok(questionSetService.createMySet(principal, request))
    }

    @GetMapping
    fun getMyAndGlobalSets(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<List<QuestionSetSummaryResponse>> {
        return ResponseEntity.ok(questionSetService.getMyAndGlobalSets(principal))
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
}
