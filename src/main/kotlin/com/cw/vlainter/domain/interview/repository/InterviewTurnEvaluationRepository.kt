package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InterviewTurnEvaluationRepository : JpaRepository<InterviewTurnEvaluation, Long> {
    fun findByTurn_Id(turnId: Long): InterviewTurnEvaluation?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InterviewTurnEvaluation e where e.turn.session.id = :sessionId")
    fun deleteAllByTurnSessionId(@Param("sessionId") sessionId: Long): Int
}
