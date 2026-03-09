package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.CategoryResponse
import com.cw.vlainter.domain.interview.dto.CreateCategoryRequest
import com.cw.vlainter.domain.interview.dto.MoveCategoryRequest
import com.cw.vlainter.domain.interview.dto.UpdateCategoryRequest
import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class CategoryAdminService(
    private val categoryRepository: QaCategoryRepository,
    private val userRepository: UserRepository
) {
    @Transactional(readOnly = true)
    fun getActiveCategoryTree(): List<CategoryResponse> {
        return categoryRepository.findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc()
            .map { toResponse(it) }
    }

    @Transactional
    fun createCategory(principal: AuthPrincipal, request: CreateCategoryRequest): CategoryResponse {
        val actor = loadUser(principal.userId)
        val parent = request.parentId?.let { findActiveCategory(it) }
        val normalizedName = request.name.trim()
        if (normalizedName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "카테고리 이름은 필수입니다.")
        }

        val depth = (parent?.depth ?: -1) + 1
        if (depth !in 0..2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "카테고리는 계열(0) / 직무(1) / 기술(2) 깊이까지만 생성할 수 있습니다.")
        }

        val normalizedCode = allocateUniqueCode(parent?.id, normalizeCode(request.code ?: normalizedName))
        if (hasDuplicateName(parent, normalizedName)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "같은 위치에 동일한 이름의 카테고리가 이미 존재합니다.")
        }

        val path = (parent?.path ?: "") + "/$normalizedCode"
        val saved = categoryRepository.save(
            QaCategory(
                parent = parent,
                code = normalizedCode,
                name = normalizedName,
                description = request.description?.trim(),
                depth = depth,
                path = path,
                sortOrder = request.sortOrder,
                isActive = true,
                isLeaf = true,
                createdBy = actor,
                updatedBy = actor
            )
        )

        if (parent != null && parent.isLeaf) {
            parent.isLeaf = false
        }

        return toResponse(saved)
    }

    @Transactional
    fun updateCategory(principal: AuthPrincipal, categoryId: Long, request: UpdateCategoryRequest): CategoryResponse {
        ensureAdmin(principal)
        val actor = loadUser(principal.userId)
        val category = findCategory(categoryId)

        request.name?.let { category.name = it.trim() }
        request.description?.let { category.description = it.trim() }
        request.sortOrder?.let { category.sortOrder = it }
        request.isActive?.let { category.isActive = it }
        request.isLeaf?.let { category.isLeaf = it }
        category.updatedBy = actor

        return toResponse(category)
    }

    @Transactional
    fun moveCategory(principal: AuthPrincipal, categoryId: Long, request: MoveCategoryRequest): CategoryResponse {
        ensureAdmin(principal)
        val actor = loadUser(principal.userId)
        val category = findCategory(categoryId)
        val newParent = request.parentId?.let { findActiveCategory(it) }

        if (newParent != null && newParent.id == category.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신을 부모로 지정할 수 없습니다.")
        }
        if (newParent != null && newParent.path.startsWith("${category.path}/")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "하위 카테고리를 부모로 이동할 수 없습니다.")
        }
        if (hasDuplicateName(newParent, category.name)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "같은 위치에 동일한 이름의 카테고리가 이미 존재합니다.")
        }

        when (category.depth) {
            0 -> {
                if (newParent != null) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "계열(depth 0)은 부모 카테고리를 가질 수 없습니다.")
                }
            }
            1 -> {
                if (newParent == null || newParent.depth != 0) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "직무(depth 1)는 계열(depth 0)로만 이동할 수 있습니다.")
                }
            }
            2 -> {
                if (newParent == null || newParent.depth != 1) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "기술(depth 2)은 직무(depth 1)로만 이동할 수 있습니다.")
                }
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 depth 카테고리입니다.")
        }

        val oldPath = category.path
        category.parent = newParent
        category.depth = (newParent?.depth ?: -1) + 1
        category.path = (newParent?.path ?: "") + "/${category.code}"
        category.updatedBy = actor

        categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc("$oldPath/")
            .forEach { child ->
            val suffix = child.path.removePrefix(oldPath)
            child.path = "${category.path}$suffix"
            child.depth = child.path.split("/").count { it.isNotBlank() } - 1
            child.updatedBy = actor
        }

        return toResponse(category)
    }

    private fun findCategory(categoryId: Long): QaCategory {
        return categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다.")
    }

    private fun findActiveCategory(categoryId: Long): QaCategory {
        val category = findCategory(categoryId)
        if (!category.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성화된 카테고리입니다.")
        }
        return category
    }

    private fun ensureAdmin(principal: AuthPrincipal) {
        if (principal.role != UserRole.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 접근 가능합니다.")
        }
    }

    private fun loadUser(userId: Long) = userRepository.findById(userId)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자 정보를 찾을 수 없습니다.") }

    private fun hasDuplicateName(parent: QaCategory?, name: String): Boolean {
        return if (parent == null) {
            categoryRepository.existsByParentIsNullAndNameIgnoreCaseAndDeletedAtIsNull(name)
        } else {
            categoryRepository.existsByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(parent.id, name)
        }
    }

    private fun allocateUniqueCode(parentId: Long?, baseCode: String): String {
        var sequence = 1
        var candidate = baseCode
        while (existsCode(parentId, candidate)) {
            sequence += 1
            val suffix = "_$sequence"
            val prefixMax = (80 - suffix.length).coerceAtLeast(1)
            val prefix = baseCode.take(prefixMax).trimEnd('_').ifBlank { "CATEGORY" }
            candidate = (prefix + suffix).take(80)
        }
        return candidate
    }

    private fun existsCode(parentId: Long?, code: String): Boolean {
        return if (parentId == null) {
            categoryRepository.existsByParentIsNullAndCodeAndDeletedAtIsNull(code)
        } else {
            categoryRepository.existsByParent_IdAndCodeAndDeletedAtIsNull(parentId, code)
        }
    }

    private fun normalizeCode(raw: String): String {
        return raw.trim()
            .uppercase()
            .replace(Regex("[^A-Z0-9가-힣_]+"), "_")
            .trim('_')
            .ifBlank { "CUSTOM_${raw.hashCode().toUInt().toString(16).uppercase()}" }
            .take(80)
    }

    private fun toResponse(category: QaCategory): CategoryResponse {
        return CategoryResponse(
            categoryId = category.id,
            parentId = category.parent?.id,
            code = category.code,
            name = category.name,
            description = category.description,
            depth = category.depth,
            depthLabel = depthLabel(category.depth),
            path = category.path,
            sortOrder = category.sortOrder,
            isActive = category.isActive,
            isLeaf = category.isLeaf
        )
    }

    private fun depthLabel(depth: Int): String = when (depth) {
        0 -> "계열"
        1 -> "직무"
        2 -> "기술"
        else -> "기타"
    }
}
