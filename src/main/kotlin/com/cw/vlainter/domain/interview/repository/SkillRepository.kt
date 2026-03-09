package com.cw.vlainter.domain.interview.repository

import com.cw.vlainter.domain.interview.entity.Skill
import org.springframework.data.jpa.repository.JpaRepository

interface SkillRepository : JpaRepository<Skill, Long> {
    fun findByJob_IdAndNormalizedName(jobId: Long, normalizedName: String): Skill?

    fun findTop20ByJob_IdAndNormalizedNameContainingOrderByNameAsc(jobId: Long, keyword: String): List<Skill>

    fun findTop20ByJob_IdOrderByNameAsc(jobId: Long): List<Skill>

    fun findTop20ByNormalizedNameContainingOrderByNameAsc(keyword: String): List<Skill>

    fun findTop20ByOrderByNameAsc(): List<Skill>
}
