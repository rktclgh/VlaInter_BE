package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaCategory
import org.springframework.data.jpa.repository.JpaRepository

interface QaCategoryRepository : JpaRepository<QaCategory, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): QaCategory?

    fun findAllByPathStartingWithAndDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc(pathPrefix: String): List<QaCategory>

    fun findAllByParent_IdAndDeletedAtIsNullOrderBySortOrderAsc(parentId: Long): List<QaCategory>

    fun findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc(): List<QaCategory>

    fun existsByParent_IdAndCodeAndDeletedAtIsNull(parentId: Long, code: String): Boolean
}
