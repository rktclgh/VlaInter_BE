@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

@ExtendWith(MockitoExtension::class)
class InterviewCategoryContextResolverTests {

    @Mock
    private lateinit var categoryRepository: QaCategoryRepository

    private fun resolver(): InterviewCategoryContextResolver = InterviewCategoryContextResolver(categoryRepository)

    @Test
    fun `categoryId가 있으면 해당 카테고리를 기준으로 컨텍스트를 반환한다`() {
        val jobCategory = createCategory(id = 11L, code = "JOB", name = "기존직무", depth = 1, path = "TECH/JOB")
        val skillCategory = createCategory(
            id = 22L,
            parent = jobCategory,
            code = "SKILL",
            name = "기존기술",
            depth = 2,
            path = "TECH/JOB/SKILL"
        )
        given(categoryRepository.findByIdAndDeletedAtIsNull(22L)).willReturn(skillCategory)

        val resolved = resolver().resolve(
            categoryId = 22L,
            jobName = "회계사",
            skillName = "재무회계",
            createIfMissing = false
        )

        assertThat(resolved).isNotNull
        assertThat(resolved!!.category.id).isEqualTo(22L)
        assertThat(resolved.jobName).isEqualTo("회계사")
        assertThat(resolved.skillName).isEqualTo("재무회계")
    }

    @Test
    fun `평문 직무 기술이 기존 카테고리에 있으면 생성 없이 반환한다`() {
        val techRoot = createCategory(id = 1L, code = "TECH", name = "기술", depth = 0, path = "TECH", isLeaf = false)
        val jobCategory = createCategory(id = 2L, parent = techRoot, code = "ACCOUNTANT", name = "회계사", depth = 1, path = "TECH/ACCOUNTANT", isLeaf = false)
        val skillCategory = createCategory(id = 3L, parent = jobCategory, code = "FINANCE", name = "재무회계", depth = 2, path = "TECH/ACCOUNTANT/FINANCE")

        given(categoryRepository.findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc())
            .willReturn(listOf(techRoot, jobCategory, skillCategory))

        val resolved = resolver().resolve(
            categoryId = null,
            jobName = "회계사",
            skillName = "재무회계",
            createIfMissing = false
        )

        assertThat(resolved).isNotNull
        assertThat(resolved!!.category.id).isEqualTo(3L)
        assertThat(resolved.jobName).isEqualTo("회계사")
        assertThat(resolved.skillName).isEqualTo("재무회계")
    }

    @Test
    fun `평문 직무 기술이 없고 createIfMissing이면 먼저 트리에서 생성하라고 안내한다`() {
        val techRoot = createCategory(id = 1L, code = "TECH", name = "기술", depth = 0, path = "TECH", isLeaf = false)
        val jobCategory = createCategory(id = 20L, parent = techRoot, code = "ACCOUNTANT", name = "회계사", depth = 1, path = "TECH/ACCOUNTANT", isLeaf = false)

        given(categoryRepository.findAllByDeletedAtIsNullAndIsActiveTrueOrderByDepthAscSortOrderAsc())
            .willReturn(listOf(techRoot, jobCategory))

        val exception = assertThrows<ResponseStatusException> {
            resolver().resolve(
                categoryId = null,
                jobName = "회계사",
                skillName = "재무회계",
                createIfMissing = true
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.reason).contains("기술 카테고리를 찾을 수 없습니다")
    }
    private fun createCategory(
        id: Long,
        parent: QaCategory? = null,
        code: String,
        name: String,
        depth: Int,
        path: String,
        isLeaf: Boolean = true
    ): QaCategory = QaCategory(
        id = id,
        parent = parent,
        code = code,
        name = name,
        description = null,
        depth = depth,
        path = path,
        sortOrder = 100,
        isActive = true,
        isLeaf = isLeaf
    )
}
