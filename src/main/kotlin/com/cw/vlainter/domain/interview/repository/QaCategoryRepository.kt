package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.QaCategory
import org.springframework.data.jpa.repository.JpaRepository

interface QaCategoryRepository : JpaRepository<QaCategory, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): QaCategory?

    fun findByParentIsNullAndCodeAndDeletedAtIsNull(code: String): QaCategory?

    fun findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(parentId: Long, name: String): QaCategory?

    fun findAllByPathStartingWithAndDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc(pathPrefix: String): List<QaCategory>

    fun findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc(): List<QaCategory>

    fun existsByParent_IdAndCodeAndDeletedAtIsNull(parentId: Long, code: String): Boolean

    fun existsByParentIsNullAndCodeAndDeletedAtIsNull(code: String): Boolean

    fun existsByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(parentId: Long, name: String): Boolean

    fun existsByParentIsNullAndNameIgnoreCaseAndDeletedAtIsNull(name: String): Boolean
}
