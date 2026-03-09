package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.InterviewTurnEvaluation
import org.springframework.data.jpa.repository.JpaRepository

interface InterviewTurnEvaluationRepository : JpaRepository<InterviewTurnEvaluation, Long> {
    fun findByTurn_Id(turnId: Long): InterviewTurnEvaluation?
}
