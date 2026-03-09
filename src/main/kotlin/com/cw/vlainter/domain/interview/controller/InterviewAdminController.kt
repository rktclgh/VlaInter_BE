package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.CategoryResponse
import com.cw.vlainter.domain.interview.dto.CreateCategoryRequest
import com.cw.vlainter.domain.interview.dto.MoveCategoryRequest
import com.cw.vlainter.domain.interview.dto.QuestionSetSummaryResponse
import com.cw.vlainter.domain.interview.dto.UpdateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.UpdateCategoryRequest
import com.cw.vlainter.domain.interview.service.CategoryAdminService
import com.cw.vlainter.domain.interview.service.QuestionSetService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RequestParam

@RestController
@RequestMapping("/api/admin/interview")
class InterviewAdminController(
    private val questionSetService: QuestionSetService,
    private val categoryAdminService: CategoryAdminService
) {
    @GetMapping("/sets")
    fun getAllSets(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false) keyword: String?
    ): ResponseEntity<List<QuestionSetSummaryResponse>> {
        return ResponseEntity.ok(questionSetService.getAllSetsForAdmin(principal, keyword))
    }

    @PostMapping("/sets/{setId}/promote")
    fun promoteSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long
    ): ResponseEntity<QuestionSetSummaryResponse> {
        return ResponseEntity.ok(questionSetService.promoteSet(principal, setId))
    }

    @PatchMapping("/sets/{setId}")
    fun updateSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long,
        @RequestBody request: UpdateQuestionSetRequest
    ): ResponseEntity<QuestionSetSummaryResponse> {
        return ResponseEntity.ok(questionSetService.updateSetByAdmin(principal, setId, request))
    }

    @DeleteMapping("/sets/{setId}")
    fun deleteSet(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable setId: Long
    ): ResponseEntity<Map<String, String>> {
        questionSetService.deleteSetByAdmin(principal, setId)
        return ResponseEntity.ok(mapOf("message" to "질문 세트가 삭제되었습니다."))
    }

    @GetMapping("/categories")
    fun getCategories(): ResponseEntity<List<CategoryResponse>> {
        return ResponseEntity.ok(categoryAdminService.getActiveCategoryTree())
    }

    @PostMapping("/categories")
    fun createCategory(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: CreateCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        return ResponseEntity.ok(categoryAdminService.createCategory(principal, request))
    }

    @PatchMapping("/categories/{categoryId}")
    fun updateCategory(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable categoryId: Long,
        @RequestBody request: UpdateCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        return ResponseEntity.ok(categoryAdminService.updateCategory(principal, categoryId, request))
    }

    @PatchMapping("/categories/{categoryId}/move")
    fun moveCategory(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable categoryId: Long,
        @Valid @RequestBody request: MoveCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        return ResponseEntity.ok(categoryAdminService.moveCategory(principal, categoryId, request))
    }
}
