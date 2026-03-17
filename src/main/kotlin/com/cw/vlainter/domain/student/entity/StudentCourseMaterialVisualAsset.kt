@file:Suppress("JpaDataSourceORMInspection")

package com.cw.vlainter.domain.student.entity

import com.cw.vlainter.domain.student.dto.StudentCourseMaterialVisualAssetType
import com.cw.vlainter.domain.userFile.entity.UserFile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Suppress("unused")
@Entity
@Table(name = "student_course_material_visual_assets")
class StudentCourseMaterialVisualAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", nullable = false)
    var material: StudentCourseMaterial,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_file_id", nullable = false)
    var userFile: UserFile,

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 40)
    var assetType: StudentCourseMaterialVisualAssetType,

    @Column(name = "asset_order", nullable = false)
    var assetOrder: Int,

    @Column(name = "label", nullable = false, length = 255)
    var label: String,

    @Column(name = "storage_key", nullable = false, length = 1000)
    var storageKey: String,

    @Column(name = "content_type", length = 255)
    var contentType: String? = null,

    @Column(name = "page_no")
    var pageNo: Int? = null,

    @Column(name = "slide_no")
    var slideNo: Int? = null,

    @Column(name = "width")
    var width: Int? = null,

    @Column(name = "height")
    var height: Int? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
) {
    @PrePersist
    fun prePersist() {
        createdAt = OffsetDateTime.now()
    }
}
