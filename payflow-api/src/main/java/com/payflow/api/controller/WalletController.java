package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.wallet.BalanceResponse;
import com.payflow.api.dto.wallet.CreateWalletRequest;
import com.payflow.api.dto.wallet.TransactionHistoryResponse;
import com.payflow.api.dto.wallet.WalletResponse;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
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

@RestController
@RequestMapping("/api/wallets")
@Tag(name = "Wallet Management", description = "User wallet queries and provisioning")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @Operation(summary = "Provision a wallet for the authenticated user")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody(required = false) CreateWalletRequest request) {
        String currency = request != null ? request.currency() : "INR";
        WalletResponse response = walletService.createWalletForUser(userPrincipal.getId(), currency);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Wallet created successfully", response));
    }

    @GetMapping("/me")
    @Operation(summary = "Fetch the authenticated user's wallet")
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        WalletResponse response = walletService.getWalletByUserId(userPrincipal.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/me/balance")
    @Operation(summary = "Fetch current wallet balance for authenticated user")
    public ResponseEntity<ApiResponse<BalanceResponse>> getMyBalance(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        BalanceResponse response = walletService.getBalanceByUserId(userPrincipal.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/me/transactions")
    @Operation(summary = "Fetch paginated transaction ledger history for authenticated user")
    public ResponseEntity<ApiResponse<Page<TransactionHistoryResponse>>> getMyTransactions(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<TransactionHistoryResponse> response = walletService.getTransactionHistory(userPrincipal.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
