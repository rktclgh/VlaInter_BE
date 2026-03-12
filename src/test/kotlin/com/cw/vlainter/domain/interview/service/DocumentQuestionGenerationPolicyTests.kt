@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.interview.service

import com.cw.vlainter.domain.userFile.entity.FileType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentQuestionGenerationPolicyTests {

    @Test
    fun `자기소개서는 이력서보다 더 많은 질문 예산을 받는다`() {
        val allocation = DocumentQuestionGenerationPolicy.allocateQuestionCounts(
            total = 5,
            fileTypes = listOf(FileType.RESUME, FileType.INTRODUCE)
        )

        assertThat(allocation).containsExactly(2, 3)
    }

    @Test
    fun `자기소개서 snippet budget은 이력서보다 크다`() {
        val resumeBudget = DocumentQuestionGenerationPolicy.snippetBudget(FileType.RESUME, 2)
        val introduceBudget = DocumentQuestionGenerationPolicy.snippetBudget(FileType.INTRODUCE, 2)

        assertThat(introduceBudget).isGreaterThan(resumeBudget)
    }

    @Test
    fun `자기소개서의 포부 문장은 motivation kind로 분류된다`() {
        val classified = DocumentQuestionGenerationPolicy.classifySnippets(
            fileType = FileType.INTRODUCE,
            snippets = listOf("인턴으로서 후보자 경험을 더 좋게 만들고 싶고, 불필요한 업무를 줄이겠다는 마음가짐을 가지겠습니다.")
        )

        assertThat(classified.single().kind).isEqualTo(DocumentSnippetKind.MOTIVATION_OR_ASPIRATION)
    }

    @Test
    fun `포트폴리오의 성과 문장은 result kind로 분류된다`() {
        val classified = DocumentQuestionGenerationPolicy.classifySnippets(
            fileType = FileType.PORTFOLIO,
            snippets = listOf("API 응답 구조를 개선해 초기 로딩 시간을 35퍼센트 줄였고 관련 문의도 감소했습니다.")
        )

        assertThat(classified.single().kind).isEqualTo(DocumentSnippetKind.PROJECT_OR_RESULT)
    }

    @Test
    fun `영문 systems나 teams는 result kind로 오분류되지 않는다`() {
        val classified = DocumentQuestionGenerationPolicy.classifySnippets(
            fileType = FileType.PORTFOLIO,
            snippets = listOf("Designed backend systems and collaborated with product teams to stabilize deployments.")
        )

        assertThat(classified.single().kind).isNotEqualTo(DocumentSnippetKind.PROJECT_OR_RESULT)
    }

    @Test
    fun `이력서 snippet 우선순위는 과목표보다 경력과 수상 경험을 앞세운다`() {
        val prioritized = DocumentQuestionGenerationPolicy.prioritizeSnippets(
            fileType = FileType.RESUME,
            snippets = listOf(
                "학 사 컴퓨터공학 2023 1 전공 데이터베이스 3 A+ 운영체제 3 A 과목명 취득 학점 성적",
                "마음AI에서 SW 개발 인턴으로 음성봇 고도화 작업과 백엔드 개발을 담당했습니다.",
                "AWS 루키 챔피언십에서 Slack 알림 봇을 주제로 OCR 분석과 번역 기능을 접목한 서비스를 제작해 수상했습니다."
            )
        )

        assertThat(prioritized.first()).contains("마음AI")
        assertThat(prioritized[1]).contains("AWS 루키 챔피언십")
        assertThat(prioritized.last()).contains("과목명")
    }
}
