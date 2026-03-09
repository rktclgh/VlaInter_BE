package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.InterviewTurn
import org.springframework.data.jpa.repository.JpaRepository

interface InterviewTurnRepository : JpaRepository<InterviewTurn, Long> {
    fun findFirstBySession_IdAndUserAnswerIsNullOrderByTurnNoAsc(sessionId: Long): InterviewTurn?

    fun findAllBySession_IdOrderByTurnNoAsc(sessionId: Long): List<InterviewTurn>

    fun findByIdAndSession_User_Id(id: Long, userId: Long): InterviewTurn?
}
