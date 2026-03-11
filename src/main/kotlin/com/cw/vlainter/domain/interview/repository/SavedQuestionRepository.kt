package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.SavedQuestion
import com.cw.vlainter.domain.interview.entity.QaCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SavedQuestionRepository : JpaRepository<SavedQuestion, Long> {
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long): List<SavedQuestion>

    fun findByIdAndUser_Id(id: Long, userId: Long): SavedQuestion?

    fun existsByUser_IdAndSourceTurn_Id(userId: Long, sourceTurnId: Long): Boolean
    fun findTopByUser_IdAndQuestion_IdOrderByCreatedAtDesc(userId: Long, questionId: Long): SavedQuestion?
    fun findTopByUser_IdAndDocumentQuestion_IdOrderByCreatedAtDesc(userId: Long, documentQuestionId: Long): SavedQuestion?

    @Modifying(flushAutomatically = true)
    @Query("update SavedQuestion s set s.category = :target where s.category = :source")
    fun reassignCategory(
        @Param("source") source: QaCategory,
        @Param("target") target: QaCategory
    ): Int
}
