@file:Suppress("JpaDataSourceORMInspection", "unused")

package com.cw.vlainter.domain.interview.entity

import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

@Entity
@Table(
    name = "jobs",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_jobs_normalized_name", columnNames = ["normalized_name"])
    ]
)
class Job(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "name", nullable = false, length = 120)
    var name: String,

    @Column(name = "normalized_name", nullable = false, length = 120, unique = true)
    var normalizedName: String,

    @Column(name = "slug", length = 140)
    var slug: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    var createdBy: User? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    var updatedBy: User? = null,

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
        normalizedName = name.trim().lowercase()
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = OffsetDateTime.now()
        normalizedName = name.trim().lowercase()
    }
}
