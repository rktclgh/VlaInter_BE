package com.cw.vlainter.domain.site.controller

import com.cw.vlainter.domain.site.dto.AdminSiteSettingsResponse
import com.cw.vlainter.domain.site.dto.UpdateAdminSiteSettingsRequest
import com.cw.vlainter.domain.site.service.SiteSettingsService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/site/settings")
class AdminSiteSettingsController(
    private val siteSettingsService: SiteSettingsService
) {
    @GetMapping
    fun getAdminSettings(
        @AuthenticationPrincipal principal: AuthPrincipal
    ): ResponseEntity<AdminSiteSettingsResponse> {
        return ResponseEntity.ok(siteSettingsService.getAdminSettings(principal))
    }

    @PatchMapping
    fun updateAdminSettings(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: UpdateAdminSiteSettingsRequest
    ): ResponseEntity<AdminSiteSettingsResponse> {
        return ResponseEntity.ok(siteSettingsService.updateAdminSettings(principal, request))
    }
}
