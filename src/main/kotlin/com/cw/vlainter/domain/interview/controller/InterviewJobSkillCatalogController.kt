package com.cw.vlainter.domain.interview.controller

import com.cw.vlainter.domain.interview.dto.CreateJobRequest
import com.cw.vlainter.domain.interview.dto.CreateSkillRequest
import com.cw.vlainter.domain.interview.dto.JobSummaryResponse
import com.cw.vlainter.domain.interview.dto.SkillSummaryResponse
import com.cw.vlainter.domain.interview.service.JobSkillCatalogService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interview/catalog")
class InterviewJobSkillCatalogController(
    private val jobSkillCatalogService: JobSkillCatalogService
) {
    @GetMapping("/jobs")
    fun getJobs(
        @RequestParam(required = false) query: String?
    ): ResponseEntity<List<JobSummaryResponse>> {
        return ResponseEntity.ok(jobSkillCatalogService.listJobs(query))
    }

    @PostMapping("/jobs")
    fun createJob(
        @Valid @RequestBody request: CreateJobRequest
    ): ResponseEntity<JobSummaryResponse> {
        val job = jobSkillCatalogService.ensureJob(request.name)
        return ResponseEntity.ok(JobSummaryResponse(jobId = job.id, name = job.name))
    }

    @GetMapping("/skills")
    fun getSkills(
        @RequestParam(required = false) jobName: String?,
        @RequestParam(required = false) query: String?
    ): ResponseEntity<List<SkillSummaryResponse>> {
        return ResponseEntity.ok(jobSkillCatalogService.listSkills(jobName, query))
    }

    @PostMapping("/skills")
    fun createSkill(
        @Valid @RequestBody request: CreateSkillRequest
    ): ResponseEntity<SkillSummaryResponse> {
        val (job, skill) = jobSkillCatalogService.ensureCatalog(request.jobName, request.skillName)
        return ResponseEntity.ok(
            SkillSummaryResponse(
                skillId = skill.id,
                jobId = job.id,
                jobName = job.name,
                name = skill.name
            )
        )
    }
}
