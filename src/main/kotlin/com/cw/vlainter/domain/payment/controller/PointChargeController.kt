package com.cw.vlainter.domain.payment.controller

import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeRequest
import com.cw.vlainter.domain.payment.dto.ConfirmPointChargeResponse
import com.cw.vlainter.domain.payment.dto.PointLedgerHistoryResponse
import com.cw.vlainter.domain.payment.dto.PointPaymentHistoryResponse
import com.cw.vlainter.domain.payment.dto.PointChargeProductResponse
import com.cw.vlainter.domain.payment.dto.PortoneWebhookRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeRequest
import com.cw.vlainter.domain.payment.dto.PreparePointChargeResponse
import com.cw.vlainter.domain.payment.dto.RefundPointChargeResponse
import com.cw.vlainter.domain.payment.service.PointChargeService
import com.cw.vlainter.global.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PointChargeController(
    private val pointChargeService: PointChargeService
) {
    @GetMapping("/points/products")
    fun getPointChargeProducts(): ResponseEntity<List<PointChargeProductResponse>> {
        return ResponseEntity.ok(pointChargeService.getPointChargeProducts())
    }

    @PostMapping("/points/prepare")
    fun preparePointCharge(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: PreparePointChargeRequest
    ): ResponseEntity<PreparePointChargeResponse> {
        return ResponseEntity.ok(pointChargeService.prepareCharge(principal, request))
    }

    @PostMapping("/points/confirm")
    fun confirmPointCharge(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: ConfirmPointChargeRequest
    ): ResponseEntity<ConfirmPointChargeResponse> {
        return ResponseEntity.ok(pointChargeService.confirmCharge(principal, request))
    }

    @GetMapping("/points/history")
    fun getPointPaymentHistory(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): ResponseEntity<PointPaymentHistoryResponse> {
        return ResponseEntity.ok(pointChargeService.getPaymentHistory(principal, page, size))
    }

    @GetMapping("/points/ledger")
    fun getPointLedgerHistory(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): ResponseEntity<PointLedgerHistoryResponse> {
        return ResponseEntity.ok(pointChargeService.getPointLedgerHistory(principal, page, size))
    }

    @PostMapping("/points/{chargeId}/refund")
    fun refundPointCharge(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable chargeId: Long
    ): ResponseEntity<RefundPointChargeResponse> {
        return ResponseEntity.ok(pointChargeService.refundCharge(principal, chargeId))
    }

    @PostMapping("/portone/webhook", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun handlePortoneWebhookJson(
        @RequestBody(required = false) request: PortoneWebhookRequest?
    ): ResponseEntity<Map<String, String>> {
        val safeRequest = request ?: PortoneWebhookRequest()
        return ResponseEntity.ok(pointChargeService.handleWebhook(safeRequest))
    }

    @PostMapping("/portone/webhook", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun handlePortoneWebhookForm(
        @RequestParam(name = "imp_uid", required = false) impUid: String?,
        @RequestParam(name = "merchant_uid", required = false) merchantUid: String?,
        @RequestParam(name = "status", required = false) status: String?
    ): ResponseEntity<Map<String, String>> {
        val request = PortoneWebhookRequest(
            impUid = impUid,
            merchantUid = merchantUid,
            status = status
        )
        return ResponseEntity.ok(pointChargeService.handleWebhook(request))
    }

    @GetMapping("/portone/callback", produces = [MediaType.TEXT_HTML_VALUE])
    fun callbackPage(): ResponseEntity<String> {
        val html = """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>결제 처리 중</title>
            </head>
            <body>
            <script>
              (function () {
                var params = new URLSearchParams(window.location.search);
                var payload = {
                  impUid: params.get("imp_uid") || "",
                  merchantUid: params.get("merchant_uid") || "",
                  status: params.get("imp_success") || ""
                };
                if (window.opener && !window.opener.closed) {
                  window.opener.postMessage({ type: "PORTONE_PAYMENT_CALLBACK", payload: payload }, "*");
                  window.close();
                  return;
                }
                window.location.replace("/content/point-charge" + window.location.search);
              })();
            </script>
            </body>
            </html>
        """.trimIndent()
        return ResponseEntity.ok(html)
    }
}
