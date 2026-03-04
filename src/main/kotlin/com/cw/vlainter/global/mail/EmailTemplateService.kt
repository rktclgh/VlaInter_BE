package com.cw.vlainter.global.mail

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.nio.charset.StandardCharsets
import java.time.Year

@Component
class EmailTemplateService {
    fun buildVerificationCodeEmail(code: String, expiresInSeconds: Long): String {
        val model = mapOf(
            "mail_title" to "이메일 인증 코드",
            "service_name" to "VlaInter",
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
            "temporary_password" to temporaryPassword,
            "contact_email" to CONTACT_EMAIL,
            "footer_year" to Year.now().value.toString(),
            "footer_description" to "AI 면접 트레이닝 플랫폼"
        )
        return TemporaryPasswordTemplate().render(model)
    }

    private abstract inner class BaseTemplate {
        fun render(rawModel: Map<String, String>): String {
            val escapedModel = rawModel.mapValues { HtmlUtils.htmlEscape(it.value) }
            val content = applyModel(load(contentPath()), escapedModel)
            var frame = applyModel(load(framePath()), escapedModel)
            frame = frame.replace("{{content}}", content)
            return frame
        }

        protected open fun framePath(): String = "email/frame/default.html"
        protected abstract fun contentPath(): String

        private fun load(path: String): String {
            val resource = ClassPathResource(path)
            return resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
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

    companion object {
        private const val CONTACT_EMAIL = "info@vlainter.online"
    }
}
