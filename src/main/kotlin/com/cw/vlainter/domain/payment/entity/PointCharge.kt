package com.cw.vlainter.domain.payment.entity

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
import java.time.OffsetDateTime

@Entity
@Table(
    name = "point_charges",
    indexes = [
        Index(name = "idx_point_charge_user_id", columnList = "user_id"),
        Index(name = "idx_point_charge_status", columnList = "status")
    ]
)
class PointCharge(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, unique = true, length = 120)
    val merchantUid: String,

    @Column(unique = true, length = 120)
    var impUid: String? = null,

    @Column(nullable = false)
    val requestedAmount: Int,

    @Column(nullable = false)
    val rewardPoint: Long,

    @Column(nullable = false)
    var paidAmount: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PointChargeStatus = PointChargeStatus.READY,

    @Column(length = 255)
    var failureReason: String? = null,

    @Column(name = "paid_at")
    var paidAt: OffsetDateTime? = null,

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
