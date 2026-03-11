package com.cw.vlainter.domain.auth.entity

import com.cw.vlainter.domain.auth.service.AuthProviderType
import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.OffsetDateTime

//noinspection JpaDataSourceORMInspection
@Suppress("unused")
@Entity
@Table(
    name = "user_access_session_logs",
    indexes = [
        Index(name = "idx_user_access_session_logs_user_login_at", columnList = "user_id, login_at"),
        Index(name = "idx_user_access_session_logs_session_id", columnList = "session_id", unique = true)
    ]
)
class UserAccessSessionLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val user: User,

    @Column(name = "session_id", nullable = false, length = 64, unique = true)
    var sessionId: String,

    @Column(name = "email_snapshot", nullable = false, length = 255)
    var emailSnapshot: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    var authProvider: AuthProviderType,

    @Column(name = "login_at", nullable = false)
    var loginAt: OffsetDateTime,

    @Column(name = "last_activity_at")
    var lastActivityAt: OffsetDateTime? = null,

    @Column(name = "logout_at")
    var logoutAt: OffsetDateTime? = null,

    @Column(name = "action_count", nullable = false)
    var actionCount: Long = 0,

    @Column(name = "ip_address", length = 100)
    var ipAddress: String? = null,

    @Column(name = "user_agent", length = 1000)
    var userAgent: String? = null,

    @Column(name = "last_method", length = 16)
    var lastMethod: String? = null,

    @Column(name = "last_path", length = 500)
    var lastPath: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

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
