package com.cw.vlainter.domain.users.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "users")
class Users (

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val email: String,

    @Column(nullable = false)
    val password: String,

    @Column(nullable = false, length = 100)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: UserStatus = UserStatus.ACTIVE,

    @Column(nullable = false)
    val free: Int = 0,

    @Column(nullable = false)
    val point: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)
