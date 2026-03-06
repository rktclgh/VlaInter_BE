package com.cw.vlainter.domain.interview.service

private val GUIDE_LIKE_PATTERNS = listOf(
    Regex("^정의[, ]"),
    Regex("^핵심"),
    Regex("^질문 의도"),
    Regex("^개념 설명"),
    Regex("^좋은 답변은"),
    Regex("^지원 .* 관점에서"),
    Regex("답변이 좋습니다\\.?$"),
    Regex("답변을 중심으로"),
    Regex("답변해\\s?주십시오"),
    Regex("설명해야 합니다\\.?$"),
    Regex("제시해야 합니다\\.?$"),
    Regex("드러나야 합니다\\.?$"),
    Regex("언급하는 것이 좋습니다\\.?$"),
    Regex("답변하세요\\.$"),
    Regex("구성해 보세요\\.$"),
    Regex("순서로 답변"),
    Regex("균형 있게 답변"),
    Regex("포함해 답할")
)

data class ResolvedAnswerContent(
    val modelAnswer: String?,
    val guideText: String?
)

fun isGuideLikeText(value: String?): Boolean {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return false
    return GUIDE_LIKE_PATTERNS.any { it.containsMatchIn(text) }
}

fun resolveAnswerContent(
    questionText: String,
    rawModelAnswer: String?,
    rawGuideText: String?,
    difficulty: String? = null,
    categoryLabel: String? = null
): ResolvedAnswerContent {
    val normalizedModel = rawModelAnswer?.trim().takeUnless { it.isNullOrBlank() }
    val normalizedGuide = rawGuideText?.trim().takeUnless { it.isNullOrBlank() }

    val guideFromModel = normalizedModel?.takeIf(::isGuideLikeText)
    val synthesizedModelAnswer = buildIdealModelAnswer(questionText, difficulty, categoryLabel)
    val modelAnswer = normalizedModel
        ?.takeUnless(::isGuideLikeText)
        ?.takeUnless { shouldReplaceModelAnswer(questionText, it, categoryLabel) }
        ?: synthesizedModelAnswer

    val guideText = normalizedGuide
        ?: guideFromModel
        ?: buildGuideText(questionText, difficulty)

    return ResolvedAnswerContent(
        modelAnswer = modelAnswer,
        guideText = guideText
    )
}

private fun shouldReplaceModelAnswer(
    questionText: String,
    rawModelAnswer: String,
    categoryLabel: String?
): Boolean {
    val questionIntent = detectQuestionIntent(questionText)
    val answerLower = rawModelAnswer.lowercase()
    val focusKeywords = buildFocusKeywords(normalizeFocus(categoryLabel, questionText))
    val questionKeywords = extractQuestionKeywords(questionText, focusKeywords)

    if (
        answerLower.contains("단순한 기능 요소가 아니라 시스템의 안정성과 생산성에 직접 영향을 주는 핵심 요소") ||
        answerLower.contains("어떤 문제를 해결하려고 등장했는지") ||
        answerLower.contains("대체 기술과의 트레이드오프까지 같이 판단")
    ) {
        return true
    }

    val hasFocus = focusKeywords.any { answerLower.contains(it) }
    if (!hasFocus) return true

    if (questionKeywords.isNotEmpty()) {
        val matchedKeywordCount = questionKeywords.count { answerLower.contains(it) }
        val requiredMatches = when {
            questionKeywords.size >= 5 -> 3
            questionKeywords.size >= 3 -> 2
            else -> 1
        }
        if (matchedKeywordCount < requiredMatches) return true
    }

    return when (questionIntent) {
        QuestionIntent.PERFORMANCE -> !containsAny(answerLower, listOf("지표", "응답", "처리량", "cpu", "메모리", "병목", "튜닝", "모니터링"))
        QuestionIntent.RESILIENCE -> !containsAny(answerLower, listOf("장애", "전파", "격리", "복구", "롤백", "타임아웃", "서킷", "헬스체크"))
        QuestionIntent.COMPARISON -> !containsAny(answerLower, listOf("비교", "대안", "선택", "포기", "트레이드오프", "장단점"))
        QuestionIntent.INCIDENT -> !containsAny(answerLower, listOf("원인", "우선순위", "사후", "재발", "복구", "대응"))
        QuestionIntent.ADOPTION -> !containsAny(answerLower, listOf("도입", "적용", "운영", "구성", "설정", "검증"))
        QuestionIntent.GENERAL -> false
    }
}

