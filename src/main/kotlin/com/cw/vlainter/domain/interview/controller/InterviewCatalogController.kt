package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.CategoryResponse
import com.cw.vlainter.domain.interview.service.CategoryAdminService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
}
