package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.DocumentQuestion
import org.springframework.data.jpa.repository.JpaRepository

interface DocumentQuestionRepository : JpaRepository<DocumentQuestion, Long> {
    fun findAllBySetIdOrderByQuestionNoAsc(setId: Long): List<DocumentQuestion>
    fun findByIdAndUserId(id: Long, userId: Long): DocumentQuestion?
}
