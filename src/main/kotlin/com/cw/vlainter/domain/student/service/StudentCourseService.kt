package com.cw.vlainter.domain.student.service

import com.cw.vlainter.domain.student.dto.CreateStudentCourseRequest
import com.cw.vlainter.domain.student.dto.CreateStudentExamSessionRequest
import com.cw.vlainter.domain.student.dto.CreateStudentWrongAnswerSetRequest
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialDownloadResponse
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialKind
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialResponse
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialVisualAssetResponse
import com.cw.vlainter.domain.student.dto.StudentCourseResponse
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialVisualAssetType
import com.cw.vlainter.domain.student.dto.StudentExamGenerationMode
import com.cw.vlainter.domain.student.dto.StudentExamQuestionStyle
import com.cw.vlainter.domain.student.dto.StudentExamQuestionResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionDetailResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionResponse
import com.cw.vlainter.domain.student.dto.StudentWrongAnswerItemResponse
import com.cw.vlainter.domain.student.dto.StudentWrongAnswerSetDetailResponse
import com.cw.vlainter.domain.student.dto.StudentWrongAnswerSetResponse
import com.cw.vlainter.domain.student.dto.SubmitStudentExamAnswersRequest
import com.cw.vlainter.domain.interview.ai.GeminiTransientException
import com.cw.vlainter.domain.interview.ai.CourseExamEvaluationInput
import com.cw.vlainter.domain.interview.ai.CourseExamEvaluationResult
import com.cw.vlainter.domain.interview.ai.GeneratedCourseExamQuestion
import com.cw.vlainter.domain.interview.ai.InterviewAiOrchestrator
import com.cw.vlainter.domain.interview.ai.PastExamPracticeQuestionCandidate
import com.cw.vlainter.domain.student.entity.StudentCourse
import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import com.cw.vlainter.domain.student.entity.StudentExamQuestion
import com.cw.vlainter.domain.student.entity.StudentExamSession
import com.cw.vlainter.domain.student.entity.StudentExamSessionStatus
import com.cw.vlainter.domain.student.entity.StudentWrongAnswerItem
import com.cw.vlainter.domain.student.entity.StudentWrongAnswerSet
import com.cw.vlainter.domain.student.repository.StudentExamQuestionRepository
import com.cw.vlainter.domain.student.repository.StudentExamSessionRepository
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
import com.cw.vlainter.domain.student.repository.StudentCourseRepository
import com.cw.vlainter.domain.student.repository.StudentWrongAnswerItemRepository
import com.cw.vlainter.domain.student.repository.StudentWrongAnswerSetRepository
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialVisualAssetRepository
import com.cw.vlainter.domain.interview.repository.DocChunkEmbeddingRepository
import com.cw.vlainter.domain.interview.entity.DocumentIngestionJob
import com.cw.vlainter.domain.interview.entity.DocumentIngestionStatus
import com.cw.vlainter.domain.interview.repository.DocumentIngestionJobRepository
import com.cw.vlainter.domain.interview.service.DocumentInterviewService
import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.user.entity.UserServiceMode
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.user.service.UserGeminiApiKeyService
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.service.UserFileService
import com.cw.vlainter.global.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import org.springframework.web.server.ResponseStatusException
import kotlin.math.roundToInt
import java.time.OffsetDateTime

