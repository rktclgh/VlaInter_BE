package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.SavedQuestion
import org.springframework.data.jpa.repository.JpaRepository

interface SavedQuestionRepository : JpaRepository<SavedQuestion, Long> {
    fun findAllByUser_IdOrderByCreatedAtDesc(userId: Long): List<SavedQuestion>

    fun findByIdAndUser_Id(id: Long, userId: Long): SavedQuestion?

    fun existsByUser_IdAndSourceTurn_Id(userId: Long, sourceTurnId: Long): Boolean
}
