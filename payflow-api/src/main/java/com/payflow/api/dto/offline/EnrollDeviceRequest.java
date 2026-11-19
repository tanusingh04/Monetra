package com.payflow.api.dto.offline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EnrollDeviceRequest(
        @NotBlank(message = "Device ID is required")
        @Size(max = 128, message = "Device ID must not exceed 128 characters")
        String deviceId,

        @Size(max = 128, message = "Device model must not exceed 128 characters")
        String deviceModel,

        @NotBlank(message = "Base64 ECDSA public key is required")
        String publicKey
) {}
