package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.userFile.entity.FileType
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal enum class DocumentSnippetKind {
    ACTUAL_EXPERIENCE,
    PROJECT_OR_RESULT,
    MOTIVATION_OR_ASPIRATION,
    VALUE_OR_ATTITUDE
}

internal data class ClassifiedPromptSnippet(
    val text: String,
    val kind: DocumentSnippetKind
) {
    fun toPromptBlock(index: Int): String = """
        [문서 발췌 $index]
        kind=${kind.name}
        text=$text
    """.trimIndent()
}

internal object DocumentQuestionGenerationPolicy {
    private val resumeRoleSignals = listOf(
        "경력", "직무관련 경력", "인턴", "프로젝트", "연구실", "학부연구생", "대외 활동",
        "수상", "해커톤", "백엔드", "개발", "구현", "설계", "개선", "문제", "해결",
        "성과", "결과", "지원한 이유", "지원동기", "입사 후", "성장과정", "역경", "기술적 도전",
        "intern", "project", "award", "hackathon", "backend", "implemented", "designed",
        "improved", "result", "motivation", "growth", "challenge"
    )
    private val resumeNoiseSignals = listOf(
        "지원자", "휴대폰 번호", "전화 번호", "희망근무지역", "주소", "영문", "인적사항",
        "학점", "평점", "학번", "취득 학점", "과목명", "성적", "수강연도", "학기", "교양", "전공",
        "phone", "address", "gpa", "grade", "semester", "course"
    )
    private val introduceAspirationSignals = listOf(
        "지원동기", "포부", "가치관", "계획", "성장", "motivation", "value", "plan"
    )
    private val aspirationMarkers = listOf(
        "싶", "하고자", "하겠습니다", "가지겠습니다", "희망", "포부", "지원 동기", "지원동기",
        "기여", "성장", "배우", "목표", "관심", "꿈", "되고자", "되겠습니다",
        "want to", "hope to", "aspire", "would like to", "aim to", "looking forward"
    )
    private val valueMarkers = listOf(
        "가치", "가치관", "원칙", "태도", "관점", "생각", "중요", "신념", "마음가짐", "철학",
        "value", "principle", "attitude", "belief", "perspective", "mindset", "important"
    )
    private val experienceMarkers = listOf(
        "인턴", "프로젝트", "실습", "근무", "경험", "개발", "운영", "담당", "수행", "개선",
        "구현", "설계", "주도", "참여", "분석", "해결", "협업", "작성", "테스트", "관리",
        "intern", "project", "worked", "built", "implemented", "designed", "led", "owned",
        "improved", "managed", "analyzed", "solved", "delivered", "launched"
    )
    private val resultMarkers = listOf(
        "성과", "결과", "향상", "개선", "증가", "감소", "달성", "절감", "완료", "출시", "리스크 관리",
        "%", "배", "명", "건", "초", "퍼센트",
        "result", "outcome", "impact", "reduced", "increased", "improved", "decreased",
        "faster", "latency", "throughput", "conversion", "retention", "%"
    )
    private val completedExperienceMarkers = listOf(
        "했습니다", "하였다", "해냈", "맡아", "주도해", "개선해", "구현해", "설계해", "운영해",
        "worked on", "was responsible for", "took ownership", "handled", "delivered"
    )

    fun allocateQuestionCounts(total: Int, fileTypes: List<FileType>): List<Int> {
        if (total <= 0 || fileTypes.isEmpty()) return List(fileTypes.size) { 0 }

        val weights = fileTypes.map(::questionWeight)
        val totalWeight = weights.sum()
        val counts = MutableList(fileTypes.size) { 0 }
        val remainders = mutableListOf<Pair<Int, Double>>()
        var assigned = 0

        fileTypes.indices.forEach { index ->
            val exact = total * (weights[index] / totalWeight)
            val base = floor(exact).toInt()
            counts[index] = base
            assigned += base
            remainders += index to (exact - base)
        }

        remainders
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .take(total - assigned)
            .forEach { (index, _) ->
                counts[index] = counts[index] + 1
            }

        return counts
    }

    fun snippetBudget(fileType: FileType, questionCount: Int): Int = when (fileType) {
        FileType.INTRODUCE -> max(4, min(questionCount + 3, 8))
        FileType.PORTFOLIO -> max(3, min(questionCount + 2, 6))
        FileType.RESUME -> max(3, min(questionCount + 1, 5))
        FileType.PROFILE_IMAGE -> max(2, min(questionCount + 1, 4))
    }

