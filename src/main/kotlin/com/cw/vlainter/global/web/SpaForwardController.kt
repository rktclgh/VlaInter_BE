package com.cw.vlainter.global.web

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * SPA 라우트 직접 접근 시 index.html로 포워딩한다.
 *
 * FE 라우트 추가 시 이 목록도 함께 갱신한다.
 */
@Controller
class SpaForwardController {
    @GetMapping("/")
    fun root(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        val isAuthenticated =
            authentication != null &&
                authentication.isAuthenticated &&
                authentication !is AnonymousAuthenticationToken

        return if (isAuthenticated) {
            "redirect:/content/interview"
        } else {
            "forward:/index.html"
        }
    }

    @GetMapping(
        "/login",
        "/join",
        "/auth/kakao/callback",
        "/content/interview",
        "/content/files",
        "/content/mypage",
        "/content/point-charge"
    )
    fun forwardToIndex(): String = "forward:/index.html"
}