@Service
class StudentCourseService(
    private val studentCourseRepository: StudentCourseRepository,
    private val studentCourseMaterialRepository: StudentCourseMaterialRepository,
    private val studentExamSessionRepository: StudentExamSessionRepository,
    private val studentExamQuestionRepository: StudentExamQuestionRepository,
    private val studentWrongAnswerSetRepository: StudentWrongAnswerSetRepository,
    private val studentWrongAnswerItemRepository: StudentWrongAnswerItemRepository,
    private val studentCourseMaterialVisualAssetRepository: StudentCourseMaterialVisualAssetRepository,
    private val documentIngestionJobRepository: DocumentIngestionJobRepository,
    private val docChunkEmbeddingRepository: DocChunkEmbeddingRepository,
    private val documentInterviewService: DocumentInterviewService,
    private val interviewAiOrchestrator: InterviewAiOrchestrator,
    private val userRepository: UserRepository,
    private val userGeminiApiKeyService: UserGeminiApiKeyService,
    private val userFileService: UserFileService
) {
    private companion object {
        const val PAST_EXAM_FILE_NAME_PREFIX = "__STUDENT_PAST_EXAM__"
        const val DEFAULT_QUESTION_MAX_SCORE = 20
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    @Transactional(readOnly = true)
    fun getMyCourses(principal: AuthPrincipal): List<StudentCourseResponse> {
        val user = getValidatedStudentUser(principal)
        return studentCourseRepository.findAllByUserIdAndIsArchivedFalseOrderByUpdatedAtDesc(user.id)
            .map { it.toResponse() }
    }

    @Transactional
    fun createCourse(principal: AuthPrincipal, request: CreateStudentCourseRequest): StudentCourseResponse {
        val user = getValidatedStudentUser(principal)
        val normalizedCourseName = request.courseName.trim()
        val normalizedProfessorName = request.professorName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedDescription = request.description?.trim()?.takeIf { it.isNotBlank() }

        val duplicateExists = studentCourseRepository
            .findAllByUserIdAndIsArchivedFalseOrderByUpdatedAtDesc(user.id)
            .any { course ->
                course.universityName.equals(user.universityName, ignoreCase = true) &&
                    course.departmentName.equals(user.departmentName, ignoreCase = true) &&
                    course.courseName.equals(normalizedCourseName, ignoreCase = true) &&
                    normalizeProfessorName(course.professorName).equals(normalizeProfessorName(normalizedProfessorName), ignoreCase = true)
            }
        if (duplicateExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "같은 과목이 이미 등록되어 있습니다.")
        }

        val saved = studentCourseRepository.save(
            StudentCourse(
                userId = user.id,
                universityName = user.universityName!!.trim(),
                departmentName = user.departmentName!!.trim(),
                courseName = normalizedCourseName,
                professorName = normalizedProfessorName,
                description = normalizedDescription
            )
        )
        return saved.toResponse()
    }

    @Transactional
    fun deleteCourse(principal: AuthPrincipal, courseId: Long) {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val materials = studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(course.id)
        val materialFileIds = materials.map { it.userFile.id }.distinct()
        val sessions = studentExamSessionRepository.findAllByCourseIdOrderByCreatedAtDesc(course.id)
        val sessionIds = sessions.map(StudentExamSession::id)
        val wrongAnswerSets = studentWrongAnswerSetRepository.findAllByCourseIdAndUserIdOrderByUpdatedAtDesc(course.id, user.id)
        val wrongAnswerSetIds = wrongAnswerSets.map(StudentWrongAnswerSet::id)

        if (wrongAnswerSetIds.isNotEmpty()) {
            val wrongAnswerItems = studentWrongAnswerItemRepository.findAllBySetIdInOrderBySetIdAscQuestionOrderAsc(wrongAnswerSetIds)
            if (wrongAnswerItems.isNotEmpty()) {
                studentWrongAnswerItemRepository.deleteAll(wrongAnswerItems)
            }
        }

        if (wrongAnswerSets.isNotEmpty()) {
            studentWrongAnswerSetRepository.deleteAll(wrongAnswerSets)
        }

        if (sessionIds.isNotEmpty()) {
            val questions = studentExamQuestionRepository.findAllBySessionIdInOrderBySessionIdAscQuestionOrderAsc(sessionIds)
            if (questions.isNotEmpty()) {
                studentExamQuestionRepository.deleteAll(questions)
            }
        }

        if (sessions.isNotEmpty()) {
            studentExamSessionRepository.deleteAll(sessions)
        }

        if (materials.isNotEmpty()) {
            studentCourseMaterialRepository.deleteAll(materials)
        }

        studentCourseRepository.delete(course)

        materialFileIds.forEach { fileId ->
            userFileService.deleteOwnedFile(user.id, fileId)
        }
    }

    @Transactional(readOnly = true)
    fun getCourseMaterials(principal: AuthPrincipal, courseId: Long): List<StudentCourseMaterialResponse> {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        return studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(course.id)
            .map { it.toResponse() }
    }

    @Transactional
    fun uploadCourseMaterial(
        principal: AuthPrincipal,
        courseId: Long,
        file: MultipartFile,
        materialKind: StudentCourseMaterialKind
    ): StudentCourseMaterialResponse {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val uploaded = userFileService.uploadMyFile(
            principal = principal,
            fileType = FileType.COURSE_MATERIAL,
            file = file,
            storedDisplayFileName = encodeStoredMaterialFileName(file.originalFilename, materialKind),
            allowedExtensions = if (materialKind == StudentCourseMaterialKind.PAST_EXAM) userFileService.allowedPastExamExtensions() else null,
            allowedContentTypes = if (materialKind == StudentCourseMaterialKind.PAST_EXAM) userFileService.allowedPastExamContentTypes() else null,
            invalidTypeMessage = if (materialKind == StudentCourseMaterialKind.PAST_EXAM) {
                "족보는 PDF, DOCX, PPTX, JPG, JPEG, PNG 형식만 업로드할 수 있습니다."
            } else {
                null
            }
        )
        val userFile = userFileService.loadOwnedFile(user.id, uploaded.fileId)
        val saved = studentCourseMaterialRepository.save(
            StudentCourseMaterial(
                course = course,
                userFile = userFile
            )
        )
        return saved.toResponse()
    }

    @Transactional
    fun deleteCourseMaterial(principal: AuthPrincipal, courseId: Long, materialId: Long) {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val material = getOwnedMaterial(course.id, materialId)
        studentCourseMaterialVisualAssetRepository.deleteAllByMaterial_Id(material.id)
        studentCourseMaterialRepository.delete(material)
        userFileService.deleteOwnedFile(user.id, material.userFile.id)
    }

    @Transactional(readOnly = true)
    fun getCourseMaterialDownloadUrl(
        principal: AuthPrincipal,
        courseId: Long,
        materialId: Long
    ): StudentCourseMaterialDownloadResponse {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val material = getOwnedMaterial(course.id, materialId)
        return StudentCourseMaterialDownloadResponse(
            downloadUrl = buildCourseMaterialContentUrl(course.id, material.id),
            expiresInSeconds = 0
        )
    }

    @Transactional(readOnly = true)
    fun getCourseMaterialContent(
        principal: AuthPrincipal,
        courseId: Long,
        materialId: Long
    ): UserFileService.FileContentResource {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val material = getOwnedMaterial(course.id, materialId)
        return userFileService.getOwnedFileContent(user.id, material.userFile.id)
    }

    @Transactional(readOnly = true)
    fun getCourseMaterialVisualAssetContent(
        principal: AuthPrincipal,
        assetId: Long
    ): UserFileService.FileContentResource {
        val user = getValidatedStudentUser(principal)
        val asset = studentCourseMaterialVisualAssetRepository.findById(assetId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "시각 자산을 찾을 수 없습니다.") }
        val material = asset.material
        val course = getOwnedCourse(user.id, material.course.id)
        getOwnedMaterial(course.id, material.id)
        return userFileService.loadStoredObjectContent(
            storageKey = asset.storageKey,
            downloadFileName = buildVisualAssetDownloadFileName(material, asset),
            fallbackContentType = asset.contentType
        )
    }

    @Transactional
    fun requestCourseMaterialIngestion(
        principal: AuthPrincipal,
        courseId: Long,
        materialId: Long
    ): StudentCourseMaterialResponse {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val material = getOwnedMaterial(course.id, materialId)
        if (!userGeminiApiKeyService.hasGeminiApiKey(user)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Gemini API 키를 먼저 등록해 주세요.")
        }
        val activeMaterial = findActiveIngestionMaterial(course.id, material.id)
        if (activeMaterial != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "한 번에 한 개의 문서만 분석할 수 있습니다. \"${decodeDisplayMaterialFileName(activeMaterial.userFile.fileName)}\" 분석이 끝난 뒤 다시 시도해 주세요."
            )
        }
        documentInterviewService.ingestStudentCourseMaterial(principal, material.userFile.id)
        return material.toResponse()
    }

    @Transactional(readOnly = true)
    fun getCourseSessions(principal: AuthPrincipal, courseId: Long): List<StudentExamSessionResponse> {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val sessions = studentExamSessionRepository.findAllByCourseIdOrderByCreatedAtDesc(course.id)
        val sessionIds = sessions.map(StudentExamSession::id)
        val questionsBySession = if (sessionIds.isEmpty()) {
            emptyMap()
        } else {
            studentExamQuestionRepository.findAllBySessionIdInOrderBySessionIdAscQuestionOrderAsc(sessionIds)
                .groupBy { it.sessionId }
        }
        return sessions
            .map { session -> session.toResponse(questionsBySession[session.id].orEmpty()) }
    }

    @Transactional
    fun createCourseSession(
        principal: AuthPrincipal,
        courseId: Long,
        request: CreateStudentExamSessionRequest
    ): StudentExamSessionResponse {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        userGeminiApiKeyService.assertGeminiApiKeyConfigured(user.id)
        val materials = studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(course.id)
        val readyLectureMaterials = materials.filter { material ->
            resolveMaterialKind(material) == StudentCourseMaterialKind.LECTURE_MATERIAL &&
                documentIngestionJobRepository.findTopByDocumentFileIdOrderByRequestedAtDesc(material.userFile.id)?.status == DocumentIngestionStatus.READY
        }
        val usesLectureMaterials = request.generationMode != StudentExamGenerationMode.PAST_EXAM_PRACTICE
        if (usesLectureMaterials && readyLectureMaterials.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분석이 완료된 강의 자료가 1개 이상 있어야 모의고사를 만들 수 있습니다.")
        }
        val readyPastExamMaterials = materials.filter { material ->
            resolveMaterialKind(material) == StudentCourseMaterialKind.PAST_EXAM &&
                documentIngestionJobRepository.findTopByDocumentFileIdOrderByRequestedAtDesc(material.userFile.id)?.status == DocumentIngestionStatus.READY
        }
        val selectedPastExamMaterials = resolveSelectedPastExamMaterials(request, readyPastExamMaterials)
        validateSessionCreationRequest(request, readyPastExamMaterials.isNotEmpty(), selectedPastExamMaterials.isNotEmpty())

        val effectiveDifficultyLevel = if (
            request.generationMode == StudentExamGenerationMode.PAST_EXAM ||
            request.generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE
        ) {
            null
        } else {
            request.difficultyLevel
        }
        val effectiveQuestionStyles = normalizeRequestedQuestionStyles(request)
        logger.info(
            "학생 모의고사 생성 요청 courseId={} userId={} mode={} difficulty={} styles={} questionCount={} lectureReady={} selectedPastExams={}",
            course.id,
            user.id,
            request.generationMode,
            effectiveDifficultyLevel,
            effectiveQuestionStyles.joinToString(",") { it.name },
            request.questionCount,
            readyLectureMaterials.size,
            selectedPastExamMaterials.size
        )
        val questionStylesCsv = encodeQuestionStyles(effectiveQuestionStyles)
        val maxScore = request.questionCount * DEFAULT_QUESTION_MAX_SCORE

        val savedSession = studentExamSessionRepository.save(
            StudentExamSession(
                courseId = course.id,
                userId = user.id,
                status = StudentExamSessionStatus.READY,
                generationMode = request.generationMode,
                difficultyLevel = effectiveDifficultyLevel,
                questionStylesCsv = questionStylesCsv,
                questionCount = request.questionCount,
                maxScore = maxScore,
                sourceMaterialCount = if (request.generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE) {
                    selectedPastExamMaterials.size
                } else if (request.generationMode == StudentExamGenerationMode.PAST_EXAM) {
                    readyLectureMaterials.size + selectedPastExamMaterials.size
                } else {
                    readyLectureMaterials.size
                },
                title = buildSessionTitle(course, request.questionCount, request.generationMode, selectedPastExamMaterials)
            )
        )
        val questions = try {
            userGeminiApiKeyService.withUserApiKey(user.id) {
                buildAiGeneratedQuestions(
                    userId = user.id,
                    universityName = user.universityName!!.trim(),
                    departmentName = user.departmentName!!.trim(),
                    course = course,
                    lectureMaterials = if (request.generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE) {
                        emptyList()
                    } else {
                        readyLectureMaterials
                    },
                    styleReferenceMaterials = selectedPastExamMaterials,
                    questionCount = request.questionCount,
                    generationMode = request.generationMode,
                    difficultyLevel = effectiveDifficultyLevel,
                    questionStyles = effectiveQuestionStyles
                )
            }
        } catch (ex: GeminiTransientException) {
            val status = if (ex.statusCode == 429) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.SERVICE_UNAVAILABLE
            throw ResponseStatusException(status, "시험문제 생성 중 AI 호출이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.")
        } catch (ex: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, ex.message ?: "시험문제를 생성할 수 없습니다.")
        }
        val savedQuestions = studentExamQuestionRepository.saveAll(
            questions.mapIndexed { index, item ->
                StudentExamQuestion(
                    sessionId = savedSession.id,
                    questionOrder = index + 1,
                    questionText = item.questionText,
                    questionStyle = normalizeQuestionStyle(item.questionStyle, effectiveQuestionStyles),
                    canonicalAnswer = item.canonicalAnswer,
                    gradingCriteria = item.gradingCriteria,
                    referenceExample = item.referenceExample,
                    maxScore = item.maxScore
                )
            }
        )
        savedSession.questionStylesCsv = encodeQuestionStyles(savedQuestions.map(StudentExamQuestion::questionStyle).distinct())
        return savedSession.toResponse(savedQuestions)
    }

    @Transactional
    fun deleteSession(principal: AuthPrincipal, sessionId: Long) {
        val user = getValidatedStudentUser(principal)
        val session = getOwnedSession(user.id, sessionId)
        val wrongAnswerSets = studentWrongAnswerSetRepository.findAllByCourseIdAndUserIdOrderByUpdatedAtDesc(session.courseId, user.id)
            .filter { it.sessionId == session.id || it.retestSessionId == session.id }
        wrongAnswerSets.forEach { set ->
            studentWrongAnswerItemRepository.deleteAllBySetId(set.id)
        }
        if (wrongAnswerSets.isNotEmpty()) {
            studentWrongAnswerSetRepository.deleteAll(wrongAnswerSets)
        }
        val questions = studentExamQuestionRepository.findAllBySessionIdOrderByQuestionOrderAsc(session.id)
        if (questions.isNotEmpty()) {
            studentExamQuestionRepository.deleteAll(questions)
        }
        studentExamSessionRepository.delete(session)
    }

    @Transactional(readOnly = true)
    fun getCourseWrongAnswerSets(principal: AuthPrincipal, courseId: Long): List<StudentWrongAnswerSetResponse> {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val sets = studentWrongAnswerSetRepository.findAllByCourseIdAndUserIdOrderByUpdatedAtDesc(course.id, user.id)
        val setIds = sets.map(StudentWrongAnswerSet::id)
        val itemsBySet = if (setIds.isEmpty()) {
            emptyMap()
        } else {
            studentWrongAnswerItemRepository.findAllBySetIdInOrderBySetIdAscQuestionOrderAsc(setIds)
                .groupBy { it.setId }
        }
        return sets.map { set -> set.toResponse(itemsBySet[set.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    fun getWrongAnswerSetDetail(principal: AuthPrincipal, setId: Long): StudentWrongAnswerSetDetailResponse {
        val user = getValidatedStudentUser(principal)
        val set = getOwnedWrongAnswerSet(user.id, setId)
        val items = studentWrongAnswerItemRepository.findAllBySetIdOrderByQuestionOrderAsc(set.id)
        val originalSession = getOwnedSession(user.id, set.sessionId)
        val originalQuestions = studentExamQuestionRepository.findAllBySessionIdOrderByQuestionOrderAsc(originalSession.id)
        return set.toDetailResponse(
            items = items,
            sourceContexts = buildQuestionSourceContexts(user.id, originalSession, originalQuestions)
        )
    }

    @Transactional
    fun createRetestSession(principal: AuthPrincipal, setId: Long): StudentExamSessionResponse {
        val user = getValidatedStudentUser(principal)
        val set = getOwnedWrongAnswerSet(user.id, setId)
        val course = getOwnedCourse(user.id, set.courseId)
        val items = studentWrongAnswerItemRepository.findAllBySetIdOrderByQuestionOrderAsc(set.id)
        if (items.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "오답노트에 저장된 문제가 없습니다.")
        }

        val savedSession = studentExamSessionRepository.save(
            StudentExamSession(
                courseId = course.id,
                userId = user.id,
                status = StudentExamSessionStatus.READY,
                generationMode = StudentExamGenerationMode.WRONG_ANSWER_RETEST,
                difficultyLevel = null,
                questionStylesCsv = encodeQuestionStyles(items.map(StudentWrongAnswerItem::questionStyle).distinct()),
                questionCount = items.size,
                maxScore = items.sumOf(StudentWrongAnswerItem::maxScore),
                sourceMaterialCount = 0,
                title = "${set.title} 재시험"
            )
        )

        val savedQuestions = studentExamQuestionRepository.saveAll(
            items.mapIndexed { index, item ->
                StudentExamQuestion(
                    sessionId = savedSession.id,
                    questionOrder = index + 1,
                    questionText = item.questionText,
                    questionStyle = item.questionStyle,
                    canonicalAnswer = item.canonicalAnswer,
                    gradingCriteria = item.gradingCriteria,
                    referenceExample = item.referenceExample,
                    maxScore = item.maxScore
                )
            }
        )
        set.retestSessionId = savedSession.id
        studentWrongAnswerSetRepository.save(set)
        return savedSession.toResponse(savedQuestions)
    }

    @Transactional(readOnly = true)
    fun getSessionDetail(principal: AuthPrincipal, sessionId: Long): StudentExamSessionDetailResponse {
        val user = getValidatedStudentUser(principal)
        val session = getOwnedSession(user.id, sessionId)
        val questions = studentExamQuestionRepository.findAllBySessionIdOrderByQuestionOrderAsc(session.id)
        return session.toDetailResponse(
            questions = questions,
            sourceContexts = buildQuestionSourceContexts(user.id, session, questions)
        )
    }

    @Transactional
    fun submitSessionAnswers(
        principal: AuthPrincipal,
        sessionId: Long,
        request: SubmitStudentExamAnswersRequest
    ): StudentExamSessionDetailResponse {
        val user = getValidatedStudentUser(principal)
        val session = getOwnedSession(user.id, sessionId)
        val questions = studentExamQuestionRepository.findAllBySessionIdOrderByQuestionOrderAsc(session.id)
        if (questions.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "세션에 저장된 문항이 없습니다.")
        }
        if (request.answers.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제출할 답안이 없습니다.")
        }

        val answerByQuestionId = request.answers.associateBy { it.questionId }
        val answeredAt = OffsetDateTime.now()
        val evaluationsByQuestionId = evaluateSubmittedAnswers(
            userId = user.id,
            session = session,
            course = getOwnedCourse(user.id, session.courseId),
            questions = questions,
            answerByQuestionId = answerByQuestionId
        )
        questions.forEach { question ->
            val submitted = answerByQuestionId[question.id]
            val trimmedAnswer = submitted?.answerText?.trim().orEmpty()
            if (trimmedAnswer.isBlank()) {
                question.answerText = null
                question.score = 0
                question.feedback = "미응답입니다. 다음 재시험에서는 핵심 개념과 풀이 과정을 꼭 작성해 주세요."
                question.isCorrect = false
                question.answeredAt = answeredAt
                return@forEach
            }
            val evaluation = evaluationsByQuestionId[question.id] ?: evaluateAnswer(question, trimmedAnswer)
            question.answerText = trimmedAnswer
            question.score = evaluation.score.coerceIn(0, question.maxScore)
            question.feedback = evaluation.feedback
            question.isCorrect = evaluation.isCorrect
            question.answeredAt = answeredAt
        }
        val savedQuestions = studentExamQuestionRepository.saveAll(questions)
        val scoredQuestions = savedQuestions.filter { it.answerText != null || it.score != null }
        session.answeredCount = scoredQuestions.size
        session.totalScore = savedQuestions.sumOf { it.score ?: 0 }
        session.status = StudentExamSessionStatus.SUBMITTED
        session.submittedAt = answeredAt
        val savedSession = studentExamSessionRepository.save(session)
        return savedSession.toDetailResponse(
            questions = savedQuestions,
            sourceContexts = buildQuestionSourceContexts(user.id, savedSession, savedQuestions)
        )
    }

    @Transactional
    fun createWrongAnswerSet(
        principal: AuthPrincipal,
        sessionId: Long,
        request: CreateStudentWrongAnswerSetRequest
    ): StudentWrongAnswerSetResponse {
        val user = getValidatedStudentUser(principal)
        val session = getOwnedSession(user.id, sessionId)
        if (session.status != StudentExamSessionStatus.SUBMITTED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "답안 제출이 끝난 세션만 오답노트로 저장할 수 있습니다.")
        }
        val selectedQuestionIds = request.questionIds.toSet()
        val selectedQuestions = studentExamQuestionRepository.findAllBySessionIdOrderByQuestionOrderAsc(session.id)
            .filter { it.id in selectedQuestionIds }
        if (selectedQuestions.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "저장할 문제를 찾을 수 없습니다.")
        }

        val savedSet = studentWrongAnswerSetRepository.save(
            StudentWrongAnswerSet(
                sessionId = session.id,
                courseId = session.courseId,
                userId = user.id,
                title = request.title?.trim().takeUnless { it.isNullOrBlank() } ?: "${session.title} 오답노트",
                questionCount = selectedQuestions.size
            )
        )
        val savedItems = studentWrongAnswerItemRepository.saveAll(
            selectedQuestions.map { question ->
                StudentWrongAnswerItem(
                    setId = savedSet.id,
                    questionId = question.id,
                    questionOrder = question.questionOrder,
                    questionText = question.questionText,
                    questionStyle = question.questionStyle,
                    canonicalAnswer = question.canonicalAnswer,
                    gradingCriteria = question.gradingCriteria,
                    referenceExample = question.referenceExample,
                    maxScore = question.maxScore,
                    answerText = question.answerText,
                    score = question.score,
                    feedback = question.feedback
                )
            }
        )
        return savedSet.toResponse(savedItems)
    }

    private fun getValidatedStudentUser(principal: AuthPrincipal) = userRepository.findById(principal.userId)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.") }
        .also { user ->
            if (user.serviceMode != UserServiceMode.STUDENT) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학생 모드에서만 사용할 수 있습니다.")
            }
            if (user.universityName.isNullOrBlank() || user.departmentName.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대학교와 학과를 먼저 등록해 주세요.")
            }
        }

    private fun getOwnedCourse(userId: Long, courseId: Long): StudentCourse {
        return studentCourseRepository.findById(courseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다.") }
            .also { course ->
                if (course.userId != userId || course.isArchived) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다.")
                }
            }
    }

    private fun getOwnedSession(userId: Long, sessionId: Long): StudentExamSession {
        return studentExamSessionRepository.findById(sessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "모의고사 세션을 찾을 수 없습니다.") }
            .also { session ->
                if (session.userId != userId) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "모의고사 세션을 찾을 수 없습니다.")
                }
            }
    }

    private fun getOwnedWrongAnswerSet(userId: Long, setId: Long): StudentWrongAnswerSet {
        return studentWrongAnswerSetRepository.findByIdAndUserId(setId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "오답노트를 찾을 수 없습니다.")
    }

    private fun getOwnedMaterial(courseId: Long, materialId: Long): StudentCourseMaterial {
        return studentCourseMaterialRepository.findById(materialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "과목 자료를 찾을 수 없습니다.") }
            .also { material ->
                if (material.course.id != courseId) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "과목 자료를 찾을 수 없습니다.")
                }
            }
    }

    private fun findActiveIngestionMaterial(courseId: Long, excludeMaterialId: Long? = null): StudentCourseMaterial? {
        return studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(courseId)
            .asSequence()
            .filter { material -> excludeMaterialId == null || material.id != excludeMaterialId }
            .firstOrNull { material ->
                val latestJob = material.latestIngestionJob()
                latestJob?.status == DocumentIngestionStatus.QUEUED || latestJob?.status == DocumentIngestionStatus.PROCESSING
            }
    }

    private fun normalizeProfessorName(value: String?): String = value?.trim().orEmpty()

    private fun resolveMaterialKind(material: StudentCourseMaterial): StudentCourseMaterialKind {
        return if (material.userFile.fileName.startsWith(PAST_EXAM_FILE_NAME_PREFIX)) {
            StudentCourseMaterialKind.PAST_EXAM
        } else {
            StudentCourseMaterialKind.LECTURE_MATERIAL
        }
    }

    private fun decodeDisplayMaterialFileName(fileName: String): String {
        return fileName.removePrefix(PAST_EXAM_FILE_NAME_PREFIX).trim().ifBlank { fileName }
    }

    private fun encodeStoredMaterialFileName(originalFileName: String?, materialKind: StudentCourseMaterialKind): String {
        val normalized = originalFileName?.trim().takeUnless { it.isNullOrBlank() } ?: "document"
        return when (materialKind) {
            StudentCourseMaterialKind.LECTURE_MATERIAL -> normalized
            StudentCourseMaterialKind.PAST_EXAM -> "$PAST_EXAM_FILE_NAME_PREFIX$normalized"
        }
    }

    private fun buildAiGeneratedQuestions(
        userId: Long,
        universityName: String,
        departmentName: String,
        course: StudentCourse,
        lectureMaterials: List<StudentCourseMaterial>,
        styleReferenceMaterials: List<StudentCourseMaterial>,
        questionCount: Int,
        generationMode: StudentExamGenerationMode,
        difficultyLevel: Int?,
        questionStyles: List<StudentExamQuestionStyle>
    ): List<GeneratedCourseExamQuestion> {
        if (generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE) {
            return buildPastExamPracticeQuestions(
                userId = userId,
                universityName = universityName,
                departmentName = departmentName,
                course = course,
                styleReferenceMaterials = styleReferenceMaterials,
                questionCount = questionCount
            )
        }

        val lectureSnippets = collectMaterialSnippets(
            userId = userId,
            materials = lectureMaterials,
            totalLimit = maxOf(questionCount + 5, 10)
        )
        if (lectureSnippets.isEmpty()) {
            throw IllegalStateException("시험문제 생성에 사용할 강의 자료 발췌가 부족합니다. 자료를 다시 분석해 주세요.")
        }

        val styleSnippets = if (generationMode == StudentExamGenerationMode.PAST_EXAM) {
            collectStyleReferenceSnippets(
                userId = userId,
                materials = styleReferenceMaterials
            )
        } else {
            emptyList()
        }

        val requestedStyleSet = questionStyles.toSet()
        val generatedQuestions = interviewAiOrchestrator.generateCourseExamQuestions(
            universityName = universityName,
            departmentName = departmentName,
            courseName = course.courseName,
            professorName = course.professorName,
            questionCount = questionCount,
            difficultyLevel = difficultyLevel,
            questionStyles = questionStyles.map(StudentExamQuestionStyle::name),
            lectureContextSnippets = lectureSnippets.mapIndexed { index, snippet ->
                "[강의 자료 발췌 ${index + 1}]\n$snippet"
            },
            styleReferenceSnippets = styleSnippets.mapIndexed { index, snippet ->
                "[족보 스타일 참고 ${index + 1}]\n$snippet"
            },
            generationMode = generationMode.name,
            language = InterviewLanguage.KO
        )
        val generatedStyleCounts = generatedQuestions.groupingBy { it.questionStyle.trim().uppercase() }.eachCount()
        val generatedMissingReferenceCount = generatedQuestions.count {
            it.referenceExample.isNullOrBlank()
        }
        logger.info(
            "학생 모의고사 AI 응답 courseId={} mode={} requestedStyles={} generatedCount={} generatedStyles={} missingReferenceExamples={}",
            course.id,
            generationMode,
            questionStyles.joinToString(",") { it.name },
            generatedQuestions.size,
            generatedStyleCounts.entries.joinToString(",") { "${it.key}:${it.value}" },
            generatedMissingReferenceCount
        )

        var rejectedUnknownStyleCount = 0
        var rejectedUnrequestedStyleCount = 0
        var rejectedMissingReferenceCount = 0

        val acceptedQuestions = generatedQuestions.mapNotNull { generated ->
                val normalizedStyle = parseQuestionStyle(generated.questionStyle)
                    ?: run {
                        rejectedUnknownStyleCount += 1
                        return@mapNotNull null
                    }
                if (normalizedStyle !in requestedStyleSet) {
                    rejectedUnrequestedStyleCount += 1
                    return@mapNotNull null
                }
                val normalizedExample = generated.referenceExample?.trim()?.takeIf { it.isNotBlank() }
                if (normalizedStyle.requiresReferenceExample() && normalizedExample == null) {
                    rejectedMissingReferenceCount += 1
                    return@mapNotNull null
                }
                generated.copy(
                    questionStyle = normalizedStyle.name,
                    referenceExample = normalizedExample,
                    maxScore = DEFAULT_QUESTION_MAX_SCORE
                )
            }

        logger.info(
            "학생 모의고사 필터 결과 courseId={} mode={} acceptedCount={} rejectedUnknownStyle={} rejectedUnrequestedStyle={} rejectedMissingReference={}",
            course.id,
            generationMode,
            acceptedQuestions.size,
            rejectedUnknownStyleCount,
            rejectedUnrequestedStyleCount,
            rejectedMissingReferenceCount
        )

        if (acceptedQuestions.size < questionCount) {
            throw IllegalStateException(
                "요청한 문제 스타일을 충족하는 문항을 충분히 만들지 못했습니다. " +
                    "generated=${generatedQuestions.size}, accepted=${acceptedQuestions.size}, " +
                    "rejectedUnknownStyle=$rejectedUnknownStyleCount, " +
                    "rejectedUnrequestedStyle=$rejectedUnrequestedStyleCount, " +
                    "rejectedMissingReference=$rejectedMissingReferenceCount"
            )
        }
        return acceptedQuestions
    }

    private fun buildPastExamPracticeQuestions(
        userId: Long,
        universityName: String,
        departmentName: String,
        course: StudentCourse,
        styleReferenceMaterials: List<StudentCourseMaterial>,
        questionCount: Int
    ): List<GeneratedCourseExamQuestion> {
        val extractedCandidates = extractPastExamPracticeQuestionCandidates(
            userId = userId,
            materials = styleReferenceMaterials,
            totalLimit = questionCount
        )
        if (extractedCandidates.size < questionCount) {
            val ocrBased = extractedCandidates.any { it.extractionMethod == "OCR_TESSERACT" } ||
                styleReferenceMaterials.any { extractExtractionMethod(it.latestIngestionJob()?.metadataJson) == "OCR_TESSERACT" }
            val guide = if (ocrBased) {
                " 선명한 PDF로 다시 저장하거나, 문제 영역만 잘라 정면에서 촬영한 이미지로 재업로드해 주세요."
            } else {
                ""
            }
            throw IllegalStateException(
                "족보 원문에서 문제를 충분히 추출하지 못했습니다. extracted=${extractedCandidates.size}, required=$questionCount.$guide"
            )
        }

        logger.info(
            "족보 그대로 연습 원문 추출 courseId={} extractedCount={} fileNames={}",
            course.id,
            extractedCandidates.size,
            extractedCandidates.map { it.sourceFileName }.distinct().joinToString(", ")
        )

        val refinedQuestions = interviewAiOrchestrator.refinePastExamPracticeQuestions(
            universityName = universityName,
            departmentName = departmentName,
            courseName = course.courseName,
            professorName = course.professorName,
            extractedQuestions = extractedCandidates.map { candidate ->
                PastExamPracticeQuestionCandidate(
                    questionNo = candidate.questionNo,
                    questionText = candidate.questionText,
                    sourceFileName = candidate.sourceFileName,
                    extractionMethod = candidate.extractionMethod
                )
            },
            language = InterviewLanguage.KO
        )

        logger.info(
            "족보 그대로 연습 보정 결과 courseId={} refinedCount={} styles={}",
            course.id,
            refinedQuestions.size,
            refinedQuestions.groupingBy { it.questionStyle }.eachCount().entries.joinToString(",") { "${it.key}:${it.value}" }
        )

        var rejectedBrokenQuestionCount = 0
        val acceptedQuestions = refinedQuestions.mapNotNull { question ->
            val normalizedQuestionText = question.questionText.trim()
            if (!isUsablePastExamPracticeQuestionText(normalizedQuestionText)) {
                rejectedBrokenQuestionCount += 1
                return@mapNotNull null
            }
            question.copy(
                questionStyle = parseQuestionStyle(question.questionStyle)?.name ?: StudentExamQuestionStyle.ESSAY.name,
                referenceExample = normalizeOptionalReferenceExample(question.referenceExample),
                maxScore = DEFAULT_QUESTION_MAX_SCORE
            )
        }

        logger.info(
            "족보 그대로 연습 품질 필터 courseId={} acceptedCount={} rejectedBroken={}",
            course.id,
            acceptedQuestions.size,
            rejectedBrokenQuestionCount
        )

        if (acceptedQuestions.size < questionCount) {
            throw IllegalStateException(
                "족보 문제 복원 품질이 낮습니다. usable=${acceptedQuestions.size}, required=$questionCount, broken=$rejectedBrokenQuestionCount. 선명한 PDF로 다시 저장하거나 문제 영역만 잘라서 재업로드해 주세요."
            )
        }

        return acceptedQuestions.take(questionCount)
    }

    private fun collectMaterialSnippets(
        userId: Long,
        materials: List<StudentCourseMaterial>,
        totalLimit: Int
    ): List<String> {
        return materials
            .flatMap { material ->
                docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(userId, material.userFile.id)
                    .map { it.chunkText.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.length >= 40 }
                    .take(3)
            }
            .distinct()
            .take(totalLimit)
    }

    private fun collectStyleReferenceSnippets(
        userId: Long,
        materials: List<StudentCourseMaterial>
    ): List<String> {
        return materials
            .flatMap { material ->
                val extractionMethod = extractExtractionMethod(material.latestIngestionJob()?.metadataJson)
                val extractionLabel = if (extractionMethod == "OCR_TESSERACT") "OCR 추출 원문" else "문서 추출 원문"
                docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(userId, material.userFile.id)
                    .map { embedding ->
                        val snippet = embedding.chunkText.replace(Regex("\\s+"), " ").trim()
                        if (snippet.length < 24) {
                            null
                        } else {
                            "[자료명] ${decodeDisplayMaterialFileName(material.userFile.fileName)}\n[원문 유형] $extractionLabel\n$snippet"
                        }
                    }
                    .filterNotNull()
                    .take(2)
            }
            .distinct()
            .take(6)
    }

    private fun extractPastExamPracticeQuestionCandidates(
        userId: Long,
        materials: List<StudentCourseMaterial>,
        totalLimit: Int
    ): List<ExtractedPastExamQuestionCandidate> {
        return materials
            .flatMap { material ->
                val chunks = docChunkEmbeddingRepository.findAllByUserIdAndUserFileIdOrderByChunkNoAsc(userId, material.userFile.id)
                val combinedText = mergeChunkTextsForQuestionExtraction(chunks.map { it.chunkText })
                val extractionMethod = extractExtractionMethod(material.latestIngestionJob()?.metadataJson)
                val sourceFileName = decodeDisplayMaterialFileName(material.userFile.fileName)
                val regexCandidates = extractQuestionBlocksFromPastExam(combinedText)
                val recoveredCandidates = if (regexCandidates.size >= minOf(totalLimit, 2)) {
                    regexCandidates
                } else {
                    recoverQuestionBlocksFromPastExamWithAi(
                        material = material,
                        sourceFileName = sourceFileName,
                        extractionMethod = extractionMethod,
                        rawText = combinedText,
                        expectedQuestionCount = totalLimit
                    ).ifEmpty { regexCandidates }
                }
                logger.info(
                    "족보 원문 문제 추출 fileId={} fileName={} extractionMethod={} chunkCount={} chars={} regexCount={} finalCount={}",
                    material.userFile.id,
                    sourceFileName,
                    extractionMethod ?: "UNKNOWN",
                    chunks.size,
                    combinedText.length,
                    regexCandidates.size,
                    recoveredCandidates.size
                )
                recoveredCandidates.mapIndexed { index, block ->
                        ExtractedPastExamQuestionCandidate(
                            questionNo = index + 1,
                            questionText = block,
                            sourceFileName = sourceFileName,
                            extractionMethod = extractionMethod
                        )
                    }
            }
            .distinctBy { it.questionText.normalizeQuestionKey() }
            .take(totalLimit)
    }

    private fun extractQuestionBlocksFromPastExam(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val normalized = normalizePastExamExtractionText(rawText)
        val explicitQuestionMarkerRegex = Regex("문\\s*제\\s*[0-9]{1,2}\\s*[.:)]?")
        val explicitQuestionMatches = explicitQuestionMarkerRegex.findAll(normalized).toList()
        val qMarkerRegex = Regex("Q\\s*[0-9]{1,2}\\s*[.:)]?", RegexOption.IGNORE_CASE)
        val qMatches = qMarkerRegex.findAll(normalized).toList()
        val numberedLineMarkerRegex = Regex("(?m)^\\s*(문\\s*제\\s*[0-9]{1,2}\\s*[.:)]?|Q\\s*[0-9]{1,2}\\s*[.:)]?|[0-9]{1,2}\\s*[.)](?=\\s*[가-힣A-Za-z<(\\[]))")
        val inlineNumberedMarkerRegex = Regex("(?<![0-9])[0-9]{1,2}\\s*[.)](?=\\s*[가-힣A-Za-z<(\\[])")
        val matches = if (explicitQuestionMatches.size >= 2) {
            explicitQuestionMatches
        } else if (qMatches.size >= 2) {
            qMatches
        } else {
            val lineMatches = numberedLineMarkerRegex.findAll(normalized).toList()
            if (lineMatches.size >= 2) lineMatches else inlineNumberedMarkerRegex.findAll(normalized).toList()
        }
        if (matches.isEmpty()) return emptyList()

        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.first
            val end = matches.getOrNull(index + 1)?.range?.first ?: normalized.length
            normalized.substring(start, end)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
                .takeIf { it.length >= 18 }
        }
    }

    private fun normalizePastExamExtractionText(rawText: String): String {
        return rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("(?<!\\n)(문\\s*제\\s*[0-9]{1,2}\\s*[.:)]?)"), "\n$1")
            .replace(Regex("(?<!\\n)(Q\\s*[0-9]{1,2}\\s*[.:)]?)", RegexOption.IGNORE_CASE), "\n$1")
            .replace(Regex("([①②③④⑤⑥⑦⑧⑨⑩])")) { match ->
                val number = "①②③④⑤⑥⑦⑧⑨⑩".indexOf(match.value[0]) + 1
                "\n문제 $number. "
            }
            .replace(Regex("([⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳])")) { match ->
                val number = "⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳".indexOf(match.value[0]) + 11
                "\n문제 $number. "
            }
            .replace(Regex("(?<!\\n)(?<![0-9A-Za-z가-힣])([0-9]{1,2}\\s*[.)])(?=\\s*[가-힣A-Za-z<(\\[])", RegexOption.MULTILINE), "\n$1")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun mergeChunkTextsForQuestionExtraction(chunks: List<String>): String {
        if (chunks.isEmpty()) return ""
        var merged = chunks.first().trim()
        chunks.drop(1).forEach { rawChunk ->
            val chunk = rawChunk.trim()
            if (chunk.isBlank()) return@forEach
            if (merged.contains(chunk)) return@forEach
            val overlap = longestSuffixPrefixOverlap(merged, chunk)
            merged = if (overlap >= 40) {
                merged + chunk.substring(overlap)
            } else {
                "$merged\n$chunk"
            }
        }
        return merged
    }

    private fun longestSuffixPrefixOverlap(left: String, right: String): Int {
        val maxWindow = minOf(left.length, right.length, 220)
        for (size in maxWindow downTo 24) {
            if (left.regionMatches(left.length - size, right, 0, size, ignoreCase = false)) {
                return size
            }
        }
        return 0
    }

    private fun recoverQuestionBlocksFromPastExamWithAi(
        material: StudentCourseMaterial,
        sourceFileName: String,
        extractionMethod: String?,
        rawText: String,
        expectedQuestionCount: Int
    ): List<String> {
        if (rawText.length < 40) return emptyList()
        return runCatching {
            interviewAiOrchestrator.recoverPastExamPracticeQuestionCandidates(
                universityName = material.course.universityName.trim(),
                departmentName = material.course.departmentName.trim(),
                courseName = material.course.courseName,
                professorName = material.course.professorName,
                sourceFileName = sourceFileName,
                extractionMethod = extractionMethod,
                rawText = rawText.take(20_000),
                expectedQuestionCount = expectedQuestionCount,
                language = InterviewLanguage.KO
            )
        }.onFailure { ex ->
            logger.warn(
                "족보 원문 AI 분리 fallback 실패 fileId={} fileName={} reason={}",
                material.userFile.id,
                sourceFileName,
                ex.message
            )
        }.getOrDefault(emptyList())
    }

    private fun validateSessionCreationRequest(
        request: CreateStudentExamSessionRequest,
        hasPastExamReference: Boolean,
        hasSelectedPastExamReference: Boolean
    ) {
        if (request.generationMode == StudentExamGenerationMode.STANDARD && request.difficultyLevel == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "일반형 모의고사는 난이도를 선택해 주세요.")
        }
        if (
            (request.generationMode == StudentExamGenerationMode.PAST_EXAM ||
                request.generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE) &&
            !hasPastExamReference
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "족보형 모의고사는 분석 완료된 족보가 1개 이상 필요합니다.")
        }
        if (
            (request.generationMode == StudentExamGenerationMode.PAST_EXAM ||
                request.generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE) &&
            !hasSelectedPastExamReference
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "모의고사에 사용할 족보를 1개 이상 선택해 주세요.")
        }
        if (request.generationMode == StudentExamGenerationMode.STANDARD && request.questionStyles.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "문제 스타일을 1개 이상 선택해 주세요.")
        }
    }

    private fun resolveSelectedPastExamMaterials(
        request: CreateStudentExamSessionRequest,
        readyPastExamMaterials: List<StudentCourseMaterial>
    ): List<StudentCourseMaterial> {
        if (
            request.generationMode != StudentExamGenerationMode.PAST_EXAM &&
            request.generationMode != StudentExamGenerationMode.PAST_EXAM_PRACTICE
        ) {
            return readyPastExamMaterials
        }
        if (request.selectedPastExamMaterialIds.isEmpty()) {
            return readyPastExamMaterials
        }

        val selectedIds = request.selectedPastExamMaterialIds.toSet()
        val selectedMaterials = readyPastExamMaterials.filter { it.id in selectedIds }
        if (selectedMaterials.size != selectedIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 족보 중 사용할 수 없는 자료가 포함되어 있습니다.")
        }
        return selectedMaterials
    }

    private fun normalizeRequestedQuestionStyles(request: CreateStudentExamSessionRequest): List<StudentExamQuestionStyle> {
        return if (
            request.generationMode == StudentExamGenerationMode.PAST_EXAM ||
            request.generationMode == StudentExamGenerationMode.PAST_EXAM_PRACTICE
        ) {
            StudentExamQuestionStyle.entries.toList()
        } else {
            request.questionStyles.distinct()
        }
    }

    private fun normalizeOptionalReferenceExample(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (normalized.equals("null", ignoreCase = true)) return null
        return normalized
    }

    private fun isUsablePastExamPracticeQuestionText(questionText: String): Boolean {
        val normalized = questionText
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length < 18) return false
        if (Regex("[�□◻]").containsMatchIn(normalized)) return false

        val hangulCount = normalized.count { it in '\uAC00'..'\uD7A3' }
        val letterCount = normalized.count(Char::isLetter)
        val uppercaseLetterCount = normalized.count { it.isLetter() && it.isUpperCase() }
        val digitCount = normalized.count(Char::isDigit)
        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val uppercaseNoiseTokens = tokens.count { token ->
            val letters = token.filter(Char::isLetter)
            letters.length >= 4 && letters.all(Char::isUpperCase)
        }
        val shortBrokenPattern = Regex("[A-Za-z]{1,3}\\s+[A-Za-z]{1,3}\\s+[A-Za-z]{1,3}\\s+[A-Za-z]{1,3}").containsMatchIn(normalized)
        val examSignal = Regex("(문\\s*제\\s*\\d+|[가-힣]+하시오|[가-힣]+하라|구하시오|보이시오|작성하시오|설명하시오|제시하시오|비교하시오|구현하시오|명령어|알고리즘|행렬|그래프|배열|트리|시간복잡도|증명)").containsMatchIn(normalized)

        if (uppercaseNoiseTokens >= 2) return false
        if (shortBrokenPattern && hangulCount < 6) return false
        if (letterCount >= 12 && uppercaseLetterCount.toDouble() / letterCount.toDouble() > 0.72 && hangulCount < 6) return false
        if (hangulCount == 0 && digitCount < 2) return false
        if (!examSignal) return false
        return true
    }

    private fun buildSessionTitle(
        course: StudentCourse,
        questionCount: Int,
        generationMode: StudentExamGenerationMode,
        selectedPastExamMaterials: List<StudentCourseMaterial>
    ): String {
        val professorSuffix = course.professorName?.trim()?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val selectedPastExamLabel = selectedPastExamMaterials.firstOrNull()?.let { material ->
            val primaryName = decodeDisplayMaterialFileName(material.userFile.fileName)
            if (selectedPastExamMaterials.size <= 1) {
                primaryName
            } else {
                "$primaryName 외 ${selectedPastExamMaterials.size - 1}개"
            }
        }
        return when (generationMode) {
            StudentExamGenerationMode.PAST_EXAM ->
                "족보형_${selectedPastExamLabel ?: course.courseName}(${questionCount}문항)"
            StudentExamGenerationMode.PAST_EXAM_PRACTICE ->
                "족보 그대로 연습_${selectedPastExamLabel ?: course.courseName}(${questionCount}문항)"
            StudentExamGenerationMode.WRONG_ANSWER_RETEST ->
                "오답노트_${course.courseName}(${questionCount}문항)"
            StudentExamGenerationMode.STANDARD ->
                "${course.courseName}$professorSuffix 모의고사 (${questionCount}문항)"
        }
    }

    private fun encodeQuestionStyles(styles: List<StudentExamQuestionStyle>): String {
        return styles.distinct().joinToString(",") { it.name }
    }

    private fun decodeQuestionStyles(csv: String): List<StudentExamQuestionStyle> {
        return csv.split(",")
            .mapNotNull { token ->
                runCatching { StudentExamQuestionStyle.valueOf(token.trim()) }.getOrNull()
            }
            .distinct()
            .ifEmpty { listOf(StudentExamQuestionStyle.DEFINITION) }
    }

    private fun normalizeQuestionStyle(
        rawStyle: String,
        preferredStyles: List<StudentExamQuestionStyle>
    ): StudentExamQuestionStyle {
        return runCatching { StudentExamQuestionStyle.valueOf(rawStyle.trim().uppercase()) }.getOrElse {
            preferredStyles.firstOrNull() ?: StudentExamQuestionStyle.DEFINITION
        }
    }

    private fun parseQuestionStyle(rawStyle: String): StudentExamQuestionStyle? {
        return runCatching { StudentExamQuestionStyle.valueOf(rawStyle.trim().uppercase()) }.getOrNull()
    }

    private fun StudentExamQuestionStyle.requiresReferenceExample(): Boolean {
        return this == StudentExamQuestionStyle.CODING ||
            this == StudentExamQuestionStyle.CALCULATION ||
            this == StudentExamQuestionStyle.PRACTICAL
    }

    private fun String.normalizeQuestionKey(): String =
        lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun evaluateSubmittedAnswers(
        userId: Long,
        session: StudentExamSession,
        course: StudentCourse,
        questions: List<StudentExamQuestion>,
        answerByQuestionId: Map<Long, com.cw.vlainter.domain.student.dto.StudentExamAnswerRequest>
    ): Map<Long, EvaluatedAnswer> {
        val answeredQuestions = questions.filter { question ->
            answerByQuestionId[question.id]?.answerText?.trim()?.isNotBlank() == true
        }
        if (answeredQuestions.isEmpty()) return emptyMap()

        return try {
            userGeminiApiKeyService.withUserApiKey(userId) {
                interviewAiOrchestrator.evaluateCourseExamAnswersBatch(
                    universityName = course.universityName,
                    departmentName = course.departmentName,
                    courseName = course.courseName,
                    generationMode = session.generationMode.name,
                    difficultyLevel = session.difficultyLevel,
                    items = answeredQuestions.map { question ->
                        CourseExamEvaluationInput(
                            key = question.id.toString(),
                            questionStyle = question.questionStyle.name,
                            questionText = question.questionText,
                            canonicalAnswer = question.canonicalAnswer,
                            gradingCriteria = question.gradingCriteria,
                            referenceExample = question.referenceExample,
                            maxScore = question.maxScore,
                            userAnswer = answerByQuestionId[question.id]?.answerText?.trim().orEmpty()
                        )
                    },
                    responseLanguage = InterviewLanguage.KO
                ).mapNotNull { (key, value) ->
                    key.toLongOrNull()?.let { it to value.toEvaluatedAnswer() }
                }.toMap()
            }
        } catch (_: Exception) {
            answeredQuestions.associate { question ->
                question.id to evaluateAnswer(
                    question = question,
                    answerText = answerByQuestionId[question.id]?.answerText?.trim().orEmpty()
                )
            }
        }
    }

    private fun CourseExamEvaluationResult.toEvaluatedAnswer(): EvaluatedAnswer {
        return EvaluatedAnswer(
            score = score,
            isCorrect = score >= passScore,
            feedback = feedback
        )
    }

    private fun evaluateAnswer(question: StudentExamQuestion, answerText: String): EvaluatedAnswer {
        val questionTokens = question.questionText.lowercase()
            .replace(Regex("[^a-z0-9가-힣\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .distinct()
        val answerTokens = answerText.lowercase()
            .replace(Regex("[^a-z0-9가-힣\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .toSet()

        val overlapCount = questionTokens.count { it in answerTokens }
        val keywordScore = if (questionTokens.isEmpty()) {
            0
        } else {
            ((overlapCount.toDouble() / questionTokens.size.toDouble()) * 50.0).toInt()
        }
        val lengthScore = when {
            answerText.length >= 180 -> 50
            answerText.length >= 120 -> 42
            answerText.length >= 80 -> 34
            answerText.length >= 40 -> 24
            answerText.length >= 20 -> 14
            else -> 4
        }
        val normalizedScore = (keywordScore + lengthScore).coerceIn(0, 100)
        val score = ((normalizedScore / 100.0) * question.maxScore.toDouble()).roundToInt().coerceIn(0, question.maxScore)
        val feedback = when {
            score >= (question.maxScore * 0.8).roundToInt() -> "핵심 개념과 풀이 흐름이 비교적 잘 드러납니다. 정답 표현을 더 정교하게 다듬으면 좋습니다."
            score >= (question.maxScore * 0.5).roundToInt() -> "부분적으로 맞지만 핵심 근거, 계산 과정, 구현 세부사항을 더 보강해야 합니다."
            else -> "핵심 개념 반영이 부족합니다. 정답 예시와 채점 기준을 참고해 다시 정리해 보세요."
        }
        return EvaluatedAnswer(
            score = score,
            isCorrect = score >= (question.maxScore * 0.6).roundToInt(),
            feedback = feedback
        )
    }

    private fun StudentCourse.toResponse(): StudentCourseResponse = StudentCourseResponse(
        courseId = id,
        universityName = universityName,
        departmentName = departmentName,
        courseName = courseName,
        professorName = professorName,
        description = description,
        materialCount = studentCourseMaterialRepository.countByCourse_Id(id).toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun StudentExamSession.toResponse(questions: List<StudentExamQuestion>): StudentExamSessionResponse = StudentExamSessionResponse(
        sessionId = id,
        courseId = courseId,
        title = title,
        status = status,
        generationMode = generationMode,
        difficultyLevel = difficultyLevel,
        questionStyles = decodeQuestionStyles(questionStylesCsv),
        questionCount = questionCount,
        maxScore = maxScore,
        sourceMaterialCount = sourceMaterialCount,
        answeredCount = answeredCount,
        totalScore = totalScore,
        submittedAt = submittedAt,
        previewQuestions = questions.sortedBy { it.questionOrder }.take(3).map(StudentExamQuestion::questionText),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun StudentExamSession.toDetailResponse(
        questions: List<StudentExamQuestion>,
        sourceContexts: Map<Long, PastExamQuestionSourceContext> = emptyMap()
    ): StudentExamSessionDetailResponse = StudentExamSessionDetailResponse(
        sessionId = id,
        courseId = courseId,
        title = title,
        status = status,
        generationMode = generationMode,
        difficultyLevel = difficultyLevel,
        questionStyles = decodeQuestionStyles(questionStylesCsv),
        questionCount = questionCount,
        maxScore = maxScore,
        sourceMaterialCount = sourceMaterialCount,
        answeredCount = answeredCount,
        totalScore = totalScore,
        submittedAt = submittedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        questions = questions.map { question ->
            StudentExamQuestionResponse(
                questionId = question.id,
                questionOrder = question.questionOrder,
                questionStyle = question.questionStyle,
                questionText = question.questionText,
                canonicalAnswer = question.canonicalAnswer,
                gradingCriteria = question.gradingCriteria,
                referenceExample = question.referenceExample,
                maxScore = question.maxScore,
                answerText = question.answerText,
                score = question.score,
                feedback = question.feedback,
                isCorrect = question.isCorrect,
                answeredAt = question.answeredAt,
                sourceFileName = sourceContexts[question.id]?.sourceFileName,
                sourceVisualAssets = sourceContexts[question.id]?.sourceVisualAssets.orEmpty()
            )
        }
    )

    private fun StudentWrongAnswerSet.toResponse(items: List<StudentWrongAnswerItem>): StudentWrongAnswerSetResponse = StudentWrongAnswerSetResponse(
        setId = id,
        sessionId = sessionId,
        courseId = courseId,
        title = title,
        questionCount = questionCount,
        retestSessionId = retestSessionId,
        previewQuestions = items.sortedBy { it.questionOrder }.take(3).map(StudentWrongAnswerItem::questionText),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun StudentWrongAnswerSet.toDetailResponse(
        items: List<StudentWrongAnswerItem>,
        sourceContexts: Map<Long, PastExamQuestionSourceContext> = emptyMap()
    ): StudentWrongAnswerSetDetailResponse =
        StudentWrongAnswerSetDetailResponse(
            setId = id,
            sessionId = sessionId,
            courseId = courseId,
            title = title,
            questionCount = questionCount,
            retestSessionId = retestSessionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            items = items.map { item ->
                StudentWrongAnswerItemResponse(
                    questionId = item.questionId,
                    questionOrder = item.questionOrder,
                    questionStyle = item.questionStyle,
                    questionText = item.questionText,
                    canonicalAnswer = item.canonicalAnswer,
                    gradingCriteria = item.gradingCriteria,
                    referenceExample = item.referenceExample,
                    maxScore = item.maxScore,
                    answerText = item.answerText,
                    score = item.score,
                    feedback = item.feedback,
                    sourceFileName = sourceContexts[item.questionId]?.sourceFileName,
                    sourceVisualAssets = sourceContexts[item.questionId]?.sourceVisualAssets.orEmpty()
                )
            }
        )

    private fun StudentCourseMaterial.toResponse(): StudentCourseMaterialResponse {
        val latestJob = latestIngestionJob()
        val extractionMethod = latestJob?.metadataJson?.let(::extractExtractionMethod)
        val visualAssets = loadVisualAssetResponses(this)
        return StudentCourseMaterialResponse(
            materialId = id,
            fileId = userFile.id,
            fileType = userFile.fileType,
            materialKind = resolveMaterialKind(this),
            fileName = decodeDisplayMaterialFileName(userFile.fileName),
            originalFileName = userFile.originalFileName,
            fileUrl = buildCourseMaterialContentUrl(course.id, id),
            createdAt = createdAt,
            ingestionStatus = latestJob?.status?.name,
            ingested = latestJob?.status == DocumentIngestionStatus.READY,
            errorMessage = latestJob?.errorMessage,
            extractionMethod = extractionMethod,
            ocrUsed = extractionMethod == "OCR_TESSERACT",
            visualAssets = visualAssets
        )
    }

    private fun buildQuestionSourceContexts(
        userId: Long,
        session: StudentExamSession,
        questions: List<StudentExamQuestion>
    ): Map<Long, PastExamQuestionSourceContext> {
        val sourceSession = resolveSourceSessionForQuestionContext(userId, session) ?: return emptyMap()
        if (sourceSession.generationMode != StudentExamGenerationMode.PAST_EXAM_PRACTICE || questions.isEmpty()) {
            return emptyMap()
        }

        val readyPastExamMaterials = studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(sourceSession.courseId)
            .filter { material ->
                resolveMaterialKind(material) == StudentCourseMaterialKind.PAST_EXAM &&
                    material.latestIngestionJob()?.status == DocumentIngestionStatus.READY
            }
        if (readyPastExamMaterials.isEmpty()) return emptyMap()

        val candidateByQuestionKey = extractPastExamPracticeQuestionCandidates(
            userId = userId,
            materials = readyPastExamMaterials,
            totalLimit = maxOf(questions.size * 8, 40)
        ).associateBy { it.questionText.normalizeQuestionKey() }

        val visualAssetsByFileName = readyPastExamMaterials.associate { material ->
            decodeDisplayMaterialFileName(material.userFile.fileName) to loadVisualAssetResponses(material)
        }

        return questions.mapNotNull { question ->
            val matchedCandidate = candidateByQuestionKey[question.questionText.normalizeQuestionKey()] ?: return@mapNotNull null
            question.id to PastExamQuestionSourceContext(
                sourceFileName = matchedCandidate.sourceFileName,
                sourceVisualAssets = visualAssetsByFileName[matchedCandidate.sourceFileName].orEmpty()
            )
        }.toMap()
    }

    private fun resolveSourceSessionForQuestionContext(userId: Long, session: StudentExamSession): StudentExamSession? {
        return when (session.generationMode) {
            StudentExamGenerationMode.PAST_EXAM_PRACTICE -> session
            StudentExamGenerationMode.WRONG_ANSWER_RETEST ->
                studentWrongAnswerSetRepository.findByRetestSessionIdAndUserId(session.id, userId)
                    ?.let { getOwnedSession(userId, it.sessionId) }
            else -> null
        }
    }

    private fun loadVisualAssetResponses(material: StudentCourseMaterial): List<StudentCourseMaterialVisualAssetResponse> {
        return studentCourseMaterialVisualAssetRepository.findAllByMaterial_IdOrderByAssetOrderAsc(material.id)
            .mapNotNull { asset ->
                runCatching {
                    StudentCourseMaterialVisualAssetResponse(
                        assetId = asset.id,
                        assetType = asset.assetType,
                        assetOrder = asset.assetOrder,
                        label = asset.label,
                        pageNo = asset.pageNo,
                        slideNo = asset.slideNo,
                        width = asset.width,
                        height = asset.height,
                        downloadUrl = buildVisualAssetContentUrl(asset.id)
                    )
                }.getOrElse { ex ->
                    logger.warn("학생 자료 시각 자산 URL 생성 실패 materialId={} assetId={} reason={}", material.id, asset.id, ex.message)
                    null
                }
            }
    }

    private fun buildCourseMaterialContentUrl(courseId: Long, materialId: Long): String {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/student/courses/{courseId}/materials/{materialId}/content")
            .buildAndExpand(courseId, materialId)
            .toUriString()
    }

    private fun buildVisualAssetContentUrl(assetId: Long): String {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/student/courses/material-visual-assets/{assetId}/content")
            .buildAndExpand(assetId)
            .toUriString()
    }

    private fun buildVisualAssetDownloadFileName(
        material: StudentCourseMaterial,
        asset: com.cw.vlainter.domain.student.entity.StudentCourseMaterialVisualAsset
    ): String {
        val baseName = decodeDisplayMaterialFileName(material.userFile.fileName)
            .substringBeforeLast('.')
            .ifBlank { "material" }
        val suffix = when (asset.assetType) {
            StudentCourseMaterialVisualAssetType.PDF_PAGE_RENDER -> "page-${asset.pageNo ?: asset.assetOrder}"
            StudentCourseMaterialVisualAssetType.PPT_SLIDE_RENDER -> "slide-${asset.slideNo ?: asset.assetOrder}"
            StudentCourseMaterialVisualAssetType.DOCX_EMBEDDED_IMAGE -> "image-${asset.assetOrder}"
            StudentCourseMaterialVisualAssetType.ORIGINAL_IMAGE -> "original-${asset.assetOrder}"
        }
        val extension = when (asset.contentType?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "png"
        }
        return "$baseName-$suffix.$extension"
    }

    private fun StudentCourseMaterial.latestIngestionJob() =
        documentIngestionJobRepository.findTopByDocumentFileIdOrderByRequestedAtDesc(userFile.id)
            ?.let { job ->
                if (!isStaleIngestionJob(job)) {
                    return@let job
                }
                documentInterviewService.markIngestionFailed(job.id, "이전 분석 작업이 중단되었습니다. 다시 시도해 주세요.")
                documentIngestionJobRepository.findTopByDocumentFileIdOrderByRequestedAtDesc(userFile.id)
            }

    private fun isStaleIngestionJob(job: DocumentIngestionJob): Boolean {
        val now = OffsetDateTime.now()
        return when (job.status) {
            DocumentIngestionStatus.PROCESSING ->
                job.startedAt?.isBefore(now.minusMinutes(10)) ?: false
            DocumentIngestionStatus.QUEUED ->
                job.requestedAt?.isBefore(now.minusMinutes(10)) ?: false
            else -> false
        }
    }

    private fun extractExtractionMethod(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            objectMapper.readTree(rawJson)
                .path("extractionMethod")
                .takeIf { !it.isMissingNode && !it.isNull }
                ?.asText()
                ?.trim()
        }.getOrNull().takeIf { !it.isNullOrBlank() }
    }

    private data class EvaluatedAnswer(
        val score: Int,
        val isCorrect: Boolean,
        val feedback: String
    )

    private data class ExtractedPastExamQuestionCandidate(
        val questionNo: Int,
        val questionText: String,
        val sourceFileName: String,
        val extractionMethod: String?
    )

    private data class PastExamQuestionSourceContext(
        val sourceFileName: String,
        val sourceVisualAssets: List<StudentCourseMaterialVisualAssetResponse>
    )
}
