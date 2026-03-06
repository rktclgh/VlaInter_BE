package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.DocumentQuestionSet
import org.springframework.data.jpa.repository.JpaRepository

interface DocumentQuestionSetRepository : JpaRepository<DocumentQuestionSet, Long> {
    fun findAllByUserIdAndDocumentFileIdOrderByCreatedAtDesc(userId: Long, documentFileId: Long): List<DocumentQuestionSet>
}
