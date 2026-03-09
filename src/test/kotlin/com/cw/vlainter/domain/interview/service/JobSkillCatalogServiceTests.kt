package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.entity.Job
import com.cw.vlainter.domain.interview.entity.Skill
import com.cw.vlainter.domain.interview.repository.JobRepository
import com.cw.vlainter.domain.interview.repository.SkillRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class JobSkillCatalogServiceTests {

    @Mock
    private lateinit var jobRepository: JobRepository

    @Mock
    private lateinit var skillRepository: SkillRepository

    @Test
    fun `ensureCatalog는 없는 직무 기술을 생성한다`() {
        given(jobRepository.findByNormalizedName("회계사")).willReturn(null)
        given(jobRepository.save(any(Job::class.java))).willReturn(
            Job(
                id = 10L,
                name = "회계사",
                normalizedName = "회계사",
                slug = "회계사"
            )
        )
        given(skillRepository.findByJob_IdAndNormalizedName(10L, "재무회계")).willReturn(null)
        given(skillRepository.save(any(Skill::class.java))).willAnswer { invocation ->
            val candidate = invocation.getArgument<Skill>(0)
            Skill(
                id = 20L,
                job = candidate.job,
                name = candidate.name,
                normalizedName = candidate.normalizedName,
                slug = candidate.slug
            )
        }

        val service = JobSkillCatalogService(jobRepository, skillRepository)
        val (job, skill) = service.ensureCatalog("회계사", "재무회계")

        assertThat(job.id).isEqualTo(10L)
        assertThat(job.name).isEqualTo("회계사")
        assertThat(skill.id).isEqualTo(20L)
        assertThat(skill.name).isEqualTo("재무회계")
        assertThat(skill.job.id).isEqualTo(10L)
    }

    @Test
    fun `listSkills는 직무명을 기준으로 기술 목록을 조회한다`() {
        val job = Job(
            id = 11L,
            name = "회계사",
            normalizedName = "회계사",
            slug = "회계사"
        )
        given(jobRepository.findByNormalizedName("회계사")).willReturn(job)
        given(skillRepository.findTop20ByJob_IdAndNormalizedNameContainingOrderByNameAsc(11L, "재무")).willReturn(
            listOf(
                Skill(
                    id = 101L,
                    job = job,
                    name = "재무회계",
                    normalizedName = "재무회계",
                    slug = "회계사-재무회계"
                )
            )
        )

        val service = JobSkillCatalogService(jobRepository, skillRepository)
        val items = service.listSkills(jobName = "회계사", query = "재무")

        assertThat(items).hasSize(1)
        assertThat(items[0].jobName).isEqualTo("회계사")
        assertThat(items[0].name).isEqualTo("재무회계")
    }
}
