package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.UserQuestionAttempt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserQuestionAttemptRepository : JpaRepository<UserQuestionAttempt, Long> {
    fun findAllByUser_IdAndQuestion_IdOrderByCreatedAtDesc(userId: Long, questionId: Long): List<UserQuestionAttempt>
    fun existsByTurn_Id(turnId: Long): Boolean

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserQuestionAttempt u where u.session.id = :sessionId or u.turn.session.id = :turnSessionId")
    fun deleteAllBySessionIdOrTurnSessionId(
        @Param("sessionId") sessionId: Long,
        @Param("turnSessionId") turnSessionId: Long
    ): Int
}
