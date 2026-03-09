@file:Suppress("JpaDataSourceORMInspection", "unused")

package com.cw.vlainter.domain.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "jobs")
class Job(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "name", nullable = false, length = 120)
    var name: String,

    @Column(name = "normalized_name", nullable = false, length = 120)
    var normalizedName: String,

    @Column(name = "slug", length = 140)
    var slug: String? = null,

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
