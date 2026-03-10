package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.user.entity.User
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class InterviewCategoryContextResolver(
    private val categoryRepository: QaCategoryRepository
) {
    data class ResolvedCategoryContext(
        val category: QaCategory,
        val jobName: String,
        val skillName: String
    )

    fun resolve(
        actor: User,
        categoryId: Long?,
        jobName: String?,
        skillName: String?,
        createIfMissing: Boolean
    ): ResolvedCategoryContext? {
        val normalizedJobName = jobName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSkillName = skillName?.trim()?.takeIf { it.isNotBlank() }

        if (categoryId != null) {
            val category = categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 categoryId 입니다: $categoryId")
            if (!category.isActive) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리입니다.")
            }
            return ResolvedCategoryContext(
                category = category,
                jobName = normalizedJobName ?: category.parent?.name?.trim().orEmpty().ifBlank { "직무" },
                skillName = normalizedSkillName ?: category.name.trim()
            )
        }

        if (normalizedSkillName == null) {
            return null
        }

        val matches = categoryRepository.findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc()
            .asSequence()
            .filter { it.depth == 2 }
            .filter { it.name.trim().equals(normalizedSkillName, ignoreCase = true) }
            .filter { category ->
                normalizedJobName == null || category.parent?.name?.trim().equals(normalizedJobName, ignoreCase = true)
            }
            .toList()

        if (matches.isEmpty()) {
            if (!createIfMissing) return null
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "기술 카테고리를 찾을 수 없습니다. 계열-직무-기술 트리에서 먼저 생성하거나 선택해 주세요: $normalizedSkillName"
            )
        }

        if (matches.size > 1) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "동일한 기술명이 여러 트리에 존재합니다. 기술 카테고리를 직접 선택해 주세요: $normalizedSkillName"
            )
        }

        val skillCategory = matches.first()
        val resolvedJobName = skillCategory.parent?.name?.trim().orEmpty()
            .ifBlank { normalizedJobName ?: "직무" }

        return ResolvedCategoryContext(
            category = skillCategory,
            jobName = resolvedJobName,
            skillName = normalizedSkillName
        )
    }
}
