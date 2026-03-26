package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.InterviewSession
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository

interface InterviewSessionRepository : JpaRepository<InterviewSession, Long> {
    fun findByIdAndUser_Id(id: Long, userId: Long): InterviewSession?
    fun countByUser_Id(userId: Long): Long
    fun countByUser_IdAndStatus(userId: Long, status: com.cw.vlainter.domain.interview.entity.InterviewStatus): Long

    fun findAllByUser_IdAndModeInOrderByCreatedAtDesc(userId: Long, modes: Collection<com.cw.vlainter.domain.interview.entity.InterviewMode>): List<InterviewSession>

    fun findAllByUser_IdAndModeInOrderByCreatedAtDesc(
        userId: Long,
        modes: Collection<com.cw.vlainter.domain.interview.entity.InterviewMode>,
        pageable: Pageable
    ): Slice<InterviewSession>

    fun findAllByUser_IdAndModeInOrderByUpdatedAtDescCreatedAtDesc(
        userId: Long,
        modes: Collection<com.cw.vlainter.domain.interview.entity.InterviewMode>
    ): List<InterviewSession>
}
