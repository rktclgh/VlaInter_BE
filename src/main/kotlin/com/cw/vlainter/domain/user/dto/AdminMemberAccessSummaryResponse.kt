package com.cw.vlainter.domain.user.dto

import java.time.OffsetDateTime

data class AdminMemberAccessSummaryResponse(
    val recentLoginCount: Int,
    val activeSessionCount: Int,
    val totalActionCount: Long,
    val averageActionCount: Double,
    val averageSessionMinutes: Long,
    val lastLoginAt: OffsetDateTime?,
    val lastLoginIpAddress: String?,
    val completedInterviewCount: Long,
    val totalInterviewCount: Long,
    val interviewCompletionRate: Double,
    val dailyLoginCounts: List<AdminMemberAccessDailyCountResponse>,
    val calculatedAt: OffsetDateTime?
)
