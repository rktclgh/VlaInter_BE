package com.cw.vlainter.domain.userFile.entity

import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(
    name = "user_files",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_user_file", columnNames = ["user_id", "file_type"])
    ]
)
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

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)