private fun buildGuideText(questionText: String, difficulty: String?): String {
    val base = when (difficulty?.uppercase()) {
        "HARD" -> "결론을 먼저 말하고, 선택 근거와 대안 비교, 운영 시 리스크까지 포함해 답변하세요."
        "EASY" -> "핵심 개념을 먼저 정의하고, 짧은 예시를 붙여 간결하게 답변하세요."
        else -> "핵심 개념, 실제 사례, 선택 이유 순서로 답변하면 전달력이 좋아집니다."
    }
    return if (questionText.contains("경험") || questionText.contains("프로젝트") || questionText.contains("역할")) {
        "$base 특히 본인이 맡은 역할, 판단 기준, 결과를 분리해 설명하세요."
    } else {
        "$base 마지막에는 장단점이나 트레이드오프를 한 문장으로 정리하세요."
    }
}

fun buildIdealModelAnswer(
    questionText: String,
    difficulty: String? = null,
    categoryLabel: String? = null
): String {
    val focus = normalizeFocus(categoryLabel, questionText)
    val levelHint = when (difficulty?.uppercase()) {
        "HARD" -> "운영 관점의 리스크와 대안 비교까지"
        "EASY" -> "핵심 개념과 대표 사례 중심으로"
        else -> "개념, 적용 이유, 실무 판단 기준을 균형 있게"
    }

    return if (questionText.contains("역할") || questionText.contains("프로젝트") || questionText.contains("경험")) {
        """
        저는 이 경험에서 제가 맡은 역할과 책임 범위를 먼저 명확히 정의하고, ${focus}와 관련된 문제를 어떤 기준으로 해결했는지를 중심으로 설명하겠습니다.
        당시에는 단순히 기능을 구현하는 것보다 서비스가 안정적으로 운영될 수 있는 구조를 만드는 것이 더 중요하다고 판단했고, 그래서 선택한 기술과 방식의 이유를 제약 조건과 함께 검토했습니다.
        구현 과정에서는 $levelHint 설명하려고 했고, 문제가 발생했을 때는 원인을 빠르게 좁히고 재발을 막을 수 있는 방향으로 구조를 다듬었습니다.
        결과적으로 성능이나 안정성, 운영 편의성 중 최소 한 가지 이상에서 분명한 개선을 만들었고, 같은 상황이 다시 온다면 초기 설계 단계에서 모니터링과 검증 포인트를 더 촘촘히 넣어 완성도를 높일 것입니다.
        """.trimIndent().replace("\n", " ")
    } else {
        buildTechnicalModelAnswer(questionText, focus, difficulty, levelHint)
    }
}

