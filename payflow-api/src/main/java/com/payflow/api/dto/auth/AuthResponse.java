package com.payflow.api.dto.auth;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String tokenType,
        UUID userId,
        String username,
        String email,
        String role,
        UUID walletId
) {
    public AuthResponse(String accessToken, UUID userId, String username, String email, String role, UUID walletId) {
        this(accessToken, "Bearer", userId, username, email, role, walletId);
    }
}