    fun retrievalQueryLimit(fileType: FileType, questionCount: Int): Int = when (fileType) {
        FileType.INTRODUCE -> max(3, min(questionCount + 1, 5))
        FileType.PORTFOLIO -> max(3, min(questionCount + 1, 4))
        FileType.RESUME -> max(2, min(questionCount, 3))
        FileType.PROFILE_IMAGE -> 2
    }

    fun prioritizeSnippets(fileType: FileType, snippets: List<String>): List<String> {
        return snippets.sortedByDescending { promptSnippetScore(fileType, it) }
    }

    fun classifySnippets(fileType: FileType, snippets: List<String>): List<ClassifiedPromptSnippet> {
        return snippets.map { snippet ->
            ClassifiedPromptSnippet(
                text = snippet,
                kind = classifySnippet(fileType, snippet)
            )
        }
    }

    private fun classifySnippet(fileType: FileType, snippet: String): DocumentSnippetKind {
        val normalized = snippet.lowercase()
        val aspirationScore = countMarkerHits(normalized, aspirationMarkers)
        val valueScore = countMarkerHits(normalized, valueMarkers)
        val experienceScore = countMarkerHits(normalized, experienceMarkers)
        val resultScore = countMarkerHits(normalized, resultMarkers)
        val completedScore = countMarkerHits(normalized, completedExperienceMarkers)

        if (fileType == FileType.INTRODUCE) {
            if (resultScore >= 1 || completedScore >= 1 || (experienceScore >= 2 && aspirationScore == 0)) {
                return if (resultScore >= 1) {
                    DocumentSnippetKind.PROJECT_OR_RESULT
                } else {
                    DocumentSnippetKind.ACTUAL_EXPERIENCE
                }
            }
            if (aspirationScore >= 1 && aspirationScore >= experienceScore) {
                return DocumentSnippetKind.MOTIVATION_OR_ASPIRATION
            }
            if (valueScore >= 1) {
                return DocumentSnippetKind.VALUE_OR_ATTITUDE
            }
            return if (experienceScore >= 1) {
                DocumentSnippetKind.ACTUAL_EXPERIENCE
            } else {
                DocumentSnippetKind.MOTIVATION_OR_ASPIRATION
            }
        }

        if (resultScore >= 1) return DocumentSnippetKind.PROJECT_OR_RESULT
        if (experienceScore >= 1 || completedScore >= 1) return DocumentSnippetKind.ACTUAL_EXPERIENCE
        if (aspirationScore >= 1) return DocumentSnippetKind.MOTIVATION_OR_ASPIRATION
        if (valueScore >= 1) return DocumentSnippetKind.VALUE_OR_ATTITUDE

        return if (fileType == FileType.PROFILE_IMAGE) {
            DocumentSnippetKind.VALUE_OR_ATTITUDE
        } else {
            DocumentSnippetKind.ACTUAL_EXPERIENCE
        }
    }

    private fun questionWeight(fileType: FileType): Double = when (fileType) {
        FileType.INTRODUCE -> 1.6
        FileType.PORTFOLIO -> 1.3
        FileType.RESUME -> 1.0
        FileType.PROFILE_IMAGE -> 0.5
    }

    private fun promptSnippetScore(fileType: FileType, snippet: String): Int {
        val lowered = snippet.lowercase()
        return when (fileType) {
            FileType.RESUME -> {
                val signalHits = resumeRoleSignals.count { lowered.contains(it) } * 18
                val noiseHits = resumeNoiseSignals.count { lowered.contains(it) } * 16
                val numberBonus = if (Regex("""\d+[%건명배회]""").containsMatchIn(snippet)) 12 else 0
                val lengthBonus = min(20, snippet.length / 40)
                signalHits + numberBonus + lengthBonus - noiseHits
            }
            FileType.INTRODUCE -> {
                val signalHits = resumeRoleSignals.count { lowered.contains(it) } * 10
                val aspirationHits = introduceAspirationSignals.count { lowered.contains(it) } * 14
                signalHits + aspirationHits + min(16, snippet.length / 50)
            }
            FileType.PORTFOLIO -> {
                val signalHits = resumeRoleSignals.count { lowered.contains(it) } * 15
                val numberBonus = if (Regex("""\d+[%건명배회]""").containsMatchIn(snippet)) 10 else 0
                signalHits + numberBonus + min(18, snippet.length / 45)
            }
            FileType.PROFILE_IMAGE -> snippet.length / 10
        }
    }

    private fun countMarkerHits(text: String, markers: List<String>): Int {
        return markers.count { marker -> text.contains(marker.lowercase()) }
    }
}
