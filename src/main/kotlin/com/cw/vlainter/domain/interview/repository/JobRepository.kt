package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.Job
import org.springframework.data.jpa.repository.JpaRepository

interface JobRepository : JpaRepository<Job, Long> {
    fun findByNormalizedName(normalizedName: String): Job?

    fun findTop20ByNormalizedNameContainingOrderByNameAsc(keyword: String): List<Job>

    fun findTop20ByOrderByNameAsc(): List<Job>
}
