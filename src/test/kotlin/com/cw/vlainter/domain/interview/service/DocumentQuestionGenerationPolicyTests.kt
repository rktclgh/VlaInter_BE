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
}
