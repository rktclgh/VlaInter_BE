package com.cw.vlainter.domain.support.controller

import com.cw.vlainter.domain.support.dto.SupportReportResponse
import com.cw.vlainter.domain.support.service.SupportReportService
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/support")
class SupportController(
    private val supportReportService: SupportReportService
) {
    @PostMapping("/reports", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun sendReport(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) message: String?,
        @RequestParam(required = false) currentPath: String?,
        @RequestParam(required = false) userAgent: String?,
        @RequestParam(required = false) screenshot: MultipartFile?
    ): ResponseEntity<SupportReportResponse> {
        return ResponseEntity.ok(
            supportReportService.sendReport(
                principal = principal,
                category = category,
                title = title,
                message = message,
                currentPath = currentPath,
                userAgent = userAgent,
                screenshot = screenshot
            )
        )
    }
}
