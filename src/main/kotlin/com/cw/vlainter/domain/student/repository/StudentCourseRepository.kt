package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentCourse
import org.springframework.data.jpa.repository.JpaRepository

interface StudentCourseRepository : JpaRepository<StudentCourse, Long> {
    fun findAllByUserIdAndIsArchivedFalseOrderByUpdatedAtDesc(userId: Long): List<StudentCourse>
}
