@file:Suppress("JpaDataSourceORMInspection")

package com.cw.vlainter.domain.student.entity

import com.cw.vlainter.domain.userFile.entity.UserFile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "student_course_materials")
class StudentCourseMaterial(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Suppress("unused")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    var course: StudentCourse,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_file_id", nullable = false)
    var userFile: UserFile,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
) {
    @PrePersist
    fun prePersist() {
        createdAt = OffsetDateTime.now()
    }
}
