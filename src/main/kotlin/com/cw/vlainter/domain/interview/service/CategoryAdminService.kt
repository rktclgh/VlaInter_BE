package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.CategoryResponse
import com.cw.vlainter.domain.interview.dto.CreateCategoryRequest
import com.cw.vlainter.domain.interview.dto.MergeCategoryRequest
import com.cw.vlainter.domain.interview.dto.MoveCategoryRequest
import com.cw.vlainter.domain.interview.dto.UpdateCategoryRequest
import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.repository.InterviewTurnRepository
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.SavedQuestionRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class CategoryAdminService(
    private val categoryRepository: QaCategoryRepository,
    private val questionRepository: QaQuestionRepository,
    private val savedQuestionRepository: SavedQuestionRepository,
    private val interviewTurnRepository: InterviewTurnRepository,
    private val userRepository: UserRepository
) {
    @Transactional
    fun getActiveCategoryTree(): List<CategoryResponse> {
        ensureCommonJobsForBranches()
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
        validateUniqueName(depth, normalizedName)

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
        if (saved.depth == 0) {
            ensureCommonJob(saved, actor)
        }

        return toResponse(saved)
    }

    @Transactional
    fun updateCategory(principal: AuthPrincipal, categoryId: Long, request: UpdateCategoryRequest): CategoryResponse {
        ensureAdmin(principal)
        val actor = loadUser(principal.userId)
        val category = findCategory(categoryId)

        request.name?.let {
            val normalizedName = it.trim()
            if (normalizedName.isBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "카테고리 이름은 필수입니다.")
            }
            validateUniqueName(category.depth, normalizedName, category.id)
            if (hasDuplicateName(category.parent, normalizedName, category.id)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "같은 위치에 동일한 이름의 카테고리가 이미 존재합니다.")
            }
            category.name = normalizedName
        }
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

        categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullOrderByDepthAscSortOrderAsc("$oldPath/")
            .forEach { child ->
            val suffix = child.path.removePrefix(oldPath)
            child.path = "${category.path}$suffix"
            child.depth = child.path.split("/").count { it.isNotBlank() } - 1
            child.updatedBy = actor
        }

        return toResponse(category)
    }

    @Transactional
    fun mergeCategory(principal: AuthPrincipal, categoryId: Long, request: MergeCategoryRequest): CategoryResponse {
        ensureAdmin(principal)
        val actor = loadUser(principal.userId)
        val source = findActiveCategory(categoryId)
        val target = findActiveCategory(request.targetCategoryId)

        if (source.id == target.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 카테고리끼리는 통합할 수 없습니다.")
        }
        if (source.depth != target.depth) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 depth 카테고리끼리만 통합할 수 있습니다.")
        }

        mergeCategoryNodes(source, target, actor)
        refreshLeafState(source.parent, actor)
        refreshLeafState(target, actor)
        return toResponse(target)
    }

    @Transactional
    fun deleteCategory(principal: AuthPrincipal, categoryId: Long) {
        ensureAdmin(principal)
        val actor = loadUser(principal.userId)
        val category = findCategory(categoryId)
        val now = OffsetDateTime.now()

        val descendants =
            categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullOrderByDepthDescSortOrderDesc("${category.path}/")
        (listOf(category) + descendants).forEach { item ->
            item.deletedAt = now
            item.isActive = false
            item.updatedBy = actor
        }

        val parent = category.parent
        if (parent != null && !categoryRepository.existsByParent_IdAndDeletedAtIsNull(parent.id)) {
            parent.isLeaf = true
            parent.updatedBy = actor
        }
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

    private fun hasDuplicateName(parent: QaCategory?, name: String, excludingId: Long? = null): Boolean {
        val duplicate = if (parent == null) {
            categoryRepository.findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc()
                .firstOrNull { it.parent == null && it.name.equals(name, ignoreCase = true) }
        } else {
            categoryRepository.findAllByParent_IdAndDeletedAtIsNullOrderBySortOrderAsc(parent.id)
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        return duplicate != null && duplicate.id != excludingId
    }

    private fun validateUniqueName(depth: Int, normalizedName: String, excludingId: Long? = null) {
        val isBranchCommonJob = depth == 1 && normalizedName.equals("공통", ignoreCase = true)
        if (isBranchCommonJob) return
        val duplicate = categoryRepository.findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc()
            .firstOrNull {
                it.depth == depth &&
                    it.name.equals(normalizedName, ignoreCase = true) &&
                    it.id != excludingId
            }
        if (duplicate != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "같은 depth에 동일한 이름의 카테고리가 이미 존재합니다. 중복 생성은 허용되지 않습니다: $normalizedName"
            )
        }
    }

    private fun ensureCommonJobsForBranches() {
        categoryRepository.findAllByDepthAndDeletedAtIsNullAndIsActiveTrueOrderBySortOrderAsc(0)
            .forEach { branch ->
                ensureCommonJob(branch, branch.updatedBy ?: branch.createdBy)
            }
    }

    private fun ensureCommonJob(branch: QaCategory, actor: com.cw.vlainter.domain.user.entity.User?) {
        if (categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(branch.id, "공통") != null) {
            return
        }
        try {
            val code = allocateUniqueCode(branch.id, normalizeCode("공통"))
            categoryRepository.save(
                QaCategory(
                    parent = branch,
                    code = code,
                    name = "공통",
                    description = "계열 공통 직무",
                    depth = 1,
                    path = "${branch.path}/$code",
                    sortOrder = -100,
                    isActive = true,
                    isLeaf = true,
                    createdBy = actor,
                    updatedBy = actor
                )
            )
        } catch (ex: org.springframework.dao.DataIntegrityViolationException) {
            if (categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(branch.id, "공통") != null) {
                return
            }
            throw ex
        }
        if (branch.isLeaf) {
            branch.isLeaf = false
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

    private fun mergeCategoryNodes(source: QaCategory, target: QaCategory, actor: com.cw.vlainter.domain.user.entity.User) {
        if (source.id == target.id) return

        questionRepository.reassignCategory(source, target)
        savedQuestionRepository.reassignCategory(source, target)
        interviewTurnRepository.reassignCategory(source, target)

        val sourceChildren = categoryRepository.findAllByParent_IdAndDeletedAtIsNullOrderBySortOrderAsc(source.id)
        sourceChildren.forEach { child ->
            val matchedTargetChild = categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(target.id, child.name)
            if (matchedTargetChild != null && matchedTargetChild.id != child.id) {
                mergeCategoryNodes(child, matchedTargetChild, actor)
            } else {
                moveCategoryNode(child, target, actor)
            }
        }

        source.deletedAt = OffsetDateTime.now()
        source.isActive = false
        source.updatedBy = actor
    }

    private fun moveCategoryNode(category: QaCategory, newParent: QaCategory, actor: com.cw.vlainter.domain.user.entity.User) {
        val oldPath = category.path
        val nextCode = if (existsCode(newParent.id, category.code)) {
            allocateUniqueCode(newParent.id, normalizeCode(category.code))
        } else {
            category.code
        }

        category.code = nextCode
        category.parent = newParent
        category.depth = newParent.depth + 1
        category.path = "${newParent.path}/$nextCode"
        category.updatedBy = actor
        newParent.isLeaf = false
        newParent.updatedBy = actor

        categoryRepository.findAllByPathStartingWithAndDeletedAtIsNullOrderByDepthAscSortOrderAsc("$oldPath/")
            .forEach { child ->
                val suffix = child.path.removePrefix(oldPath)
                child.path = "${category.path}$suffix"
                child.depth = child.path.split("/").count { it.isNotBlank() } - 1
                child.updatedBy = actor
            }
    }

    private fun refreshLeafState(category: QaCategory?, actor: com.cw.vlainter.domain.user.entity.User) {
        if (category == null) return
        val hasChildren = categoryRepository.existsByParent_IdAndDeletedAtIsNull(category.id)
        category.isLeaf = !hasChildren
        category.updatedBy = actor
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
            isLeaf = category.isLeaf,
            createdByUserId = category.createdBy?.id,
            createdByName = category.createdBy?.name,
            createdByEmail = category.createdBy?.email,
            createdByStatus = category.createdBy?.status
        )
    }

    private fun depthLabel(depth: Int): String = when (depth) {
        0 -> "계열"
        1 -> "직무"
        2 -> "기술"
        else -> "기타"
    }
}
