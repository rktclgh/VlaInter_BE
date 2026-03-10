@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.dto.AddQuestionToSetRequest
import com.cw.vlainter.domain.interview.dto.CreateQuestionSetRequest
import com.cw.vlainter.domain.interview.dto.UpdateQuestionInSetRequest
import com.cw.vlainter.domain.interview.entity.QaCategory
import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QaQuestionSet
import com.cw.vlainter.domain.interview.entity.QaQuestionSetItem
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.entity.QuestionSetOwnerType
import com.cw.vlainter.domain.interview.entity.QuestionSetVisibility
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
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
        val actor = createUser()
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
            branchName = "상경",
            jobName = "회계사",
            skillName = "재무회계"
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(
            categoryContextResolver.resolve(
                categoryId = null,
                jobName = "회계사",
                skillName = "재무회계",
                requireIfMissing = true
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

        val service = createService()

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
        assertThat(set.jobName).isEqualTo("상경")
        assertThat(set.skillName).isNull()
    }

    @Test
    fun `createMySet는 계열만 세트 메타로 저장하고 기술 목록은 비워둔다`() {
        val actor = createUser()
        val savedSet = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "백엔드 질문 세트",
            jobName = "개발",
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        given(userRepository.findById(7L)).willReturn(Optional.of(actor))
        given(questionSetRepository.save(any(QaQuestionSet::class.java))).willReturn(savedSet)
        given(questionSetItemRepository.findAllBySet_IdAndIsActiveTrueOrderByOrderNoAsc(100L)).willReturn(emptyList())

        val result = createService().createMySet(
            AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
            CreateQuestionSetRequest(
                title = "백엔드 질문 세트",
                branchName = "개발",
                skillName = "Spring",
                description = null,
                visibility = QuestionSetVisibility.PRIVATE
            )
        )

        assertThat(result.branchName).isEqualTo("개발")
        assertThat(result.jobName).isEqualTo("개발")
        assertThat(result.skillName).isNull()
        assertThat(result.skillNames).isEmpty()
    }

    @Test
    fun `같은 직무 안에서는 서로 다른 기술 질문도 같은 세트에 추가할 수 있다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "백엔드 질문 세트",
            jobName = "개발",
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        val jpaContext = InterviewCategoryContextResolver.ResolvedCategoryContext(
            category = createCategory(branchName = "개발", jobName = "백엔드개발자", skillName = "JPA", categoryId = 13L),
            branchName = "개발",
            jobName = "백엔드개발자",
            skillName = "JPA"
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(
            categoryContextResolver.resolve(
                categoryId = null,
                jobName = "백엔드개발자",
                skillName = "JPA",
                requireIfMissing = true
            )
        ).willReturn(jpaContext)
        given(questionRepository.findByFingerprintAndDeletedAtIsNull(anyString())).willReturn(null)
        given(questionRepository.save(any(QaQuestion::class.java))).willAnswer { invocation ->
            val candidate = invocation.getArgument<QaQuestion>(0)
            QaQuestion(
                id = 201L,
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
        given(questionSetItemRepository.existsBySet_IdAndQuestion_Id(100L, 201L)).willReturn(false)
        given(questionSetItemRepository.findMaxOrderNo(100L)).willReturn(1)
        given(questionSetItemRepository.save(any(QaQuestionSetItem::class.java))).willAnswer { it.getArgument(0) }

        val result = createService().addQuestionToSet(
            AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
            100L,
            AddQuestionToSetRequest(
                questionText = "JPA 영속성 컨텍스트의 역할을 설명해 주세요.",
                canonicalAnswer = "1차 캐시와 변경 감지를 중심으로 답합니다.",
                categoryId = null,
                jobName = "백엔드개발자",
                skillName = "JPA",
                difficulty = QuestionDifficulty.MEDIUM,
                tags = listOf("JPA")
            )
        )

        assertThat(result.skillName).isEqualTo("JPA")
        assertThat(set.jobName).isEqualTo("개발")
        assertThat(set.skillName).isNull()
    }

    @Test
    fun `같은 계열의 공통 직무 질문은 세트에 추가할 수 있다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "개발 질문 세트",
            jobName = "개발",
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        val commonContext = InterviewCategoryContextResolver.ResolvedCategoryContext(
            category = createCategory(branchName = "개발", jobName = "공통", skillName = "CS", categoryId = 14L),
            branchName = "개발",
            jobName = "공통",
            skillName = "CS"
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(
            categoryContextResolver.resolve(
                categoryId = null,
                jobName = "공통",
                skillName = "CS",
                requireIfMissing = true
            )
        ).willReturn(commonContext)
        given(questionRepository.findByFingerprintAndDeletedAtIsNull(anyString())).willReturn(null)
        given(questionRepository.save(any(QaQuestion::class.java))).willAnswer { invocation ->
            val candidate = invocation.getArgument<QaQuestion>(0)
            QaQuestion(
                id = 204L,
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
        given(questionSetItemRepository.existsBySet_IdAndQuestion_Id(100L, 204L)).willReturn(false)
        given(questionSetItemRepository.findMaxOrderNo(100L)).willReturn(0)
        given(questionSetItemRepository.save(any(QaQuestionSetItem::class.java))).willAnswer { it.getArgument(0) }

        val result = createService().addQuestionToSet(
            AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
            100L,
            AddQuestionToSetRequest(
                questionText = "운영체제에서 프로세스와 스레드 차이를 설명해 주세요.",
                canonicalAnswer = "자원 공유와 스케줄링 관점으로 설명합니다.",
                categoryId = null,
                jobName = "공통",
                skillName = "CS",
                difficulty = QuestionDifficulty.MEDIUM,
                tags = listOf("CS")
            )
        )

        assertThat(result.jobName).isEqualTo("공통")
        assertThat(result.skillName).isEqualTo("CS")
        assertThat(set.jobName).isEqualTo("개발")
    }

    @Test
    fun `다른 계열의 질문은 세트에 추가할 수 없다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "개발 질문 세트",
            jobName = "개발",
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        val financeContext = InterviewCategoryContextResolver.ResolvedCategoryContext(
            category = createCategory(branchName = "상경", jobName = "재무회계", skillName = "재무회계", categoryId = 15L),
            branchName = "상경",
            jobName = "재무회계",
            skillName = "재무회계"
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(
            categoryContextResolver.resolve(
                categoryId = null,
                jobName = "재무회계",
                skillName = "재무회계",
                requireIfMissing = true
            )
        ).willReturn(financeContext)

        val exception = assertThrows(ResponseStatusException::class.java) {
            createService().addQuestionToSet(
                AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
                100L,
                AddQuestionToSetRequest(
                    questionText = "재무제표 검증 절차를 설명해 주세요.",
                    canonicalAnswer = "실증 절차와 통제 테스트를 구분해 답합니다.",
                    categoryId = null,
                    jobName = "재무회계",
                    skillName = "재무회계",
                    difficulty = QuestionDifficulty.MEDIUM,
                    tags = listOf("재무회계")
                )
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(exception.reason).contains("질문 세트 계열")
    }

    @Test
    fun `updateQuestionInSet은 세트 내 문답의 기술과 내용을 교체한다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "백엔드 질문 세트",
            jobName = "개발",
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        val oldQuestion = QaQuestion(
            id = 200L,
            fingerprint = "old",
            questionText = "Spring 트랜잭션을 설명해 주세요.",
            canonicalAnswer = "기존 답변",
            category = createCategory(branchName = "개발", jobName = "백엔드개발자", skillName = "Spring"),
            jobName = "백엔드개발자",
            skillName = "Spring",
            difficulty = QuestionDifficulty.MEDIUM,
            sourceTag = com.cw.vlainter.domain.interview.entity.QuestionSourceTag.USER,
            tagsJson = "[]"
        )
        val item = QaQuestionSetItem(id = 1L, set = set, question = oldQuestion, orderNo = 0)
        val resolvedContext = InterviewCategoryContextResolver.ResolvedCategoryContext(
            category = createCategory(branchName = "개발", jobName = "백엔드개발자", skillName = "JPA", categoryId = 13L),
            branchName = "개발",
            jobName = "백엔드개발자",
            skillName = "JPA"
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(questionSetItemRepository.findBySet_IdAndQuestion_IdAndIsActiveTrue(100L, 200L)).willReturn(item)
        given(
            categoryContextResolver.resolve(
                categoryId = null,
                jobName = "백엔드개발자",
                skillName = "JPA",
                requireIfMissing = true
            )
        ).willReturn(resolvedContext)
        given(questionRepository.findByFingerprintAndDeletedAtIsNull(anyString())).willReturn(null)
        given(questionRepository.save(any(QaQuestion::class.java))).willAnswer { invocation ->
            val candidate = invocation.getArgument<QaQuestion>(0)
            QaQuestion(
                id = 201L,
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
        given(questionSetItemRepository.existsBySet_IdAndQuestion_Id(100L, 201L)).willReturn(false)
        given(questionSetItemRepository.save(any(QaQuestionSetItem::class.java))).willAnswer { it.getArgument(0) }

        val result = createService().updateQuestionInSet(
            AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
            100L,
            200L,
            UpdateQuestionInSetRequest(
                questionText = "JPA 영속성 컨텍스트를 설명해 주세요.",
                canonicalAnswer = "변경 감지와 1차 캐시를 중심으로 설명합니다.",
                categoryId = null,
                jobName = "백엔드개발자",
                skillName = "JPA",
                difficulty = QuestionDifficulty.MEDIUM,
                tags = listOf("JPA")
            )
        )

        assertThat(result.questionId).isEqualTo(201L)
        assertThat(item.question.id).isEqualTo(201L)
        assertThat(result.skillName).isEqualTo("JPA")
    }

    @Test
    fun `deleteQuestionFromSet은 세트 아이템을 비활성화한다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "백엔드 질문 세트",
            jobName = "개발",
            skillName = null,
            visibility = QuestionSetVisibility.PRIVATE
        )
        val item = QaQuestionSetItem(
            id = 1L,
            set = set,
            question = QaQuestion(
                id = 200L,
                fingerprint = "old",
                questionText = "Spring 트랜잭션을 설명해 주세요.",
                canonicalAnswer = "기존 답변",
                category = createCategory(branchName = "개발", jobName = "백엔드개발자", skillName = "Spring"),
                jobName = "백엔드개발자",
                skillName = "Spring",
                difficulty = QuestionDifficulty.MEDIUM,
                sourceTag = com.cw.vlainter.domain.interview.entity.QuestionSourceTag.USER,
                tagsJson = "[]"
            ),
            orderNo = 0
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(questionSetItemRepository.findBySet_IdAndQuestion_IdAndIsActiveTrue(100L, 200L)).willReturn(item)

        createService().deleteQuestionFromSet(
            AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
            100L,
            200L
        )

        assertThat(item.isActive).isFalse()
        then(questionSetItemRepository).should().save(item)
    }

    @Test
    fun `다른 사용자의 세트에는 질문을 추가할 수 없다`() {
        val owner = createUser(id = 99L, email = "owner@vlainter.com")
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = owner,
            ownerType = QuestionSetOwnerType.USER,
            title = "소유자 전용 세트",
            visibility = QuestionSetVisibility.PRIVATE
        )
        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)

        val exception = assertThrows(ResponseStatusException::class.java) {
            createService().addQuestionToSet(
                principal = AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
                setId = 100L,
                request = baseAddRequest()
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `이미 포함된 질문은 중복 추가할 수 없다`() {
        val actor = createUser(id = 7L)
        val set = QaQuestionSet(
            id = 100L,
            ownerUser = actor,
            ownerType = QuestionSetOwnerType.USER,
            title = "재무회계 세트",
            visibility = QuestionSetVisibility.PRIVATE
        )
        val category = createCategory()
        val resolvedContext = InterviewCategoryContextResolver.ResolvedCategoryContext(
            category = category,
            branchName = "상경",
            jobName = "회계사",
            skillName = "재무회계"
        )
        val existingQuestion = QaQuestion(
            id = 200L,
            fingerprint = "fingerprint",
            questionText = "재무제표 신뢰성을 높이기 위한 내부통제 핵심을 설명해 주세요.",
            canonicalAnswer = "통제환경, 위험평가, 통제활동, 정보/소통, 모니터링을 기준으로 설명합니다.",
            category = category,
            jobName = "회계사",
            skillName = "재무회계",
            difficulty = QuestionDifficulty.MEDIUM,
            sourceTag = com.cw.vlainter.domain.interview.entity.QuestionSourceTag.USER,
            tagsJson = "[]"
        )

        given(questionSetRepository.findByIdAndDeletedAtIsNull(100L)).willReturn(set)
        given(
            categoryContextResolver.resolve(
                categoryId = null,
                jobName = "회계사",
                skillName = "재무회계",
                requireIfMissing = true
            )
        ).willReturn(resolvedContext)
        given(questionRepository.findByFingerprintAndDeletedAtIsNull(anyString())).willReturn(existingQuestion)
        given(questionSetItemRepository.existsBySet_IdAndQuestion_Id(100L, 200L)).willReturn(true)

        val exception = assertThrows(ResponseStatusException::class.java) {
            createService().addQuestionToSet(
                principal = AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
                setId = 100L,
                request = baseAddRequest()
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `존재하지 않는 질문 세트 ID면 예외를 던진다`() {
        given(questionSetRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(null)

        val exception = assertThrows(ResponseStatusException::class.java) {
            createService().addQuestionToSet(
                principal = AuthPrincipal(userId = 7L, email = "tester@vlainter.com", sessionId = "S", role = UserRole.USER),
                setId = 999L,
                request = baseAddRequest()
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun createService(): QuestionSetService {
        return QuestionSetService(
            categoryContextResolver = categoryContextResolver,
            jobSkillCatalogService = jobSkillCatalogService,
            questionSetRepository = questionSetRepository,
            questionRepository = questionRepository,
            questionSetItemRepository = questionSetItemRepository,
            userRepository = userRepository,
            objectMapper = ObjectMapper()
        )
    }

    private fun baseAddRequest(): AddQuestionToSetRequest {
        return AddQuestionToSetRequest(
            questionText = "재무제표 신뢰성을 높이기 위한 내부통제 핵심을 설명해 주세요.",
            canonicalAnswer = "통제환경, 위험평가, 통제활동, 정보/소통, 모니터링을 기준으로 설명합니다.",
            categoryId = null,
            jobName = "회계사",
            skillName = "재무회계",
            difficulty = QuestionDifficulty.MEDIUM,
            tags = listOf("재무제표", "내부통제")
        )
    }

    private fun createUser(
        id: Long = 7L,
        email: String = "tester@vlainter.com"
    ): User = User(
        id = id,
        email = email,
        password = "encoded-password",
        name = "Tester",
        status = UserStatus.ACTIVE,
        role = UserRole.USER,
        free = 0,
        point = 0
    )

    private fun createCategory(
        branchName: String = "상경",
        jobName: String = "회계사",
        skillName: String = "재무회계",
        categoryId: Long = 12L
    ): QaCategory {
        val branch = QaCategory(
            id = 10L,
            parent = null,
            code = "BRANCH_DEFAULT",
            name = branchName,
            description = null,
            depth = 0,
            path = branchName,
            sortOrder = 10,
            isActive = true,
            isLeaf = false
        )
        val job = QaCategory(
            id = 11L,
            parent = branch,
            code = "JOB_ACCOUNTANT",
            name = jobName,
            description = null,
            depth = 1,
            path = "$branchName/JOB_ACCOUNTANT",
            sortOrder = 100,
            isActive = true,
            isLeaf = false
        )
        return QaCategory(
            id = categoryId,
            parent = job,
            code = "SKILL_FINANCE",
            name = skillName,
            description = null,
            depth = 2,
            path = "$branchName/JOB_ACCOUNTANT/SKILL_FINANCE",
            sortOrder = 100,
            isActive = true,
            isLeaf = true
        )
    }
}
