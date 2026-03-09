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

        val techRoot = categoryRepository.findByParentIsNullAndCodeAndDeletedAtIsNull("TECH")
            ?: if (createIfMissing) {
                categoryRepository.save(
                    QaCategory(
                        parent = null,
                        code = "TECH",
                        name = "기술",
                        description = "자동 생성된 기술 카테고리 루트",
                        depth = 0,
                        path = "/TECH",
                        sortOrder = 0,
                        isActive = true,
                        isLeaf = false,
                        createdBy = actor,
                        updatedBy = actor
                    )
                )
            } else {
                return null
            }
        if (!techRoot.isActive) {
            if (!createIfMissing) return null
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기술 카테고리 루트가 비활성화되어 있습니다.")
        }

        val resolvedJobName = normalizedJobName ?: "직무"
        val jobCategory = findOrCreateChild(
            parent = techRoot,
            name = resolvedJobName,
            actor = actor,
            createIfMissing = createIfMissing
        ) ?: return null

        val skillCategory = findOrCreateChild(
            parent = jobCategory,
            name = normalizedSkillName,
            actor = actor,
            createIfMissing = createIfMissing
        ) ?: return null

        return ResolvedCategoryContext(
            category = skillCategory,
            jobName = resolvedJobName,
            skillName = normalizedSkillName
        )
    }

    private fun findOrCreateChild(
        parent: QaCategory,
        name: String,
        actor: User,
        createIfMissing: Boolean
    ): QaCategory? {
        val existing = categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(parent.id, name)
        if (existing != null) {
            if (!existing.isActive) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리입니다: ${existing.name}")
            }
            return existing
        }
        if (!createIfMissing) return null

        val code = buildUniqueCode(parent.id, name)
        if (parent.isLeaf) {
            parent.isLeaf = false
            parent.updatedBy = actor
            categoryRepository.save(parent)
        }
        return categoryRepository.save(
            QaCategory(
                parent = parent,
                code = code,
                name = name,
                description = null,
                depth = parent.depth + 1,
                path = "${parent.path}/$code",
                sortOrder = 100,
                isActive = true,
                isLeaf = true,
                createdBy = actor,
                updatedBy = actor
            )
        )
    }

    private fun buildUniqueCode(parentId: Long, source: String): String {
        val normalized = source
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
            .replace(Regex("^_+|_+$"), "")
            .uppercase()
            .ifBlank { "CUSTOM" }

        var candidate = normalized
        var suffix = 2
        while (categoryRepository.existsByParent_IdAndCodeAndDeletedAtIsNull(parentId, candidate)) {
            candidate = "${normalized}_$suffix"
            suffix += 1
        }
        return candidate
    }
}
