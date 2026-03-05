package com.cw.vlainter.global.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseStatus

@Controller
class ErrorPageController {
    @GetMapping("/errors/403")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun forbidden(): String = "forward:/error/403.html"

    @GetMapping("/errors/404")
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "forward:/error/404.html"
}
