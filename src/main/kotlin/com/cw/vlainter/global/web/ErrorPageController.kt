package com.cw.vlainter.global.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class ErrorPageController {
    @GetMapping("/errors/403")
    fun forbidden(): String = "forward:/index.html"

    @GetMapping("/errors/404")
    fun notFound(): String = "forward:/index.html"
}
