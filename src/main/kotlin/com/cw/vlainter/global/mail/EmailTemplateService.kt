package com.cw.vlainter.global.mail

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.nio.charset.StandardCharsets
import java.time.Year
import java.util.concurrent.ConcurrentHashMap

@Component
class EmailTemplateService {
    private val templateCache = ConcurrentHashMap<String, String>()

    fun buildVerificationCodeEmail(code: String, expiresInSeconds: Long): String {
        val model = mapOf(
            "mail_title" to "이메일 인증 코드",
            "service_name" to "VlaInter",
            "logo_src" to "cid:$LOGO_CONTENT_ID",
            "verification_code" to code,
            "expires_in_seconds" to expiresInSeconds.toString(),
            "contact_email" to CONTACT_EMAIL,
            "footer_year" to Year.now().value.toString(),
            "footer_description" to "AI 면접 트레이닝 플랫폼"
        )
        return VerificationCodeTemplate().render(model)
    }

    fun buildTemporaryPasswordEmail(temporaryPassword: String): String {
        val model = mapOf(
            "mail_title" to "임시 비밀번호",
            "service_name" to "VlaInter",
            "logo_src" to "cid:$LOGO_CONTENT_ID",
            "temporary_password" to temporaryPassword,
            "contact_email" to CONTACT_EMAIL,
            "footer_year" to Year.now().value.toString(),
            "footer_description" to "AI 면접 트레이닝 플랫폼"
        )
        return TemporaryPasswordTemplate().render(model)
    }

    fun buildWelcomeEmail(userName: String, signupChannel: String): String {
        val model = mapOf(
            "mail_title" to "회원가입을 환영합니다",
            "service_name" to "VlaInter",
            "logo_src" to "cid:$LOGO_CONTENT_ID",
            "user_name" to userName,
            "signup_channel" to signupChannel,
            "contact_email" to CONTACT_EMAIL,
            "footer_year" to Year.now().value.toString(),
            "footer_description" to "AI 면접 트레이닝 플랫폼"
        )
        return WelcomeTemplate().render(model)
    }

    fun buildAccountDeletionEmail(userName: String): String {
        val model = mapOf(
            "mail_title" to "회원 탈퇴가 완료되었습니다",
            "service_name" to "VlaInter",
            "logo_src" to "cid:$LOGO_CONTENT_ID",
            "user_name" to userName,
            "contact_email" to CONTACT_EMAIL,
            "footer_year" to Year.now().value.toString(),
            "footer_description" to "AI 면접 트레이닝 플랫폼"
        )
        return AccountDeletionTemplate().render(model)
    }

    private abstract inner class BaseTemplate {
        fun render(rawModel: Map<String, String>): String {
            val escapedModel = rawModel.mapValues { HtmlUtils.htmlEscape(it.value) }
            val content = applyModel(loadCached(contentPath()), escapedModel)
            var frame = applyModel(loadCached(framePath()), escapedModel)
            frame = frame.replace("{{content}}", content)
            return frame
        }

        protected open fun framePath(): String = "email/frame/default.html"
        protected abstract fun contentPath(): String

        private fun load(path: String): String {
            val resource = ClassPathResource(path)
            return resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }

        private fun loadCached(path: String): String {
            return templateCache.computeIfAbsent(path) { load(it) }
        }

        private fun applyModel(template: String, model: Map<String, String>): String {
            var result = template
            model.forEach { (key, value) ->
                result = result.replace("{{${key}}}", value)
            }
            return result
        }
    }

    private inner class VerificationCodeTemplate : BaseTemplate() {
        override fun contentPath(): String = "email/content/auth/verification-code.html"
    }

    private inner class TemporaryPasswordTemplate : BaseTemplate() {
        override fun contentPath(): String = "email/content/auth/temporary-password.html"
    }

    private inner class WelcomeTemplate : BaseTemplate() {
        override fun contentPath(): String = "email/content/auth/welcome.html"
    }

    private inner class AccountDeletionTemplate : BaseTemplate() {
        override fun contentPath(): String = "email/content/auth/account-deletion.html"
    }

    fun logoResource(): ClassPathResource = ClassPathResource("email/logo/favicon.png")

    fun logoContentId(): String = LOGO_CONTENT_ID

    companion object {
        private const val CONTACT_EMAIL = "info@vlainter.online"
        private const val LOGO_CONTENT_ID = "vlainter-logo"
    }
}
