package com.cw.vlainter.domain.userFile.entity

import com.cw.vlainter.domain.user.entity.User
import jakarta.persistence.*
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
    @Column(name = "file_type", nullable = false)
    val fileType: FileType,

    @Column(name = "file_url", nullable = false, length = 500)
    val fileUrl: String,

    @Column(name = "file_name", nullable = false)
    val fileName: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)