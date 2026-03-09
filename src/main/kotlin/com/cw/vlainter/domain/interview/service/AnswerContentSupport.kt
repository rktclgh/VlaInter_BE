package com.cw.vlainter.domain.interview.service

private val GUIDE_LIKE_PATTERNS = listOf(
    Regex("^질문 의도"),
    Regex("^좋은 답변은"),
    Regex("^핵심"),
    Regex("^가이드"),
    Regex("답변하세요\\.?$"),
    Regex("설명해\\s?주세요\\.?$")
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
    val modelAnswer = rawModelAnswer
        ?.trim()
        ?.takeIf { it.isNotBlank() && !isGuideLikeText(it) }
    val guideText = rawGuideText
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return ResolvedAnswerContent(
        modelAnswer = modelAnswer,
        guideText = guideText
    )
}
