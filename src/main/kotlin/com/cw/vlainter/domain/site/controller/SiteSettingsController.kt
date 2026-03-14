package com.cw.vlainter.domain.site.controller

import com.cw.vlainter.domain.site.dto.PublicSiteSettingsResponse
import com.cw.vlainter.domain.site.service.SiteSettingsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/site/settings")
class SiteSettingsController(
    private val siteSettingsService: SiteSettingsService
) {
    @GetMapping
    fun getPublicSettings(): ResponseEntity<PublicSiteSettingsResponse> {
        return ResponseEntity.ok(siteSettingsService.getPublicSettings())
    }
}
