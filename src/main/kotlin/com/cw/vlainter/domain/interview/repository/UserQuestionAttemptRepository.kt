package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.UserQuestionAttempt
import org.springframework.data.jpa.repository.JpaRepository

interface UserQuestionAttemptRepository : JpaRepository<UserQuestionAttempt, Long> {
    fun findAllByUser_IdAndQuestion_IdOrderByCreatedAtDesc(userId: Long, questionId: Long): List<UserQuestionAttempt>
}
