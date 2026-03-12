package com.cw.vlainter.domain.support.service

import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.repository.UserRepository
import com.cw.vlainter.global.security.AuthPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class SupportReportServiceTests {
    private val userRepository: UserRepository = mock()
    private val supportWebhookNotifier = FakeSupportWebhookNotifier()

    @Test
    fun sendReportSendsBugReportToDiscordWebhook() {
        val principal = AuthPrincipal(userId = 1L, email = "user@vlainter.com", sessionId = "session-1")
        given(userRepository.findById(1L)).willReturn(Optional.of(createUser()))
        supportWebhookNotifier.shouldSucceed = true

        val response = service().sendReport(
            principal = principal,
            category = "BUG_REPORT",
            title = "모의면접 시작 버튼이 동작하지 않습니다",
            message = "실전 모의면접 세팅 후 시작 버튼을 눌렀는데 반응이 없습니다.",
            currentPath = "/content/interview",
            userAgent = "Mozilla/5.0",
            screenshot = MockMultipartFile("screenshot", "bug.png", "image/png", "png".toByteArray())
        )

        assertThat(response.message).isEqualTo("운영자 디스코드로 전송했습니다.")
        assertThat(response.recipientCount).isEqualTo(1)
        assertThat(supportWebhookNotifier.lastPayload?.category).isEqualTo("BUG_REPORT")
        assertThat(supportWebhookNotifier.lastPayload?.title).isEqualTo("모의면접 시작 버튼이 동작하지 않습니다")
        assertThat(supportWebhookNotifier.lastPayload?.reporterId).isEqualTo(1L)
        assertThat(supportWebhookNotifier.lastPayload?.reporterEmail).isEqualTo("user@vlainter.com")
        assertThat(supportWebhookNotifier.lastPayload?.screenshot?.originalFilename).isEqualTo("bug.png")
    }

    @Test
    fun sendReportSkipsUnsupportedAttachmentButStillSends() {
        val principal = AuthPrincipal(userId = 1L, email = "user@vlainter.com", sessionId = "session-1")
        given(userRepository.findById(1L)).willReturn(Optional.of(createUser()))
        supportWebhookNotifier.shouldSucceed = true

        service().sendReport(
            principal = principal,
            category = "MESSAGE",
            title = "건의사항",
            message = "질문 저장 UX를 조금 더 개선해 주세요.",
            currentPath = "/content/question-sets",
            userAgent = "Mozilla/5.0",
            screenshot = MockMultipartFile("screenshot", "bug.gif", "image/gif", "gif".toByteArray())
        )

        assertThat(supportWebhookNotifier.lastPayload?.category).isEqualTo("MESSAGE")
        assertThat(supportWebhookNotifier.lastPayload?.screenshot).isNull()
        assertThat(supportWebhookNotifier.lastPayload?.attachmentNotice).isEqualTo("첨부 파일은 PNG/JPEG/WEBP 이미지만 전송됩니다.")
    }

    @Test
    fun sendReportThrowsServiceUnavailableWhenWebhookFails() {
        val principal = AuthPrincipal(userId = 1L, email = "user@vlainter.com", sessionId = "session-1")
        given(userRepository.findById(1L)).willReturn(Optional.of(createUser()))
        supportWebhookNotifier.shouldSucceed = false

        val exception = assertThrows<ResponseStatusException> {
            service().sendReport(
                principal = principal,
                category = "BUG_REPORT",
                title = "오류",
                message = "전송 실패 테스트",
                currentPath = "/content/interview",
                userAgent = "Mozilla/5.0",
                screenshot = null
            )
        }

        assertThat(exception.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(exception.reason).isEqualTo("디스코드 전송에 실패했습니다. 잠시 후 다시 시도해 주세요.")
    }

    private fun service(): SupportReportService {
        return SupportReportService(
            userRepository = userRepository,
            supportWebhookNotifier = supportWebhookNotifier
        )
    }

    private fun createUser(): User {
        return User(
            id = 1L,
            email = "user@vlainter.com",
            password = "encoded-password",
            name = "Tester"
        )
    }

    private class FakeSupportWebhookNotifier : SupportWebhookNotifier {
        var shouldSucceed: Boolean = true
        var lastPayload: SupportWebhookPayload? = null

        override fun send(payload: SupportWebhookPayload): Boolean {
            lastPayload = payload
            return shouldSucceed
        }
    }
}