private fun buildTechnicalModelAnswer(
    questionText: String,
    focus: String,
    difficulty: String?,
    levelHint: String
): String {
    val normalized = questionText.replace(Regex("\\s+"), " ").trim()

    return when {
        normalized.contains("원인 분리") || normalized.contains("대응 우선순위") || normalized.contains("사후 개선") -> """
            ${focus} 관련 장애가 복합적으로 발생했다면 저는 먼저 사용자 영향도가 큰 기능부터 기준으로 우선순위를 정하고, 로그와 메트릭을 통해 장애 원인을 층위별로 분리합니다.
            예를 들어 애플리케이션 오류, 인프라 문제, 외부 의존성 장애를 섞어서 보지 않고 각각의 증거를 확보한 뒤 가장 영향도가 큰 문제부터 차례로 차단합니다.
            초기 대응이 끝난 뒤에는 임시 조치와 근본 원인 해결을 구분하고, 왜 탐지가 늦었는지, 어떤 보호 장치가 부족했는지까지 함께 점검해야 합니다.
            사후 개선 단계에서는 재현 테스트, 모니터링 보강, 운영 절차 수정, 배포 전략 개선까지 연결해야 같은 유형의 장애를 줄일 수 있습니다.
            저는 결국 장애 대응의 품질은 얼마나 빨리 복구했는지뿐 아니라, 그 경험을 구조적 개선으로 연결했는지에서 결정된다고 생각합니다.
        """.trimIndent().replace("\n", " ")

        normalized.contains("장애 전파") || (normalized.contains("설계 원칙") && normalized.contains("운영 전략")) -> """
            ${focus}을 사용하는 구조에서 장애 전파를 줄이려면 먼저 실패가 한 지점에 머물도록 경계를 명확히 나누는 설계가 필요합니다.
            저는 서비스 간 결합도를 낮추고, 타임아웃·재시도·서킷 브레이커 같은 보호 장치를 두어 한 컴포넌트의 문제가 전체 장애로 번지지 않게 설계하는 편입니다.
            운영 측면에서는 헬스체크, 점진 배포, 빠른 롤백, 경보 기준 정교화처럼 장애를 빨리 감지하고 안전하게 복구할 수 있는 절차를 함께 준비해야 합니다.
            또한 장애 대응 과정에서 어떤 기능을 우선 보호할지 비즈니스 우선순위를 정해 두어야 실제 상황에서 판단이 빨라집니다.
            결국 좋은 구조는 장애가 아예 없다는 뜻이 아니라, 장애가 나더라도 영향 범위를 제한하고 복구 시간을 짧게 만드는 구조라고 생각합니다.
        """.trimIndent().replace("\n", " ")

        normalized.contains("비교") || normalized.contains("선택 기준") || normalized.contains("포기") || normalized.contains("대안") || (normalized.contains("의사결정") && normalized.contains("사이")) -> """
            ${focus}을 선택하거나 포기할 때 저는 먼저 우리 팀의 문제를 이 기술이 실제로 해결하는지부터 확인합니다.
            단순히 많이 쓰이는 기술이라는 이유로 선택하기보다 개발 생산성, 운영 난이도, 학습 비용, 장애 대응 편의성, 기존 시스템과의 호환성을 함께 비교해야 합니다.
            예를 들어 단기적으로는 개발 속도가 빠르더라도 장기 운영 시 복잡도가 급격히 올라간다면 다른 대안을 택하는 것이 더 합리적일 수 있습니다.
            반대로 팀 역량과 서비스 특성상 ${focus}이 제공하는 장점이 분명하다면, 초기 도입 비용이 있더라도 선택할 가치가 있습니다.
            결국 기술 선택은 유행을 따르는 문제가 아니라 현재 제약 조건과 장기 운영 비용까지 고려한 의사결정이라고 생각합니다.
        """.trimIndent().replace("\n", " ")

        normalized.contains("병목") || normalized.contains("지표") || normalized.contains("성능") || normalized.contains("최적화") || normalized.contains("처리량") || normalized.contains("응답 시간") -> """
            ${focus}가 병목이 된다고 판단되면 저는 먼저 응답 시간, 처리량, 에러율, CPU·메모리 사용량처럼 병목 위치를 좁힐 수 있는 지표를 우선 확인합니다.
            그다음 애플리케이션 레벨 문제인지, DB·네트워크·외부 연동 문제인지 구간을 나눠 원인을 분리하고, 실제 트래픽 패턴과 함께 재현 가능한 증거를 확보합니다.
            개선 단계에서는 단순히 인스턴스를 늘리기보다 캐시 적용, 쿼리 최적화, 비동기 처리, 연결 풀 조정처럼 병목 원인에 맞는 처방을 먼저 검토합니다.
            특히 ${difficultySpecificTail(difficulty, "성능 개선 후에도 장애가 재발하지 않도록 모니터링 지표와 임계값, 롤백 기준까지 함께 설계하는 것이 중요하다고 생각합니다.")} 
            결국 핵심은 지표를 보고 추측하는 것이 아니라, 병목 구간을 정량적으로 확인한 뒤 가장 비용 효율적인 개선안을 선택하는 것입니다.
        """.trimIndent().replace("\n", " ")

        normalized.contains("도입") || normalized.contains("적용") || normalized.contains("사용할 때") -> """
            ${focus}을 도입할 때는 먼저 이 기술이 해결하려는 문제가 무엇인지와 현재 시스템의 제약 조건이 무엇인지를 함께 확인해야 합니다.
            저는 핵심 개념을 이해하는 것에 그치지 않고, 실제 적용 시 운영 복잡도, 팀 숙련도, 기존 구조와의 충돌 가능성까지 같이 검토하는 편입니다.
            또한 초기 도입 단계에서는 작은 범위에서 검증하고, 관측 지표와 롤백 계획을 먼저 마련한 뒤 점진적으로 적용해야 리스크를 줄일 수 있습니다.
            ${difficultySpecificTail(difficulty, "특히 난도가 높은 환경일수록 기능 장점보다 운영 비용과 장애 대응 난이도를 더 엄격하게 따져야 합니다.")}
            결국 기술 도입은 기능 비교만으로 끝나는 것이 아니라, 실제 운영 가능성과 지속 가능한 유지보수 구조까지 포함해 판단해야 한다고 생각합니다.
        """.trimIndent().replace("\n", " ")

        else -> """
            ${focus}는 단순한 기능 요소가 아니라 시스템의 안정성과 생산성에 직접 영향을 주는 핵심 요소라고 생각합니다.
            저는 먼저 이 기술이 어떤 문제를 해결하려고 등장했는지와 실제 현업에서 어떤 상황에 가장 적합한지를 함께 설명하는 편입니다.
            실제 적용에서는 구현 난이도만이 아니라 운영 복잡도, 장애 대응 방식, 대체 기술과의 트레이드오프까지 같이 판단해야 합니다.
            그래서 답변할 때도 정의만 말하는 것이 아니라 $levelHint 설명하고, 왜 그 선택이 실무적으로 합리적인지까지 연결해서 말씀드리는 것이 중요하다고 생각합니다.
        """.trimIndent().replace("\n", " ")
    }
}

