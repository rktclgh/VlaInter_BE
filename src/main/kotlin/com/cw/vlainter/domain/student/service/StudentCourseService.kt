package com.cw.vlainter.domain.student.service

import com.cw.vlainter.domain.student.dto.CreateStudentCourseRequest
import com.cw.vlainter.domain.student.dto.CreateStudentExamSessionRequest
import com.cw.vlainter.domain.student.dto.StudentCourseMaterialResponse
import com.cw.vlainter.domain.student.dto.StudentCourseResponse
import com.cw.vlainter.domain.student.dto.StudentExamQuestionResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionDetailResponse
import com.cw.vlainter.domain.student.dto.StudentExamSessionResponse
import com.cw.vlainter.domain.student.dto.SubmitStudentExamAnswersRequest
import com.cw.vlainter.domain.student.entity.StudentCourse
import com.cw.vlainter.domain.student.entity.StudentCourseMaterial
import com.cw.vlainter.domain.student.entity.StudentExamQuestion
import com.cw.vlainter.domain.student.entity.StudentExamSession
import com.cw.vlainter.domain.student.entity.StudentExamSessionStatus
import com.cw.vlainter.domain.student.repository.StudentExamQuestionRepository
import com.cw.vlainter.domain.student.repository.StudentExamSessionRepository
import com.cw.vlainter.domain.student.repository.StudentCourseMaterialRepository
import com.cw.vlainter.domain.student.repository.StudentCourseRepository
import com.cw.vlainter.domain.user.entity.UserServiceMode
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.domain.userFile.entity.FileType
import com.cw.vlainter.domain.userFile.service.UserFileService
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class StudentCourseService(
    private val studentCourseRepository: StudentCourseRepository,
    private val studentCourseMaterialRepository: StudentCourseMaterialRepository,
    private val studentExamSessionRepository: StudentExamSessionRepository,
    private val studentExamQuestionRepository: StudentExamQuestionRepository,
    private val userRepository: UserRepository,
    private val userFileService: UserFileService
) {
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

    @Transactional(readOnly = true)
    fun getCourseMaterials(principal: AuthPrincipal, courseId: Long): List<StudentCourseMaterialResponse> {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        return studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(course.id)
            .map { it.toResponse() }
    }

    @Transactional
    fun uploadCourseMaterial(principal: AuthPrincipal, courseId: Long, file: MultipartFile): StudentCourseMaterialResponse {
        val user = getValidatedStudentUser(principal)
        val course = getOwnedCourse(user.id, courseId)
        val uploaded = userFileService.uploadMyFile(principal, FileType.COURSE_MATERIAL, file)
        val userFile = userFileService.loadOwnedFile(user.id, uploaded.fileId)
        val saved = studentCourseMaterialRepository.save(
            StudentCourseMaterial(
                course = course,
                userFile = userFile
            )
        )
        return saved.toResponse()
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
        val materials = studentCourseMaterialRepository.findAllByCourse_IdOrderByCreatedAtDesc(course.id)
        if (materials.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "과목 자료를 1개 이상 업로드한 뒤 모의고사를 만들 수 있습니다.")
        }

        val savedSession = studentExamSessionRepository.save(
            StudentExamSession(
                courseId = course.id,
                userId = user.id,
                status = StudentExamSessionStatus.READY,
                questionCount = request.questionCount,
                sourceMaterialCount = materials.size,
                title = buildSessionTitle(course, request.questionCount)
            )
        )
        val questions = buildSeedQuestions(course, materials, request.questionCount)
        val savedQuestions = studentExamQuestionRepository.saveAll(
            questions.mapIndexed { index, text ->
                StudentExamQuestion(
                    sessionId = savedSession.id,
                    questionOrder = index + 1,
                    questionText = text
                )
            }
        )
        return savedSession.toResponse(savedQuestions)
    }

    @Transactional(readOnly = true)
    fun getSessionDetail(principal: AuthPrincipal, sessionId: Long): StudentExamSessionDetailResponse {
        val user = getValidatedStudentUser(principal)
        val session = getOwnedSession(user.id, sessionId)
        val questions = studentExamQuestionRepository.findAllBySessionIdOrderByQuestionOrderAsc(session.id)
        return session.toDetailResponse(questions)
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
        questions.forEach { question ->
            val submitted = answerByQuestionId[question.id] ?: return@forEach
            val trimmedAnswer = submitted.answerText.trim()
            if (trimmedAnswer.isBlank()) return@forEach
            val evaluation = evaluateAnswer(question.questionText, trimmedAnswer)
            question.answerText = trimmedAnswer
            question.score = evaluation.score
            question.feedback = evaluation.feedback
            question.isCorrect = evaluation.isCorrect
            question.answeredAt = answeredAt
        }
        val savedQuestions = studentExamQuestionRepository.saveAll(questions)
        val scoredQuestions = savedQuestions.filter { it.score != null }
        session.answeredCount = scoredQuestions.size
        session.totalScore = scoredQuestions.mapNotNull { it.score }.average().takeIf { !it.isNaN() }?.toInt()
        session.status = StudentExamSessionStatus.SUBMITTED
        session.submittedAt = answeredAt
        val savedSession = studentExamSessionRepository.save(session)
        return savedSession.toDetailResponse(savedQuestions)
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

    private fun normalizeProfessorName(value: String?): String = value?.trim().orEmpty()

    private fun buildSessionTitle(course: StudentCourse, questionCount: Int): String {
        val professorSuffix = course.professorName?.trim()?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "${course.courseName}$professorSuffix 모의고사 (${questionCount}문항)"
    }

    private fun buildSeedQuestions(
        course: StudentCourse,
        materials: List<StudentCourseMaterial>,
        questionCount: Int
    ): List<String> {
        val firstMaterialName = materials.firstOrNull()?.userFile?.originalFileName
            ?.takeIf { it.isNotBlank() }
            ?: materials.firstOrNull()?.userFile?.fileName
            ?: "업로드한 자료"
        val baseQuestions = listOf(
            "${course.courseName}에서 가장 핵심이 되는 개념을 하나 설명하고, 왜 중요한지 정리해 보세요.",
            "${firstMaterialName}를 기준으로 시험에 나올 수 있는 핵심 주제를 3개로 정리해 보세요.",
            "${course.courseName} 범위에서 서로 비교해야 하는 개념 두 가지를 골라 차이를 설명해 보세요.",
            "${course.courseName}에서 교수님이 서술형으로 물을 가능성이 높은 정의를 한 가지 골라 답해 보세요.",
            "${course.courseName}를 공부할 때 반드시 암기해야 할 공식, 용어, 절차를 정리해 보세요.",
            "${course.courseName} 개념을 실제 사례나 문제 상황에 적용하는 방식으로 설명해 보세요.",
            "${course.courseName} 자료를 바탕으로 단답형 시험에 대비해 외워야 할 포인트를 말해 보세요.",
            "${course.courseName} 시험 직전 최종 점검해야 할 단원이나 개념을 하나 정하고 이유를 설명해 보세요."
        )
        return List(questionCount) { index -> baseQuestions[index % baseQuestions.size] }
    }

    private fun evaluateAnswer(questionText: String, answerText: String): EvaluatedAnswer {
        val questionTokens = questionText.lowercase()
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
        val score = (keywordScore + lengthScore).coerceIn(0, 100)
        val feedback = when {
            score >= 80 -> "핵심 키워드를 잘 포함했고 답안 길이도 충분합니다."
            score >= 60 -> "핵심 개념은 일부 포함했지만, 근거나 예시를 더 보강하면 좋습니다."
            else -> "답안 길이나 핵심 개념 반영이 부족합니다. 자료 기준 핵심 용어를 더 포함해 보세요."
        }
        return EvaluatedAnswer(
            score = score,
            isCorrect = score >= 60,
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
        questionCount = questionCount,
        sourceMaterialCount = sourceMaterialCount,
        answeredCount = answeredCount,
        totalScore = totalScore,
        submittedAt = submittedAt,
        previewQuestions = questions.sortedBy { it.questionOrder }.take(3).map(StudentExamQuestion::questionText),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun StudentExamSession.toDetailResponse(questions: List<StudentExamQuestion>): StudentExamSessionDetailResponse = StudentExamSessionDetailResponse(
        sessionId = id,
        courseId = courseId,
        title = title,
        status = status,
        questionCount = questionCount,
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
                questionText = question.questionText,
                answerText = question.answerText,
                score = question.score,
                feedback = question.feedback,
                isCorrect = question.isCorrect,
                answeredAt = question.answeredAt
            )
        }
    )

    private fun StudentCourseMaterial.toResponse(): StudentCourseMaterialResponse = StudentCourseMaterialResponse(
        materialId = id,
        fileId = userFile.id,
        fileType = userFile.fileType,
        fileName = userFile.fileName,
        originalFileName = userFile.originalFileName,
        fileUrl = userFile.fileUrl,
        createdAt = createdAt
    )

    private data class EvaluatedAnswer(
        val score: Int,
        val isCorrect: Boolean,
        val feedback: String
    )
}
