package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.JobSummaryResponse
import com.cw.vlainter.domain.interview.dto.SkillSummaryResponse
import com.cw.vlainter.domain.interview.entity.Job
import com.cw.vlainter.domain.interview.entity.Skill
import com.cw.vlainter.domain.interview.repository.JobRepository
import com.cw.vlainter.domain.interview.repository.SkillRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JobSkillCatalogService(
    private val jobRepository: JobRepository,
    private val skillRepository: SkillRepository
) {
    @Transactional
    fun ensureJob(jobName: String): Job {
        val normalizedJob = normalizeName(jobName)
        return jobRepository.findByNormalizedName(normalizedJob)
            ?: jobRepository.save(
                Job(
                    name = jobName.trim(),
                    normalizedName = normalizedJob,
                    slug = toSlug(jobName)
                )
            )
    }

    @Transactional
    fun ensureCatalog(jobName: String, skillName: String): Pair<Job, Skill> {
        val normalizedSkill = normalizeName(skillName)
        val job = ensureJob(jobName)

        val skill = skillRepository.findByJob_IdAndNormalizedName(job.id, normalizedSkill)
            ?: skillRepository.save(
                Skill(
                    job = job,
                    name = skillName.trim(),
                    normalizedName = normalizedSkill,
                    slug = toSlug("${job.name}-${skillName.trim()}")
                )
            )

        return job to skill
    }

    @Transactional(readOnly = true)
    fun listJobs(query: String?): List<JobSummaryResponse> {
        val normalized = query?.trim()?.lowercase().orEmpty()
        val jobs = if (normalized.isBlank()) {
            jobRepository.findTop20ByOrderByNameAsc()
        } else {
            jobRepository.findTop20ByNormalizedNameContainingOrderByNameAsc(normalized)
        }
        return jobs.map { JobSummaryResponse(jobId = it.id, name = it.name) }
    }

    @Transactional(readOnly = true)
    fun listSkills(jobName: String?, query: String?): List<SkillSummaryResponse> {
        val normalizedJob = jobName?.trim()?.lowercase().orEmpty()
        val normalizedQuery = query?.trim()?.lowercase().orEmpty()

        val skills = when {
            normalizedJob.isNotBlank() && normalizedQuery.isNotBlank() -> {
                val job = jobRepository.findByNormalizedName(normalizedJob) ?: return emptyList()
                skillRepository.findTop20ByJob_IdAndNormalizedNameContainingOrderByNameAsc(job.id, normalizedQuery)
            }
            normalizedJob.isNotBlank() -> {
                val job = jobRepository.findByNormalizedName(normalizedJob) ?: return emptyList()
                skillRepository.findTop20ByJob_IdOrderByNameAsc(job.id)
            }
            normalizedQuery.isNotBlank() -> {
                skillRepository.findTop20ByNormalizedNameContainingOrderByNameAsc(normalizedQuery)
            }
            else -> skillRepository.findTop20ByOrderByNameAsc()
        }

        return skills.map {
            SkillSummaryResponse(
                skillId = it.id,
                jobId = it.job.id,
                jobName = it.job.name,
                name = it.name
            )
        }
    }

    private fun normalizeName(value: String): String = value.trim().lowercase()

    private fun toSlug(value: String): String {
        val normalized = value.trim().lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^0-9a-z가-힣-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        return normalized.take(140).ifBlank { "custom-${System.currentTimeMillis()}" }
    }
}
