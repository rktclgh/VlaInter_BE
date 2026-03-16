package com.cw.vlainter.domain.student.repository

import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import org.springframework.data.jpa.repository.JpaRepository

interface StudentCourseMaterialRepository : JpaRepository<StudentCourseMaterial, Long> {
    fun findAllByCourse_IdOrderByCreatedAtDesc(courseId: Long): List<StudentCourseMaterial>
    fun countByCourse_Id(courseId: Long): Long
}
