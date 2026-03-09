package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
import com.cw.vlainter.domain.interview.entity.QuestionSourceTag
import com.cw.vlainter.domain.interview.repository.QaQuestionRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetItemRepository
import com.cw.vlainter.domain.interview.repository.QaQuestionSetRepository
import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class QuestionSetServiceTests {

    @Mock
    private lateinit var categoryContextResolver: InterviewCategoryContextResolver

    @Mock
    private lateinit var jobSkillCatalogService: JobSkillCatalogService

    @Mock
    private lateinit var questionSetRepository: QaQuestionSetRepository

    @Mock
    private lateinit var questionRepository: QaQuestionRepository

    @Mock
    private lateinit var questionSetItemRepository: QaQuestionSetItemRepository

    @Mock
    private lateinit var userRepository: UserRepository

    @Test
    fun `addQuestionToSet는 직무 기술 평문을 저장하고 응답에 반영한다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "재무회계 세트",
            jobName = null,
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        val category = createCategory()
        val resolvedContext = InterviewCategoryContextResolver.ResolvedCategoryContext(
            category = category,
            jobName = "회계사",
            skillName = "재무회계"
        )

        given(userRepository.findById(7L)).willReturn(Optional.of(actor))
        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(
            categoryContextResolver.resolve(
                actor = actor,
                categoryId = null,
                jobName = "회계사",
                skillName = "재무회계",
                createIfMissing = true
            )
        ).willReturn(resolvedContext)
        given(questionRepository.findByFingerprintAndDeletedAtIsNull(anyString())).willReturn(null)
        given(questionRepository.save(any(QaQuestion::class.java))).willAnswer { invocation ->
            val candidate = invocation.getArgument<QaQuestion>(0)
            QaQuestion(
                id = 200L,
                fingerprint = candidate.fingerprint,
                questionText = candidate.questionText,
                canonicalAnswer = candidate.canonicalAnswer,
                category = candidate.category,
                jobName = candidate.jobName,
                skillName = candidate.skillName,
                difficulty = candidate.difficulty,
                sourceTag = candidate.sourceTag,
                tagsJson = candidate.tagsJson
            )
        }
        given(questionSetItemRepository.existsBySet_IdAndQuestion_Id(100L, 200L)).willReturn(false)
        given(questionSetItemRepository.findMaxOrderNo(100L)).willReturn(0)
        given(questionSetItemRepository.save(any(QaQuestionSetItem::class.java))).willAnswer { it.getArgument(0) }

        val service = QuestionSetService(
            categoryContextResolver = categoryContextResolver,
            jobSkillCatalogService = jobSkillCatalogService,
            questionSetRepository = questionSetRepository,
            questionRepository = questionRepository,
            questionSetItemRepository = questionSetItemRepository,
            userRepository = userRepository,
            objectMapper = ObjectMapper()
        )

        val result = service.addQuestionToSet(
            principal = AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
            setId = 100L,
            request = AddQuestionToSetRequest(
                questionText = "재무제표 신뢰성을 높이기 위한 내부통제 핵심을 설명해 주세요.",
                canonicalAnswer = "통제환경, 위험평가, 통제활동, 정보/소통, 모니터링을 기준으로 설명합니다.",
                categoryId = null,
                jobName = "회계사",
                skillName = "재무회계",
                difficulty = QuestionDifficulty.MEDIUM,
                tags = listOf("재무제표", "내부통제")
            )
        )

        assertThat(result.jobName).isEqualTo("회계사")
        assertThat(result.skillName).isEqualTo("재무회계")
        assertThat(result.bestPractice).isNull()
        assertThat(result.categoryName).isEqualTo("재무회계")
        assertThat(set.jobName).isEqualTo("회계사")
        assertThat(set.skillName).isEqualTo("재무회계")
    }

    private fun createUser(id: Long): User = User(
        id = id,
        email = "tester@vlainter.com",
        password = "encoded-password",
        name = "Tester",
        status = UserStatus.ACTIVE,
        role = UserRole.USER,
        free = 0,
        point = 0
    )

    private fun createCategory(): QaCategory {
        val job = QaCategory(
            id = 11L,
            parent = null,
            code = "JOB_ACCOUNTANT",
            name = "회계사",
            description = null,
            depth = 1,
            path = "TECH/JOB_ACCOUNTANT",
            sortOrder = 100,
            isActive = true,
            isLeaf = false
        )
        return QaCategory(
            id = 12L,
            parent = job,
            code = "SKILL_FINANCE",
            name = "재무회계",
            description = null,
            depth = 2,
            path = "TECH/JOB_ACCOUNTANT/SKILL_FINANCE",
            sortOrder = 100,
            isActive = true,
            isLeaf = true
        )
    }
}
