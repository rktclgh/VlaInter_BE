package com.cw.vlainter.domain.interview.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateCategoryRequest(
    val parentId: Long? = null,
    @field:Size(max = 80, message = "카테고리 코드는 80자 이하여야 합니다.")
    val code: String? = null,
    @field:NotBlank(message = "카테고리 이름은 필수입니다.")
    @field:Size(max = 120, message = "카테고리 이름은 120자 이하여야 합니다.")
    val name: String,
    val description: String? = null,
    val sortOrder: Int = 0
)

data class UpdateCategoryRequest(
    @field:Size(max = 120, message = "카테고리 이름은 120자 이하여야 합니다.")
    val name: String? = null,
    val description: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean? = null,
    val isLeaf: Boolean? = null
)

data class MoveCategoryRequest(
    val parentId: Long?
)

data class CategoryResponse(
    val categoryId: Long,
    val parentId: Long?,
    val code: String,
    val name: String,
    val description: String?,
    val depth: Int,
    val path: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val isLeaf: Boolean
)
