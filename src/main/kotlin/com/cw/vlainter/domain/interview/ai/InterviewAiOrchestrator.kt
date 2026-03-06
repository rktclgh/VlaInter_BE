package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.domain.interview.entity.QaQuestion
import com.cw.vlainter.domain.interview.entity.QuestionDifficulty
import com.cw.vlainter.domain.interview.service.buildIdealModelAnswer
import com.cw.vlainter.global.config.properties.AiProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class InterviewAiOrchestrator(
    private val aiProperties: AiProperties,
    private val llmProviderRouter: LlmProviderRouter,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val jobLabels = mapOf(
        "BACKEND" to "백엔드 개발자",
        "FRONTEND" to "프론트엔드 개발자",
        "EMBEDDED" to "임베디드 개발자",
        "SYSTEM_ARCH" to "시스템 아키텍트",
        "MOBILE" to "모바일 개발자",
        "DATA" to "데이터 직무",
        "AI" to "AI 개발자",
        "DEVOPS" to "데브옵스 엔지니어",
        "SECURITY" to "보안 엔지니어",
        "FINANCE" to "재무",
        "ACCOUNTING" to "회계",
        "SALES" to "영업",
        "MARKETING" to "마케팅",
        "HR" to "인사",
        "DESIGN" to "디자인",
        "PM" to "프로덕트 매니저"
    )
    private val skillLabels = mapOf(
        "SPRING" to "Spring",
        "REACT" to "React",
        "NODEJS" to "Node.js",
        "MSA" to "MSA",
        "RTOS" to "RTOS",
        "DDD" to "DDD",
        "MCU" to "MCU",
        "CI_CD" to "CI/CD",
        "GITHUB_ACTIONS" to "GitHub Actions",
        "JPA" to "JPA",
        "HIBERNATE" to "Hibernate",
        "DOCKER" to "Docker",
        "KUBERNETES" to "Kubernetes",
        "K8S" to "Kubernetes",
        "RAG" to "RAG",
        "STATE_MANAGEMENT" to "상태 관리",
        "CLOUD" to "클라우드"
    )

    fun evaluateTechAnswer(question: QaQuestion?, userAnswer: String): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val prompt = buildEvaluationPrompt(question, userAnswer)
        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            val parsed = parseEvaluationJson(generated.text)
            parsed.copy(model = generated.model, modelVersion = generated.modelVersion)
        }.onFailure { ex ->
            logger.warn("AI 평가 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) null else throw ex
        }
    }

    fun generateDocumentQuestions(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>
    ): List<GeneratedDocumentQuestion> {
        require(questionCount > 0) { "questionCount must be positive." }
        val prompt = buildDocumentQuestionPrompt(fileTypeLabel, difficulty, questionCount, contextSnippets)
        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.4)
            parseGeneratedDocumentQuestions(generated.text, fileTypeLabel).take(questionCount)
        }.onFailure { ex ->
            logger.warn("문서 질문 생성 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) {
                buildHeuristicDocumentQuestions(fileTypeLabel, difficulty, questionCount, contextSnippets)
            } else {
                throw ex
            }
        }
    }

    fun generateTechQuestions(
        categoryPath: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<GeneratedTechQuestion> {
        require(questionCount > 0) { "questionCount must be positive." }
        val labels = parseCategoryLabels(categoryPath)
        val prompt = buildTechQuestionPrompt(categoryPath, difficulty, questionCount)
        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt, temperature = 0.5)
            val parsed = parseGeneratedTechQuestions(generated.text)
            val validated = validateGeneratedTechQuestions(parsed, labels, difficulty)
            if (validated.isEmpty()) {
                throw IllegalStateException("생성된 기술 질문 품질 검증에 실패했습니다.")
            }
            backfillTechQuestions(validated, labels, difficulty, questionCount)
        }.onFailure { ex ->
            logger.warn("기술 질문 생성 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) {
                buildHeuristicTechQuestions(categoryPath, difficulty, questionCount)
            } else {
                throw ex
            }
        }
    }

    fun evaluateDocumentAnswer(
        questionText: String,
        referenceAnswer: String?,
        evidence: List<String>,
        userAnswer: String
    ): AiTurnEvaluation? {
        if (userAnswer.isBlank()) return null

        val prompt = """
            당신은 문서 기반 모의면접 평가관입니다.
            아래 입력을 바탕으로 한국어 JSON만 출력하세요.

            [질문]
            $questionText

            [참고 답안]
            ${referenceAnswer?.takeIf { it.isNotBlank() } ?: "(참고 답안 없음)"}

            [근거 포인트]
            ${if (evidence.isEmpty()) "(근거 없음)" else evidence.joinToString("\n- ", prefix = "- ")}

            [사용자 답변]
            $userAnswer

            출력 JSON 스키마:
            {
              "score": 0~100 숫자(소수점 2자리까지),
              "feedback": "총평(2~4문장)",
              "bestPractice": "개선 가이드(2~4문장)",
              "rubric": {
                "coverage": 0~100,
                "accuracy": 0~100,
                "communication": 0~100
              },
              "evidence": ["평가 근거", "..."]
            }

            규칙:
            - 반드시 JSON 객체만 반환
            - 답변이 문서 맥락과 어긋나면 낮은 점수를 부여
            - 사실 기반 근거와 전달력을 함께 평가
        """.trimIndent()

        return runCatching {
            val generated = llmProviderRouter.generateJson(prompt)
            val parsed = parseEvaluationJson(generated.text)
            parsed.copy(model = generated.model, modelVersion = generated.modelVersion)
        }.onFailure { ex ->
            logger.warn("문서 기반 AI 평가 실패(provider={}): {}", aiProperties.provider, ex.message)
        }.getOrElse { ex ->
            if (aiProperties.fallbackToHeuristic) null else throw ex
        }
    }

    private fun buildEvaluationPrompt(question: QaQuestion?, userAnswer: String): String {
        val questionText = question?.questionText.orEmpty()
        val canonicalAnswer = question?.canonicalAnswer?.takeIf { it.isNotBlank() } ?: "(모범답안 없음)"
        val category = question?.category?.path ?: "(카테고리 없음)"
        val difficulty = question?.difficulty?.name ?: "(난이도 없음)"
        val tags = question?.tagsJson ?: "[]"

        return """
            당신은 기술면접 평가관입니다.
            아래 입력을 기반으로 한국어로 엄격한 JSON만 출력하세요.

            [질문]
            $questionText

            [모범답안]
            $canonicalAnswer

            [메타]
            category=$category
            difficulty=$difficulty
            tags=$tags

            [사용자 답변]
            $userAnswer

            출력 JSON 스키마:
            {
              "score": 0~100 숫자(소수점 2자리까지),
              "feedback": "총평(2~4문장)",
              "bestPractice": "개선 가이드(2~4문장)",
              "rubric": {
                "coverage": 0~100,
                "accuracy": 0~100,
                "communication": 0~100
              },
              "evidence": ["답변에서 잘한 점/부족한 점 근거", "..."]
            }

            규칙:
            - 반드시 JSON 객체만 반환 (코드블록 금지)
            - 점수는 관대하지 않게, 근거 중심으로 산정
            - 사용자 답변이 질문과 무관하면 낮은 점수 부여
        """.trimIndent()
    }

    private fun buildDocumentQuestionPrompt(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>
    ): String {
        val joinedContext = contextSnippets
            .filter { it.isNotBlank() }
            .joinToString("\n\n") { snippet -> "[문서 발췌]\n$snippet" }

        return """
            당신은 채용 면접관입니다.
            아래 문서 발췌를 기반으로 지원자에게 물을 개인화 면접 질문을 생성하세요.
            질문은 반드시 면접관의 말투로 작성하세요.

            [문서 유형]
            $fileTypeLabel

            [난이도]
            ${difficulty?.name ?: "MIXED"}

            [문서 발췌]
            $joinedContext

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "면접 질문",
                  "questionType": "RESUME_EXPERIENCE | PORTFOLIO_PROJECT | INTRODUCE_MOTIVATION 등",
                  "referenceAnswer": "이 질문에 대한 이상적인 모범답안(면접 답변 형식, 4~8문장)",
                  "evidence": ["질문의 근거가 된 문서 포인트", "..."]
                }
              ]
            }

            규칙:
            - 총 ${questionCount}개 질문 생성
            - 질문은 구체적이어야 하며 문서의 내용과 직접 연결되어야 함
            - 단순 나열형 질문 대신 이유, 역할, 의사결정, 결과를 묻는 면접형 질문 우선
            - OCR 오류처럼 보이는 깨진 문자열, 무의미한 영문 대문자 나열, 문맥이 없는 잡음은 근거로 사용하지 말 것
            - 말이 안 되는 발췌는 건너뛰고, 의미가 분명한 다른 발췌를 선택할 것
            - 질문에 문서 발췌를 그대로 길게 인용하지 말고 자연스러운 면접 문장으로 바꿀 것
            - 반드시 JSON만 출력
        """.trimIndent()
    }

    private fun buildTechQuestionPrompt(
        categoryPath: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): String {
        val labels = parseCategoryLabels(categoryPath)
        val difficultyGuide = when (difficulty ?: QuestionDifficulty.MEDIUM) {
            QuestionDifficulty.EASY -> "기본 개념, 주요 구성요소, 실무에서 자주 맞닥뜨리는 입문 수준의 판단 포인트를 묻는다."
            QuestionDifficulty.MEDIUM -> "실무 경험이 있는 지원자 기준으로 설계 이유, 장애 대응, 운영 포인트, 트레이드오프를 묻는다."
            QuestionDifficulty.HARD -> "대규모 운영, 성능 병목, 장애 전파, 복구 전략, 기술 선택의 장단점과 의사결정 근거를 깊게 묻는다."
        }
        val skillHints = buildSkillKnowledgeHints(labels.skillLabel)
        return """
            당신은 기술면접 질문 출제관입니다.
            아래 직무와 기술을 기준으로 실전형 기술면접 질문과 모범답안을 생성하세요.

            [직무]
            ${labels.jobLabel}

            [기술]
            ${labels.skillLabel}

            [난이도]
            ${difficulty?.name ?: "MEDIUM"}

            [난이도 기준]
            $difficultyGuide

            [반영해야 할 기술 포인트]
            $skillHints

            출력 JSON 스키마:
            {
              "questions": [
                {
                  "questionText": "기술면접 질문",
                  "canonicalAnswer": "이 질문에 대한 이상적인 면접 모범답안(4~8문장)",
                  "tags": ["tag1", "tag2"]
                }
              ]
            }

            규칙:
            - 총 ${questionCount}개 질문 생성
            - 질문은 반드시 ${labels.skillLabel} 자체 또는 그 핵심 개념/구성요소/운영 이슈를 중심으로 만들어야 함
            - 질문은 개념/트러블슈팅/설계/운영 관점을 섞되, 같은 유형의 질문을 반복하지 말 것
            - 단순 정의 암기형 질문만 내지 말고, 실무 상황과 의사결정이 드러나는 질문을 우선할 것
            - 질문 문장에 raw category code, path, BACKEND SPRING 같은 기계적인 표현을 넣지 말 것
            - 자연스러운 한국어 면접 문장으로 작성할 것
            - 널리 알려진 실무 관행과 운영 포인트를 반영할 것
            - 너무 포괄적인 질문, 어느 기술에도 통할 법한 질문, 기술명이 빠진 질문은 금지
            - 모범답안은 실제 면접에서 답하는 문장으로 4~8문장 작성하고, 핵심 근거와 실무 포인트를 포함할 것
            - 반드시 JSON만 출력
        """.trimIndent()
    }

    private fun parseGeneratedDocumentQuestions(raw: String, fileTypeLabel: String): List<GeneratedDocumentQuestion> {
        val node = objectMapper.readTree(raw)
        return node["questions"]
            ?.takeIf { it.isArray }
            ?.mapIndexedNotNull { index, item ->
                val questionText = item.text("questionText").ifBlank { return@mapIndexedNotNull null }
                GeneratedDocumentQuestion(
                    questionNo = index + 1,
                    questionText = questionText,
                    questionType = item.text("questionType").ifBlank { toDocumentQuestionType(fileTypeLabel) },
                    referenceAnswer = item.text("referenceAnswer").ifBlank { null },
                    evidence = item["evidence"]
                        ?.takeIf { it.isArray }
                        ?.mapNotNull { evidenceItem -> evidenceItem.asText().trim().takeIf(String::isNotBlank) }
                        ?: emptyList()
                )
            }
            .orEmpty()
    }

    private fun parseGeneratedTechQuestions(raw: String): List<GeneratedTechQuestion> {
        val node = objectMapper.readTree(raw)
        return node["questions"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { item ->
                val questionText = item.text("questionText").ifBlank { return@mapNotNull null }
                GeneratedTechQuestion(
                    questionText = questionText,
                    canonicalAnswer = item.text("canonicalAnswer").ifBlank { null },
                    tags = item["tags"]
                        ?.takeIf { it.isArray }
                        ?.mapNotNull { tag -> tag.asText().trim().takeIf(String::isNotBlank) }
                        ?: emptyList()
                )
            }
            .orEmpty()
    }

    private fun validateGeneratedTechQuestions(
        generated: List<GeneratedTechQuestion>,
        labels: CategoryLabels,
        difficulty: QuestionDifficulty?
    ): List<GeneratedTechQuestion> {
        val seen = linkedSetOf<String>()
        return generated.mapNotNull { item ->
            val normalizedQuestion = item.questionText.replace(Regex("\\s+"), " ").trim()
            if (!isUsableTechQuestion(normalizedQuestion, labels)) return@mapNotNull null
            val fingerprint = normalizedQuestion
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "")
            if (!seen.add(fingerprint)) return@mapNotNull null

            val normalizedAnswer = item.canonicalAnswer
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !isGuideLikeModelAnswer(it) }
                ?: buildIdealModelAnswer(
                    questionText = normalizedQuestion,
                    difficulty = difficulty?.name,
                    categoryLabel = labels.skillLabel
                )

            item.copy(
                questionText = normalizedQuestion,
                canonicalAnswer = normalizedAnswer,
                tags = normalizeTechTags(item.tags, labels)
            )
        }
    }

    private fun backfillTechQuestions(
        validated: List<GeneratedTechQuestion>,
        labels: CategoryLabels,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<GeneratedTechQuestion> {
        if (validated.size >= questionCount) return validated.take(questionCount)
        val fallback = buildHeuristicTechQuestions("${labels.jobLabel}/${labels.skillLabel}", difficulty, questionCount * 2)
        val merged = buildList {
            addAll(validated)
            addAll(fallback)
        }
        val seen = linkedSetOf<String>()
        return merged.filter { candidate ->
            val fingerprint = candidate.questionText
                .lowercase()
                .replace(Regex("[^a-z0-9가-힣]+"), "")
            seen.add(fingerprint)
        }.take(questionCount)
    }

    private fun buildHeuristicDocumentQuestions(
        fileTypeLabel: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int,
        contextSnippets: List<String>
    ): List<GeneratedDocumentQuestion> {
        val evidencePool = contextSnippets
            .flatMap { snippet ->
                snippet.split("\n", ".", "?", "!")
                    .map { it.replace(Regex("\\s+"), " ").trim() }
            }
            .filter { it.length in 18..220 && isMeaningfulEvidence(it) }
            .distinct()
            .take(questionCount.coerceAtLeast(3) * 2)

        val questionType = toDocumentQuestionType(fileTypeLabel)

        val templates = when (questionType) {
            "RESUME_EXPERIENCE" -> listOf(
                "이 경험에서 맡은 역할과 실제로 기여한 부분을 구체적으로 설명해 주세요.",
                "이 활동을 통해 가장 크게 성장한 역량은 무엇이었나요?",
                "이력에 적은 성과를 만들기 위해 어떤 의사결정을 했는지 말씀해 주세요."
            )
            "PORTFOLIO_PROJECT" -> listOf(
                "이 프로젝트에서 본인이 담당한 역할과 핵심 기술 선택 이유를 설명해 주세요.",
                "구현 과정에서 가장 어려웠던 문제와 해결 방식을 구체적으로 말씀해 주세요.",
                "이 결과물을 다시 만든다면 어떤 부분을 개선할지 설명해 주세요."
            )
            else -> listOf(
                "자기소개서에서 강조한 강점을 실제 경험과 연결해서 설명해 주세요.",
                "이 내용을 바탕으로 지원 동기와 직무 적합성을 구체적으로 말씀해 주세요.",
                "이 경험이 현재 지원 직무와 어떻게 연결되는지 설명해 주세요."
            )
        }

        val safeEvidencePool = evidencePool.ifEmpty { listOf("") }

        return (0 until questionCount).map { index ->
            val evidence = safeEvidencePool[index % safeEvidencePool.size]
            val template = templates[index % templates.size]
            val questionText = if (evidence.isNotBlank()) {
                "$template\n특히 문서에 드러난 구체적인 사례와 의사결정을 중심으로 말씀해 주세요."
            } else {
                template
            }
            GeneratedDocumentQuestion(
                questionNo = index + 1,
                questionText = questionText,
                questionType = questionType,
                referenceAnswer = buildIdealModelAnswer(
                    questionText = questionText,
                    difficulty = difficulty?.name,
                    categoryLabel = fileTypeLabel
                ),
                evidence = listOfNotNull(evidence.takeIf { it.isNotBlank() })
            )
        }
    }

    private fun buildHeuristicTechQuestions(
        categoryPath: String,
        difficulty: QuestionDifficulty?,
        questionCount: Int
    ): List<GeneratedTechQuestion> {
        val labels = parseCategoryLabels(categoryPath)
        val skillLabel = labels.skillLabel
        val skillTopic = toSkillTopic(skillLabel)
        val difficultyName = difficulty?.name
        val scenarioTemplates = when (difficulty ?: QuestionDifficulty.MEDIUM) {
            QuestionDifficulty.EASY -> listOf(
                "${skillTopic}을 실제 프로젝트에 도입할 때 반드시 이해해야 하는 핵심 개념을 설명해 주세요.",
                "${skillTopic}을 처음 적용하는 팀원이 알아야 할 구성요소와 역할을 설명해 주세요.",
                "${skillTopic}을 사용할 때 자주 발생하는 기본 설정 실수와 예방 방법을 설명해 주세요.",
                "${skillTopic}이 다른 대안보다 적합했던 상황을 하나 가정하고 선택 이유를 설명해 주세요.",
                "${skillTopic}을 운영에 올리기 전 최소한 어떤 점검을 해야 하는지 설명해 주세요."
            )
            QuestionDifficulty.MEDIUM -> listOf(
                "${skillTopic}을 도입한 서비스에서 장애가 반복된다면 어떤 관점으로 원인을 좁혀갈지 설명해 주세요.",
                "${skillTopic} 기반 구조를 설계할 때 성능, 운영 편의성, 장애 대응 관점의 트레이드오프를 설명해 주세요.",
                "${skillTopic}을 실제 프로젝트에서 선택한 이유와 다른 대안 대비 장단점을 설명해 주세요.",
                "${skillTopic}을 운영 환경에 적용할 때 모니터링, 배포, 롤백 전략을 어떻게 가져갈지 설명해 주세요.",
                "${skillTopic}을 사용하는 팀에서 자주 겪는 병목이나 장애 상황과 해결 접근을 설명해 주세요."
            )
            QuestionDifficulty.HARD -> listOf(
                "${skillTopic}이 대규모 트래픽 환경에서 병목이 된다면 어떤 지표를 먼저 보고 어떻게 개선할지 설명해 주세요.",
                "${skillTopic}을 사용하는 구조에서 장애 전파를 줄이기 위한 설계 원칙과 운영 전략을 설명해 주세요.",
                "${skillTopic} 기반 시스템을 장기적으로 운영할 때 성능 최적화와 안정성 확보 사이에서 어떤 의사결정을 할지 설명해 주세요.",
                "${skillTopic} 관련 장애가 복합적으로 발생했을 때 원인 분리, 대응 우선순위, 사후 개선까지 어떤 흐름으로 처리할지 설명해 주세요.",
                "${skillTopic}을 다른 아키텍처 대안과 비교해 선택하거나 포기해야 하는 기준을 깊게 설명해 주세요."
            )
        }
        return scenarioTemplates.take(questionCount).map {
            GeneratedTechQuestion(
                questionText = it,
                canonicalAnswer = buildIdealModelAnswer(
                    questionText = it,
                    difficulty = difficultyName,
                    categoryLabel = skillLabel
                ),
                tags = listOf(skillLabel.uppercase().replace(Regex("[^A-Z0-9가-힣]+"), "_").trim('_'))
            )
        }
    }

    private fun buildSkillKnowledgeHints(skillLabel: String): String {
        val key = skillLabel.trim().lowercase()
        return when {
            "spring" in key -> "DI/IoC, 트랜잭션, JPA 연동, 예외 처리, 테스트 전략, 운영 환경 설정, 성능 병목, Bean 생명주기"
            "react" in key -> "상태 관리, 렌더링 최적화, 훅 사용 규칙, 비동기 데이터 흐름, 컴포넌트 분리 기준, 에러 처리"
            "docker" in key -> "이미지 경량화, 레이어 캐시, 컨테이너 라이프사이클, 네트워크/볼륨, 운영 배포 전략"
            "kubernetes" in key || "k8s" in key -> "Pod/Deployment, 오토스케일링, 서비스 디스커버리, 롤링 업데이트, 자원 제한, 장애 복구"
            "jpa" in key || "hibernate" in key -> "영속성 컨텍스트, N+1, fetch 전략, flush/dirty checking, 트랜잭션 경계"
            "rag" in key -> "검색 품질, chunking, embedding 전략, hallucination 방지, retrieval 평가, 비용 최적화"
            "cloud" in key -> "고가용성, 오토스케일링, 네트워크 격리, 모니터링, 비용 최적화, 장애 복구, IAM 보안"
            "ci/cd" in key || "github actions" in key -> "파이프라인 분리, 캐시, 시크릿 관리, 실패 복구, 배포 전략, 검증 단계"
            else -> "${skillLabel}의 핵심 개념, 실제 적용 시 주의점, 운영 중 자주 발생하는 문제, 다른 대안과의 비교 기준"
        }
    }

    private fun isUsableTechQuestion(questionText: String, labels: CategoryLabels): Boolean {
        if (questionText.length < 18) return false
        val lowered = questionText.lowercase()
        val bannedFragments = listOf(
            "backend spring",
            "frontend react",
            "/tech/",
            "raw category",
            "category path"
        )
        if (bannedFragments.any { lowered.contains(it) }) return false
        if (Regex("\\b(BACKEND|FRONTEND|SYSTEM_ARCH|EMBEDDED)\\b").containsMatchIn(questionText)) return false
        if (Regex("설명해 주세요\\.?$").find(questionText)?.range?.first == 0 && questionText.length < 24) return false

        val skillKeywords = buildSkillKeywords(labels.skillLabel)
        return skillKeywords.any { keyword -> keyword.isNotBlank() && lowered.contains(keyword) }
    }

    private fun buildSkillKeywords(skillLabel: String): Set<String> {
        val base = skillLabel.trim().lowercase()
        val keywords = linkedSetOf(base)
        when {
            "spring" in base -> keywords += listOf("spring", "spring boot", "bean", "transaction", "jpa")
            "react" in base -> keywords += listOf("react", "hook", "state", "component")
            "docker" in base -> keywords += listOf("docker", "image", "container")
            "kubernetes" in base || "k8s" in base -> keywords += listOf("kubernetes", "k8s", "pod", "deployment")
            "jpa" in base || "hibernate" in base -> keywords += listOf("jpa", "hibernate", "entity", "fetch")
            "rag" in base -> keywords += listOf("rag", "retrieval", "embedding", "chunk")
            "cloud" in base -> keywords += listOf("cloud", "autoscaling", "load balancer", "iam", "vpc")
        }
        return keywords
    }

    private fun isGuideLikeModelAnswer(answer: String): Boolean {
        val trimmed = answer.trim()
        return trimmed.startsWith("질문 의도") ||
            trimmed.startsWith("좋은 답변은") ||
            trimmed.startsWith("핵심 개념") ||
            trimmed.contains("답변해") ||
            trimmed.contains("설명해야")
    }

    private fun normalizeTechTags(tags: List<String>, labels: CategoryLabels): List<String> {
        val normalized = tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { raw ->
                raw.uppercase()
                    .replace(Regex("[^A-Z0-9가-힣]+"), "_")
                    .trim('_')
            }
            .filterNot { it in setOf("TECH", "BACKEND", "FRONTEND", "SYSTEM_ARCH", "EMBEDDED") }
            .distinct()
            .toMutableList()
        if (normalized.isEmpty()) {
            normalized += labels.skillLabel.uppercase().replace(Regex("[^A-Z0-9가-힣]+"), "_").trim('_')
        }
        return normalized
    }

    private fun isMeaningfulEvidence(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val uppercaseRatio = letters.count { it.isUpperCase() }.toDouble() / letters.length.toDouble()
        val tokenCount = text.split(" ").count { it.isNotBlank() }
        val suspiciousTokens = text.split(" ").count { token ->
            token.length >= 6 && token.count(Char::isUpperCase) >= 4
        }
        return tokenCount >= 4 && uppercaseRatio < 0.72 && suspiciousTokens <= 2
    }

    private fun parseCategoryLabels(categoryPath: String): CategoryLabels {
        val parts = categoryPath.split("/").filter { it.isNotBlank() }
        val jobCode = parts.getOrNull(parts.lastIndex - 1)?.uppercase()
        val skillCode = parts.lastOrNull().orEmpty().uppercase()
        return CategoryLabels(
            jobLabel = jobLabels[jobCode] ?: jobCode?.replace("_", " ") ?: "기술 직무",
            skillLabel = skillLabels[skillCode] ?: skillCode.replace("_", " ").trim().ifBlank { "기술" }
        )
    }

    private fun toSkillTopic(skillLabel: String): String {
        return when (skillLabel) {
            "클라우드" -> "클라우드 환경"
            "상태 관리" -> "프론트엔드 상태 관리"
            else -> skillLabel
        }
    }

    private fun toDocumentQuestionType(fileTypeLabel: String): String {
        return when (fileTypeLabel.trim().uppercase()) {
            "RESUME", "이력서" -> "RESUME_EXPERIENCE"
            "PORTFOLIO", "포트폴리오" -> "PORTFOLIO_PROJECT"
            "INTRODUCE", "자기소개서" -> "INTRODUCE_MOTIVATION"
            else -> "${fileTypeLabel.trim().uppercase()}_DOCUMENT"
        }
    }

    private fun parseEvaluationJson(raw: String): AiTurnEvaluation {
        val node = objectMapper.readTree(raw)
        val score = node.decimal("score").coerceIn(BigDecimal.ZERO, BigDecimal("100.00"))
        val feedback = node.text("feedback").ifBlank { "AI 피드백 생성에 실패했습니다." }
        val bestPractice = node.text("bestPractice").ifBlank { "질문 의도에 맞춰 핵심-근거-결론 구조로 답변하세요." }

        val rubricNode = node["rubric"]
        val rubric = linkedMapOf(
            "coverage" to rubricNode.decimal("coverage").coerceIn(BigDecimal.ZERO, BigDecimal("100.00")),
            "accuracy" to rubricNode.decimal("accuracy").coerceIn(BigDecimal.ZERO, BigDecimal("100.00")),
            "communication" to rubricNode.decimal("communication").coerceIn(BigDecimal.ZERO, BigDecimal("100.00"))
        )

        val evidence = node["evidence"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
            ?.take(6)
            ?: emptyList()

        return AiTurnEvaluation(
            score = score.setScale(2, RoundingMode.HALF_UP),
            feedback = feedback,
            bestPractice = bestPractice,
            rubricScoresJson = objectMapper.writeValueAsString(rubric),
            evidenceJson = objectMapper.writeValueAsString(evidence)
        )
    }

    private fun JsonNode?.decimal(field: String): BigDecimal {
        if (this == null || this.isMissingNode) return BigDecimal.ZERO
        val value = this[field] ?: return BigDecimal.ZERO
        return runCatching {
            when {
                value.isNumber -> value.decimalValue()
                else -> BigDecimal(value.asText().trim())
            }
        }.getOrDefault(BigDecimal.ZERO)
    }

    private fun JsonNode?.text(field: String): String {
        if (this == null || this.isMissingNode) return ""
        return this[field]?.asText()?.trim().orEmpty()
    }
}

data class AiTurnEvaluation(
    val score: BigDecimal,
    val feedback: String,
    val bestPractice: String,
    val rubricScoresJson: String,
    val evidenceJson: String,
    val model: String = "unknown",
    val modelVersion: String? = null
)

data class GeneratedDocumentQuestion(
    val questionNo: Int,
    val questionText: String,
    val questionType: String,
    val referenceAnswer: String?,
    val evidence: List<String>
)

data class GeneratedTechQuestion(
    val questionText: String,
    val canonicalAnswer: String? = null,
    val tags: List<String> = emptyList()
)

private data class CategoryLabels(
    val jobLabel: String,
    val skillLabel: String
)
