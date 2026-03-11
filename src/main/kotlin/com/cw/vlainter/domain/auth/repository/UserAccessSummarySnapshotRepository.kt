package com.cw.vlainter.domain.auth.repository

import com.cw.vlainter.domain.auth.entity.UserAccessSummarySnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface UserAccessSummarySnapshotRepository : JpaRepository<UserAccessSummarySnapshot, Long> {
    fun findByUser_IdAndWindowDays(userId: Long, windowDays: Int): UserAccessSummarySnapshot?
}
