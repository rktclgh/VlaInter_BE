package com.cw.vlainter.domain.user.controller

import com.cw.vlainter.domain.user.dto.AdminMemberDetailResponse
import com.cw.vlainter.domain.user.dto.AdminMemberAccessGlobalSummaryResponse
import com.cw.vlainter.domain.user.dto.AdminMemberListResponse
import com.cw.vlainter.domain.user.dto.UpdateMemberByAdminRequest
import com.cw.vlainter.domain.user.service.UserService
import com.cw.vlainter.global.security.AuthPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/members")
class AdminUserController(
    private val userService: UserService
) {
    @GetMapping
    fun getMembers(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "") keyword: String
    ): ResponseEntity<AdminMemberListResponse> {
        return ResponseEntity.ok(userService.getMembersByAdmin(principal, page, size, keyword))
    }

    @GetMapping("/access-summary")
    fun getGlobalAccessSummary(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(defaultValue = "7") windowDays: Int
    ): ResponseEntity<AdminMemberAccessGlobalSummaryResponse> {
        return ResponseEntity.ok(userService.getGlobalAccessSummaryByAdmin(principal, windowDays))
    }

    @PostMapping("/access-summary/refresh")
    fun refreshGlobalAccessSummary(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(defaultValue = "7") windowDays: Int
    ): ResponseEntity<AdminMemberAccessGlobalSummaryResponse> {
        return ResponseEntity.ok(userService.refreshGlobalAccessSummaryByAdmin(principal, windowDays))
    }

    @GetMapping("/{memberId}")
    fun getMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<AdminMemberDetailResponse> {
        return ResponseEntity.ok(userService.getMemberByAdmin(principal, memberId))
    }

    @PostMapping("/{memberId}/refresh-access")
    fun refreshMemberAccess(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<AdminMemberDetailResponse> {
        return ResponseEntity.ok(userService.refreshMemberAccessByAdmin(principal, memberId))
    }

    @PatchMapping("/{memberId}")
    fun updateMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long,
        @RequestBody request: UpdateMemberByAdminRequest
    ): ResponseEntity<AdminMemberDetailResponse> {
        return ResponseEntity.ok(userService.updateMemberByAdmin(principal, memberId, request))
    }

    @DeleteMapping("/{memberId}")
    fun deleteMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<Map<String, String>> {
        userService.hardDeleteMemberByAdmin(principal, memberId)
        return ResponseEntity.ok(mapOf("message" to "User has been permanently deleted."))
    }

    @PatchMapping("/{memberId}/deactivate")
    fun deactivateMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<Map<String, String>> {
        userService.blockMemberByAdmin(principal, memberId)
        return ResponseEntity.ok(mapOf("message" to "회원 계정이 비활성화되어 로그인이 차단되었습니다."))
    }

    @PatchMapping("/{memberId}/activate")
    fun activateMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<Map<String, String>> {
        userService.activateMemberByAdmin(principal, memberId)
        return ResponseEntity.ok(mapOf("message" to "회원 계정이 활성화되었습니다."))
    }

    @PatchMapping("/{memberId}/soft-delete")
    fun softDeleteMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<Map<String, String>> {
        userService.softDeleteMemberByAdmin(principal, memberId)
        return ResponseEntity.ok(mapOf("message" to "회원 계정이 소프트 삭제 처리되었습니다."))
    }

    @PatchMapping("/{memberId}/restore")
    fun restoreMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<Map<String, String>> {
        userService.restoreSoftDeletedMemberByAdmin(principal, memberId)
        return ResponseEntity.ok(mapOf("message" to "소프트 삭제된 회원 계정을 복구했습니다."))
    }

    @DeleteMapping("/{memberId}/hard")
    fun hardDeleteMember(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable memberId: Long
    ): ResponseEntity<Map<String, String>> {
        userService.hardDeleteMemberByAdmin(principal, memberId)
        return ResponseEntity.ok(mapOf("message" to "User has been permanently deleted."))
    }
}