private fun difficultySpecificTail(difficulty: String?, hardLine: String): String {
    return if (difficulty?.uppercase() == "HARD") hardLine else "개선 전후의 차이를 지표로 확인하고, 운영팀이 바로 대응할 수 있는 수준까지 정리하는 것이 중요합니다."
}

private fun detectQuestionIntent(questionText: String): QuestionIntent {
    val normalized = questionText.replace(Regex("\\s+"), " ").trim()
    return when {
        normalized.contains("원인 분리") || normalized.contains("대응 우선순위") || normalized.contains("사후 개선") -> QuestionIntent.INCIDENT
        normalized.contains("장애 전파") || (normalized.contains("설계 원칙") && normalized.contains("운영 전략")) -> QuestionIntent.RESILIENCE
        normalized.contains("비교") || normalized.contains("선택 기준") || normalized.contains("포기") || normalized.contains("대안") || (normalized.contains("의사결정") && normalized.contains("사이")) -> QuestionIntent.COMPARISON
        normalized.contains("병목") || normalized.contains("지표") || normalized.contains("성능") || normalized.contains("최적화") || normalized.contains("처리량") || normalized.contains("응답 시간") -> QuestionIntent.PERFORMANCE
        normalized.contains("도입") || normalized.contains("적용") || normalized.contains("사용할 때") || normalized.contains("운영") -> QuestionIntent.ADOPTION
        else -> QuestionIntent.GENERAL
    }
}

private fun buildFocusKeywords(focus: String): Set<String> {
    val lowered = focus.lowercase()
    val keywords = linkedSetOf(lowered)
    when {
        lowered.contains("spring") -> keywords += listOf("spring", "스프링", "spring boot", "bean", "트랜잭션")
        lowered.contains("react") -> keywords += listOf("react", "리액트", "hook", "상태")
        lowered.contains("cloud") || lowered.contains("클라우드") -> keywords += listOf("cloud", "클라우드", "cloudwatch", "로드밸런서", "오토스케일링")
        lowered.contains("docker") -> keywords += listOf("docker", "도커", "이미지", "컨테이너")
        lowered.contains("rag") -> keywords += listOf("rag", "retrieval", "embedding", "챗봇")
    }
    return keywords
}

private fun containsAny(source: String, keywords: List<String>): Boolean {
    return keywords.any { source.contains(it) }
}

private val QUESTION_STOPWORDS = setOf(
    "질문", "설명", "주세요", "설명해", "기준", "어떤", "먼저", "관련", "대해", "중심", "기반",
    "시스템", "환경", "구조", "상황", "경우", "장기적", "장기적으로", "사용", "운영", "실무",
    "기술", "문제", "핵심", "주제", "생각", "답변", "해주세요", "말씀", "해결", "하기", "할지"
)

private fun extractQuestionKeywords(questionText: String, focusKeywords: Set<String>): Set<String> {
    return questionText
        .lowercase()
        .replace(Regex("[^a-z0-9가-힣\\s]"), " ")
        .split(Regex("\\s+"))
        .mapNotNull(::normalizeQuestionKeyword)
        .filterNot { token ->
            token in QUESTION_STOPWORDS || focusKeywords.any { it.contains(token) || token.contains(it) }
        }
        .filter { it.length >= 2 }
        .toCollection(linkedSetOf())
}

private fun normalizeQuestionKeyword(token: String): String? {
    val cleaned = token.trim().trim { it <= ' ' }
    if (cleaned.isBlank()) return null
    return cleaned.replace(
        Regex("(으로|에서|에게|과|와|를|을|은|는|이|가|도|로|의|시|때|간|상|까지|부터|하고|하며|해서|적인|적으로|하다|해야|하는|된|되는|되면|할지|하기|하면|이다)$"),
        ""
    ).ifBlank { null }
}

private enum class QuestionIntent {
    PERFORMANCE,
    RESILIENCE,
    COMPARISON,
    INCIDENT,
    ADOPTION,
    GENERAL
}

private fun normalizeFocus(categoryLabel: String?, questionText: String): String {
    val raw = categoryLabel?.trim().orEmpty()
    if (raw.isBlank()) return extractFocus(questionText)
    if (raw.matches(Regex("^[A-Z_]+$"))) return extractFocus(questionText)
    return raw
}

private fun extractFocus(questionText: String): String {
    val normalized = questionText.replace(Regex("\\s+"), " ").trim()
    return listOf("Spring", "React", "Cloud", "Docker", "Kubernetes", "JPA", "RAG", "CI/CD", "MSA")
        .firstOrNull { normalized.contains(it, ignoreCase = true) }
        ?: "질문의 핵심 주제"
}
