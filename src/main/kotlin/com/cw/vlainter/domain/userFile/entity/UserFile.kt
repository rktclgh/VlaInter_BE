package com.cw.vlainter.domain.userFile.entity

import com.cw.vlainter.domain.user.entity.User
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
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "user_files")
class UserFile(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "file_type", nullable = false, columnDefinition = "file_type")
    val fileType: FileType,

    @Column(name = "file_url", nullable = false, length = 500)
    val fileUrl: String,

    @Column(name = "file_name", nullable = false)
    val fileName: String,

    @Column(name = "original_file_name", nullable = false)
    val originalFileName: String,

    @Column(name = "storage_file_name", nullable = false)
    val storageFileName: String,

    @Column(name = "storage_key", nullable = false, length = 1000)
    val storageKey: String,

    @Column(name = "content_type", length = 255)
    val contentType: String? = null,

    @Column(name = "file_size_bytes")
    val fileSizeBytes: Long? = null,

    @Column(name = "version_no", nullable = false)
    val versionNo: Int = 1,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
