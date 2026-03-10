package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.InterviewTurn
import com.cw.vlainter.domain.interview.entity.QaCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InterviewTurnRepository : JpaRepository<InterviewTurn, Long> {
    fun findFirstBySession_IdAndUserAnswerIsNullOrderByTurnNoAsc(sessionId: Long): InterviewTurn?

    fun findAllBySession_IdOrderByTurnNoAsc(sessionId: Long): List<InterviewTurn>

    fun findByIdAndSession_User_Id(id: Long, userId: Long): InterviewTurn?

    @Modifying(flushAutomatically = true)
    @Query("update InterviewTurn t set t.category = :target where t.category = :source")
    fun reassignCategory(
        @Param("source") source: QaCategory,
        @Param("target") target: QaCategory
    ): Int
}
