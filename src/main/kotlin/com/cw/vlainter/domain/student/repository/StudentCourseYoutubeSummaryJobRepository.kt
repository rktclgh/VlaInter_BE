package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentCourseYoutubeSummaryJob
import com.cw.vlainter.domain.student.entity.StudentCourseYoutubeSummaryJobStatus
import org.springframework.data.jpa.repository.JpaRepository

interface StudentCourseYoutubeSummaryJobRepository : JpaRepository<StudentCourseYoutubeSummaryJob, Long> {
    fun findAllByCourseIdAndUserIdOrderByCreatedAtDesc(courseId: Long, userId: Long): List<StudentCourseYoutubeSummaryJob>
    fun deleteAllByCourseIdAndUserId(courseId: Long, userId: Long)
    fun existsByCourseIdAndUserIdAndStatusIn(
        courseId: Long,
        userId: Long,
        statuses: Collection<StudentCourseYoutubeSummaryJobStatus>
    ): Boolean
}
