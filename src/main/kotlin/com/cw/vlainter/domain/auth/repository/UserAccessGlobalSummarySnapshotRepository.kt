package com.cw.vlainter.domain.auth.repository

import com.cw.vlainter.domain.auth.entity.UserAccessGlobalSummarySnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface UserAccessGlobalSummarySnapshotRepository : JpaRepository<UserAccessGlobalSummarySnapshot, Long> {
    fun findByWindowDays(windowDays: Int): UserAccessGlobalSummarySnapshot?
}
