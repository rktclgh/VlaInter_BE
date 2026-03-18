package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentExamSession
import org.springframework.data.jpa.repository.JpaRepository

interface StudentExamSessionRepository : JpaRepository<StudentExamSession, Long> {
    fun findAllByCourseIdOrderByCreatedAtDesc(courseId: Long): List<StudentExamSession>
}
