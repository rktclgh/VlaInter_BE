package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.interview.entity.InterviewLanguage
import com.cw.vlainter.domain.interview.entity.InterviewQuestionKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class StoredLocalizedTurnContent(
    val questionText: String?,
    val modelAnswer: String?,
    val evidence: List<String> = emptyList()
)

data class TurnRagContext(
    val evidence: List<String> = emptyList(),
    val localizedLanguage: String? = null,
    val localizedQuestionText: String? = null,
    val localizedModelAnswer: String? = null,
    val localizedEvidence: List<String> = emptyList()
) {
    fun localizedQuestionTextFor(language: InterviewLanguage): String? {
        return if (localizedLanguage == language.name) localizedQuestionText?.takeIf { it.isNotBlank() } else null
    }

    fun localizedModelAnswerFor(language: InterviewLanguage): String? {
        return if (localizedLanguage == language.name) localizedModelAnswer?.takeIf { it.isNotBlank() } else null
    }

    fun localizedEvidenceFor(language: InterviewLanguage): List<String> {
        return if (localizedLanguage == language.name) localizedEvidence.filter { it.isNotBlank() } else emptyList()
    }
}

fun parseTurnRagContext(objectMapper: ObjectMapper, raw: String?): TurnRagContext {
    if (raw.isNullOrBlank()) return TurnRagContext()
    val root = runCatching { objectMapper.readTree(raw) }.getOrNull() ?: return TurnRagContext()
    return when {
        root.isArray -> TurnRagContext(evidence = root.stringArray())
        root.isObject -> {
            val localized = root.path("localized")
            TurnRagContext(
                evidence = root.path("evidence").stringArray(),
                localizedLanguage = localized.path("language").asText().trim().ifBlank { null },
                localizedQuestionText = localized.path("questionText").asText().trim().ifBlank { null },
                localizedModelAnswer = localized.path("modelAnswer").asText().trim().ifBlank { null },
                localizedEvidence = localized.path("evidence").stringArray()
            )
        }
        else -> TurnRagContext()
    }
}

fun buildTurnRagContextJson(
    objectMapper: ObjectMapper,
    evidence: List<String>,
    language: InterviewLanguage,
    localized: StoredLocalizedTurnContent?
): String {
    val payload = linkedMapOf<String, Any?>(
        "evidence" to evidence
    )
    val localizedBlock = if (language == InterviewLanguage.EN && localized != null) {
        mapOf(
            "language" to language.name,
            "questionText" to localized.questionText,
            "modelAnswer" to localized.modelAnswer,
            "evidence" to localized.evidence
        )
    } else {
        null
    }
    payload["localized"] = localizedBlock
    return objectMapper.writeValueAsString(payload)
}

fun buildSessionLocalizedQueueEntries(
    kind: InterviewQuestionKind,
    entries: Map<Long, StoredLocalizedTurnContent>
): List<Map<String, Any?>> {
    return entries.map { (id, localized) ->
        mapOf(
            "kind" to kind.name,
            "id" to id,
            "questionText" to localized.questionText,
            "modelAnswer" to localized.modelAnswer,
            "evidence" to localized.evidence
        )
    }
}

fun findSessionLocalizedQueueContent(
    objectMapper: ObjectMapper,
    configJson: String?,
    language: InterviewLanguage,
    kind: InterviewQuestionKind,
    id: Long
): StoredLocalizedTurnContent? {
    if (language != InterviewLanguage.EN || configJson.isNullOrBlank()) return null
    val root = runCatching { objectMapper.readTree(configJson) }.getOrNull() ?: return null
    val localizedQueue = root.path("meta").path("localizedQueue")
    if (!localizedQueue.isArray) return null
    val matched = localizedQueue.firstOrNull { item ->
        item.path("kind").asText().trim().uppercase() == kind.name &&
            item.path("id").asLong() == id
    } ?: return null
    return StoredLocalizedTurnContent(
        questionText = matched.path("questionText").asText().trim().ifBlank { null },
        modelAnswer = matched.path("modelAnswer").asText().trim().ifBlank { null },
        evidence = matched.path("evidence").stringArray()
    )
}

private fun JsonNode.stringArray(): List<String> {
    return takeIf { isArray }
        ?.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
        .orEmpty()
}
