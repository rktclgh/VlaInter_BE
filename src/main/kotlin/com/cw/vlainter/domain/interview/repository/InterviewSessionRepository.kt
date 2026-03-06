package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.InterviewSession
import org.springframework.data.jpa.repository.JpaRepository

interface InterviewSessionRepository : JpaRepository<InterviewSession, Long> {
    fun findByIdAndUser_Id(id: Long, userId: Long): InterviewSession?
}
