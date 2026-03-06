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
import java.util.Locale

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
        ensureAdmin(principal)
        val actor = loadUser(principal.userId)
        val parent = request.parentId?.let { findActiveCategory(it) }

        val normalizedCode = normalizeCode(request.code)
        if (parent != null && categoryRepository.existsByParent_IdAndCodeAndDeletedAtIsNull(parent.id, normalizedCode)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "같은 부모 카테고리 아래에 동일 코드가 이미 존재합니다.")
        }

        val depth = (parent?.depth ?: -1) + 1
        val path = (parent?.path ?: "") + "/$normalizedCode"
        val saved = categoryRepository.save(
            QaCategory(
                parent = parent,
                code = normalizedCode,
                name = request.name.trim(),
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

        category.parent = newParent
        category.depth = (newParent?.depth ?: -1) + 1
        category.path = (newParent?.path ?: "") + "/${category.code}"
        category.updatedBy = actor

        // direct children path update
        categoryRepository.findAllByParent_IdAndDeletedAtIsNullOrderBySortOrderAsc(category.id).forEach { child ->
            child.path = "${category.path}/${child.code}"
            child.depth = category.depth + 1
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

    private fun normalizeCode(raw: String): String {
        return raw.trim()
            .uppercase(Locale.getDefault())
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .trim('_')
            .ifBlank { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 코드 형식이 아닙니다.") }
    }

    private fun toResponse(category: QaCategory): CategoryResponse {
        return CategoryResponse(
            categoryId = category.id,
            parentId = category.parent?.id,
            code = category.code,
            name = category.name,
            description = category.description,
            depth = category.depth,
            path = category.path,
            sortOrder = category.sortOrder,
            isActive = category.isActive,
            isLeaf = category.isLeaf
        )
    }
}
