package com.cw.vlainter.domain.academic.controller

import com.cw.vlainter.domain.academic.dto.DepartmentSearchItemResponse
import com.cw.vlainter.domain.academic.dto.UniversitySearchItemResponse
import com.cw.vlainter.domain.academic.service.AcademicSearchService
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Validated
@RestController
@RequestMapping("/api/academics")
class AcademicSearchController(
    private val academicSearchService: AcademicSearchService
) {
    @GetMapping("/universities/search")
    fun searchUniversities(
        @RequestParam
        @Size(max = 120, message = "대학교 검색어는 120자 이하여야 합니다.")
        keyword: String
    ): List<UniversitySearchItemResponse> {
        return academicSearchService.searchUniversities(keyword)
    }

    @GetMapping("/departments/search")
    fun searchDepartments(
        @RequestParam(required = false)
        universityId: String?,
        @RequestParam
        @Size(max = 120, message = "대학교 이름은 120자 이하여야 합니다.")
        universityName: String,
        @RequestParam
        @Size(max = 120, message = "학과 검색어는 120자 이하여야 합니다.")
        keyword: String
    ): List<DepartmentSearchItemResponse> {
        val parsedUniversityId = parseUniversityId(universityId)
        return academicSearchService.searchDepartments(
            universityId = parsedUniversityId,
            universityName = universityName,
            keyword = keyword
        )
    }

    private fun parseUniversityId(rawUniversityId: String?): Long? {
        val normalized = rawUniversityId?.trim()
        if (normalized.isNullOrEmpty()) return null
        if (normalized.equals("null", ignoreCase = true)) return null
        if (normalized.equals("undefined", ignoreCase = true)) return null
        return normalized.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "universityId는 숫자여야 합니다.")
    }
}
