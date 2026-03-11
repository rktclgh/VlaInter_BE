package com.cw.vlainter.domain.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(
    name = "user_access_global_summary_snapshots",
    indexes = [
        Index(name = "idx_user_access_global_summary_snapshots_window", columnList = "window_days", unique = true)
    ]
)
class UserAccessGlobalSummarySnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "window_days", nullable = false)
    var windowDays: Int = 7,

    @Column(name = "total_member_count", nullable = false)
    var totalMemberCount: Long = 0,

    @Column(name = "total_login_count", nullable = false)
    var totalLoginCount: Long = 0,

    @Column(name = "total_action_count", nullable = false)
    var totalActionCount: Long = 0,

    @Column(name = "average_login_count", nullable = false)
    var averageLoginCount: Double = 0.0,

    @Column(name = "average_action_count", nullable = false)
    var averageActionCount: Double = 0.0,

    @Column(name = "average_session_minutes", nullable = false)
    var averageSessionMinutes: Double = 0.0,

    @Column(name = "average_active_session_count", nullable = false)
    var averageActiveSessionCount: Double = 0.0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "daily_metrics", nullable = false, columnDefinition = "jsonb")
    var dailyMetricsJson: String = "[]",

    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    @PrePersist
    fun prePersist() {
        val now = OffsetDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}
