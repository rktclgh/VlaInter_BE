package com.cw.vlainter.domain.auth.entity

import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

//noinspection JpaDataSourceORMInspection
@Suppress("unused")
@Entity
@Table(
    name = "user_access_summary_snapshots",
    indexes = [
        Index(name = "idx_user_access_summary_snapshots_user_window", columnList = "user_id, window_days", unique = true)
    ]
)
class UserAccessSummarySnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val user: User,

    @Column(name = "window_days", nullable = false)
    var windowDays: Int = 7,

    @Column(name = "recent_login_count", nullable = false)
    var recentLoginCount: Int = 0,

    @Column(name = "active_session_count", nullable = false)
    var activeSessionCount: Int = 0,

    @Column(name = "total_action_count", nullable = false)
    var totalActionCount: Long = 0,

    @Column(name = "average_action_count", nullable = false)
    var averageActionCount: Double = 0.0,

    @Column(name = "average_session_minutes", nullable = false)
    var averageSessionMinutes: Long = 0,

    @Column(name = "last_login_at")
    var lastLoginAt: OffsetDateTime? = null,

    @Column(name = "completed_interview_count", nullable = false)
    var completedInterviewCount: Long = 0,

    @Column(name = "total_interview_count", nullable = false)
    var totalInterviewCount: Long = 0,

    @Column(name = "interview_completion_rate", nullable = false)
    var interviewCompletionRate: Double = 0.0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "daily_login_counts", nullable = false, columnDefinition = "jsonb")
    var dailyLoginCountsJson: String = "[]",

    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
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
