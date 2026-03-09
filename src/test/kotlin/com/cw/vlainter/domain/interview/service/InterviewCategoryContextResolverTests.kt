@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.repository.QaCategoryRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class InterviewCategoryContextResolverTests {

    @Mock
    private lateinit var categoryRepository: QaCategoryRepository

    private fun resolver(): InterviewCategoryContextResolver = InterviewCategoryContextResolver(categoryRepository)

    @Test
    fun `categoryId가 있으면 해당 카테고리를 기준으로 컨텍스트를 반환한다`() {
        val actor = createUser()
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
            actor = actor,
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
        val actor = createUser()
        val techRoot = createCategory(id = 1L, code = "TECH", name = "기술", depth = 0, path = "TECH", isLeaf = false)
        val jobCategory = createCategory(id = 2L, parent = techRoot, code = "ACCOUNTANT", name = "회계사", depth = 1, path = "TECH/ACCOUNTANT", isLeaf = false)
        val skillCategory = createCategory(id = 3L, parent = jobCategory, code = "FINANCE", name = "재무회계", depth = 2, path = "TECH/ACCOUNTANT/FINANCE")

        given(categoryRepository.findByParentIsNullAndCodeAndDeletedAtIsNull("TECH")).willReturn(techRoot)
        given(categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(1L, "회계사")).willReturn(jobCategory)
        given(categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(2L, "재무회계")).willReturn(skillCategory)

        val resolved = resolver().resolve(
            actor = actor,
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
    fun `평문 직무 기술이 없고 createIfMissing이면 카테고리를 생성한다`() {
        val actor = createUser()
        val techRoot = createCategory(id = 1L, code = "TECH", name = "기술", depth = 0, path = "TECH", isLeaf = false)

        given(categoryRepository.findByParentIsNullAndCodeAndDeletedAtIsNull("TECH")).willReturn(techRoot)
        given(categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(1L, "회계사")).willReturn(null)
        given(categoryRepository.findByParent_IdAndNameIgnoreCaseAndDeletedAtIsNull(20L, "재무회계")).willReturn(null)
        given(categoryRepository.existsByParent_IdAndCodeAndDeletedAtIsNull(1L, "회계사".uppercase())).willReturn(false)
        given(categoryRepository.existsByParent_IdAndCodeAndDeletedAtIsNull(20L, "재무회계".uppercase())).willReturn(false)
        given(categoryRepository.save(any(QaCategory::class.java))).willAnswer { invocation ->
            val candidate = invocation.getArgument<QaCategory>(0)
            when (candidate.parent?.id) {
                1L -> createCategory(
                    id = 20L,
                    parent = techRoot,
                    code = candidate.code,
                    name = candidate.name,
                    depth = 1,
                    path = candidate.path,
                    isLeaf = candidate.isLeaf
                )
                20L -> createCategory(
                    id = 30L,
                    parent = createCategory(id = 20L, parent = techRoot, code = "ACCOUNTANT", name = "회계사", depth = 1, path = "TECH/ACCOUNTANT", isLeaf = false),
                    code = candidate.code,
                    name = candidate.name,
                    depth = 2,
                    path = candidate.path,
                    isLeaf = candidate.isLeaf
                )
                else -> candidate
            }
        }

        val resolved = resolver().resolve(
            actor = actor,
            categoryId = null,
            jobName = "회계사",
            skillName = "재무회계",
            createIfMissing = true
        )

        assertThat(resolved).isNotNull
        assertThat(resolved!!.jobName).isEqualTo("회계사")
        assertThat(resolved.skillName).isEqualTo("재무회계")
        assertThat(resolved.category.id).isEqualTo(30L)
        assertThat(resolved.category.parent?.id).isEqualTo(20L)
    }

    private fun createUser(): User = User(
        id = 1L,
        email = "tester@vlainter.com",
        password = "encoded-password",
        name = "Tester",
        status = UserStatus.ACTIVE,
        role = UserRole.USER,
        free = 0,
        point = 0
    )

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
