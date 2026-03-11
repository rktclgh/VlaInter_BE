package com.cw.vlainter.domain.user.dto

import java.time.OffsetDateTime

data class AdminMemberAccessGlobalSummaryResponse(
    val windowDays: Int,
    val totalMemberCount: Long,
    val totalLoginCount: Long,
    val totalActionCount: Long,
    val averageLoginCount: Double,
    val averageActionCount: Double,
    val averageSessionMinutes: Double,
    val averageActiveSessionCount: Double,
    val calculatedAt: OffsetDateTime?,
    val dailyMetrics: List<AdminMemberAccessGlobalDailyMetricResponse>
)

data class AdminMemberAccessGlobalDailyMetricResponse(
    val date: String,
    val averageLoginCount: Double,
    val averageActionCount: Double,
    val averageSessionMinutes: Double
)
