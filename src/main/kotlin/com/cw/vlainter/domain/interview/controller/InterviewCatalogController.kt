package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.CategoryResponse
import com.cw.vlainter.domain.interview.dto.CreateCategoryRequest
import com.cw.vlainter.domain.interview.service.CategoryAdminService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interview")
class InterviewCatalogController(
    private val categoryAdminService: CategoryAdminService
) {
    @GetMapping("/categories")
    fun getCategoryTree(): ResponseEntity<List<CategoryResponse>> {
        return ResponseEntity.ok(categoryAdminService.getActiveCategoryTree())
    }

    @PostMapping("/categories")
    fun createCategory(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: CreateCategoryRequest
    ): ResponseEntity<CategoryResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(categoryAdminService.createCategory(principal, request))
    }
}
