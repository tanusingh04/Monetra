package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.payment.PaymentResponse;
import com.payflow.api.dto.payment.TransferRequest;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.PaymentProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payment Processing", description = "Atomic transfers, payment history and idempotency")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentProcessingService paymentProcessingService;

    public PaymentController(PaymentProcessingService paymentProcessingService) {
        this.paymentProcessingService = paymentProcessingService;
    }

    @PostMapping("/transfer")
    @Operation(summary = "Execute an atomic wallet-to-wallet transfer with idempotency guarantee")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestHeader("Idempotency-Key")
            @Parameter(description = "Unique client-generated idempotency key (e.g. UUID)", required = true)
            String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        PaymentResponse response = paymentProcessingService.transfer(request, idempotencyKey, userPrincipal.getId());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.success("Transfer completed successfully", response));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Fetch payment details by payment ID (authorized for sender/receiver only)")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentById(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable UUID paymentId) {

        PaymentResponse response = paymentProcessingService.getPaymentById(paymentId, userPrincipal.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/history")
    @Operation(summary = "Fetch paginated payment history for authenticated user's wallet")
    public ResponseEntity<ApiResponse<Page<PaymentResponse>>> getPaymentHistory(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<PaymentResponse> response = paymentProcessingService.getPaymentHistory(userPrincipal.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
