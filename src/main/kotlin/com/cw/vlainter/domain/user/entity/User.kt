package com.cw.vlainter.domain.user.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Suppress("JpaDataSourceORMInspection")
@Entity
@Table(name = "users")
class User (

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    var email: String,

    @Column(nullable = false)
    var password: String,

    @Column(nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_status")
    var status: UserStatus = UserStatus.ACTIVE,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, length = 20, columnDefinition = "user_role")
    var role: UserRole = UserRole.USER,

    @Column(nullable = false)
    var free: Int = 0,

    @Column(nullable = false)
    var point: Long = 0,

    @Column(name = "gemini_api_key_encrypted", length = 4096)
    var geminiApiKeyEncrypted: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
){
    @PrePersist
    fun prePersist() {
        createdAt = OffsetDateTime.now()
        updatedAt = OffsetDateTime.now()
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}
