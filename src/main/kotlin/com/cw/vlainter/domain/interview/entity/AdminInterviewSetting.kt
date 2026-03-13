@file:Suppress("JpaDataSourceORMInspection")

package com.cw.vlainter.domain.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "admin_interview_settings")
class AdminInterviewSetting(
    @Suppress("unused")
    @Id
    @Column(name = "setting_key", nullable = false, length = 100)
    val settingKey: String,

    @Column(name = "setting_value", nullable = false, length = 100)
    var settingValue: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    @PrePersist
    fun prePersist() {
        updatedAt = OffsetDateTime.now()
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}
